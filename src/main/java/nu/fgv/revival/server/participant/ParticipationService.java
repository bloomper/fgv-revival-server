/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.revival.server.participant;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.budget.BudgetSettings;
import nu.fgv.revival.server.budget.BudgetSettingsRepository;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.participant.ParticipantDtos.Contact;
import nu.fgv.revival.server.participant.ParticipantDtos.Participation;
import nu.fgv.revival.server.participant.ParticipantDtos.Settings;
import nu.fgv.revival.server.participant.ParticipantDtos.SwishPayment;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalService;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.settings.ApplicationSettingsService;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.Amounts;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.springframework.util.StringUtils.hasText;

/**
 * A participant's or helper's own page: their response with the terms and the
 * consent to FGV's register, contact details, dietary needs, the fee by Swish and referrals. Only the caller's own
 * participation, in a revival they see as its participant, i.e. while it is {@code OPEN}. What they change in
 * Spexregister is recorded as done by "a participant via the revival", not by name.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
@Slf4j
public class ParticipationService {

    static final int MAX_OPEN_REFERRALS = 20;

    private final ParticipantRepository repository;
    private final PendingPersonRepository pendingRepository;
    private final DietaryNeedRepository dietaryRepository;
    private final ParticipationSettingsRepository settingsRepository;
    private final ReferralRepository referralRepository;
    private final BudgetSettingsRepository budgetSettingsRepository;
    private final RevivalRepository revivalRepository;
    private final RevivalService revivalService;
    private final RevivalTitles titles;
    private final OwnerResolver ownerResolver;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final ParticipantAccess access;
    private final ParticipantViews views;
    private final ParticipantService participantService;
    private final ParticipantMailer mailer;
    private final SpexareDirectory directory;
    private final SwishQrClient swish;
    private final MessageSource messageSource;
    private final ApplicationSettingsService applicationSettings;
    private final Clock clock;

    public ParticipationService(final ParticipantRepository repository,
                                final PendingPersonRepository pendingRepository,
                                final DietaryNeedRepository dietaryRepository,
                                final ParticipationSettingsRepository settingsRepository,
                                final ReferralRepository referralRepository,
                                final BudgetSettingsRepository budgetSettingsRepository,
                                final RevivalRepository revivalRepository,
                                final RevivalService revivalService,
                                final RevivalTitles titles,
                                final OwnerResolver ownerResolver,
                                final CallerService callerService,
                                final AccessPolicy accessPolicy,
                                final ParticipantAccess access,
                                final ParticipantViews views,
                                final ParticipantService participantService,
                                final ParticipantMailer mailer,
                                final SpexareDirectory directory,
                                final SwishQrClient swish,
                                final MessageSource messageSource,
                                final ApplicationSettingsService applicationSettings,
                                final Clock clock) {
        this.repository = repository;
        this.pendingRepository = pendingRepository;
        this.dietaryRepository = dietaryRepository;
        this.settingsRepository = settingsRepository;
        this.referralRepository = referralRepository;
        this.budgetSettingsRepository = budgetSettingsRepository;
        this.revivalRepository = revivalRepository;
        this.revivalService = revivalService;
        this.titles = titles;
        this.ownerResolver = ownerResolver;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.access = access;
        this.views = views;
        this.participantService = participantService;
        this.mailer = mailer;
        this.directory = directory;
        this.swish = swish;
        this.messageSource = messageSource;
        this.applicationSettings = applicationSettings;
        this.clock = clock;
    }

    /**
     * Their contact details: a spexare's {@code HOME} address in Spexregister, null while it cannot be read; a pending
     * person's own.
     */
    private static @Nullable Contact contact(final Participant participant, final SpexregisterClient.@Nullable Spexare spexare,
                                             final @Nullable PendingPerson person) {
        if (participant.getSpexareId() != null) {
            if (spexare == null) {
                return null;
            }
            return spexare.home()
                    .map(home -> new Contact(ParticipantViews.blankToNull(home.emailAddress()),
                            hasText(home.phoneMobile()) ? home.phoneMobile() : ParticipantViews.blankToNull(home.phone()),
                            ParticipantViews.blankToNull(home.streetAddress()), ParticipantViews.blankToNull(home.postalCode()),
                            ParticipantViews.blankToNull(home.city())))
                    .orElseGet(() -> new Contact(null, null, null, null, null));
        }
        return person == null ? null : new Contact(person.getEmail(), person.getPhone(), person.getStreetAddress(), person.getPostalCode(), person.getCity());
    }

    /**
     * The caller's role in the revival, when they can see it.
     */
    @Transactional(readOnly = true)
    public @Nullable RevivalRole myRole(final Long revivalId) {
        final Caller caller = callerService.current();
        final RevivalRoleAssignment assignment = caller.roleIn(revivalId);

        if (assignment == null) {
            return null;
        }
        return revivalRepository.findById(revivalId).filter(revival -> accessPolicy.canSee(caller, revival)).map(_ -> assignment.role()).orElse(null);
    }

    /**
     * The caller's own participation; NOT_FOUND unless they are a participant or helper of a revival they can see.
     */
    @Transactional(readOnly = true)
    public Participation mine(final Long revivalId, final Locale locale) {
        final Caller caller = callerService.current();

        return participation(access.own(caller, revivalId), locale);
    }

    private Participation participation(final ParticipantAccess.Own own, final Locale locale) {
        final Participant participant = own.participant();
        final Revival revival = own.revival();
        final ParticipationSettings settings = settingsRepository.findByRevivalId(revival.getId()).orElse(null);
        final BudgetSettings budget = budgetSettingsRepository.findByRevivalId(revival.getId()).orElse(null);
        final BigDecimal fee = ParticipantViews.fee(participant, budget == null ? null : budget.getParticipationFee());
        final String acting = ActingPerson.participantOf(revival.getId());
        final SpexregisterClient.Spexare spexare = participant.getSpexareId() == null ? null
                : views.people(List.of(participant), acting, false).get(participant.getSpexareId());
        final PendingPerson person = pendingRepository.findById(participant.getId()).orElse(null);
        final ParticipantDtos.ParticipantDto dto = views.toDto(participant, new ParticipantViews.Options(false, false, false, acting, locale));
        final Contact contact = contact(participant, spexare, person);
        final String swishNumber = settings == null ? null : settings.getSwishNumber();
        final SwishPayment payment = swishNumber == null || fee == null || fee.signum() <= 0 || participant.isFeePaid() ? null
                : new SwishPayment(swishNumber, fee, SwishMessage.of(spexTitle(revival, locale), revival.getYear(), dto.firstName(), dto.lastName()), true);

        return new Participation(
                dto,
                revivalService.findById(revival.getId()),
                Settings.of(settings),
                terms(fee, budget == null ? null : budget.getLoanAmount(), applicationSettings.terms(locale), settings, locale),
                contact,
                dietaryRepository.findById(participant.getId()).map(DietaryNeed::getText).orElse(null),
                payment,
                participant.getResponse() == ParticipantResponse.YES && settings != null ? settings.getWhatsappLink() : null,
                ownerResolver.activeMembers(revival.getId()).entrySet().stream()
                        .map(entry -> new PersonRefDto(entry.getKey(), entry.getValue()))
                        .toList());
    }

    /**
     * The spex's title, without the year, as Swish's message starts with it.
     */
    private String spexTitle(final Revival revival, final Locale locale) {
        final String title = titles.ofCached(revival, locale);
        final String year = " " + revival.getYear();

        return title.endsWith(year) ? title.substring(0, title.length() - year.length()) : title;
    }

    /**
     * What accepting means: the fee when there is one, the shared liability for the loan from FGV/JK, the terms every
     * participant accepts, and the revival's own terms.
     */
    List<String> terms(final @Nullable BigDecimal fee, final @Nullable BigDecimal loan, final @Nullable String applicationTerms,
                       final @Nullable ParticipationSettings settings, final Locale locale) {
        final List<String> terms = new ArrayList<>();

        if (fee != null && fee.signum() > 0) {
            terms.add(text("participant.terms.fee", locale, Amounts.format(fee)));
        }
        terms.add(loan != null && loan.signum() > 0 ? text("participant.terms.loan", locale, Amounts.format(loan))
                : text("participant.terms.loanWithoutAmount", locale));
        if (hasText(applicationTerms)) {
            terms.add(applicationTerms.strip());
        }
        if (settings != null && hasText(settings.getTerms())) {
            terms.add(settings.getTerms().strip());
        }
        return terms;
    }

    /**
     * The caller's response. Yes needs the terms accepted; yes and maybe ask for the consent to FGV's
     * register the first time. Consenting writes the consent to Spexregister, and turns a pending person into an
     * unpublished spexare there; a spexare declining is recorded there only when they had consented before. No from a
     * pending person deletes their name and contact details, and their role.
     */
    @Transactional
    public Participation respond(final Response input, final Locale locale) {
        final Caller caller = callerService.current();
        final ParticipantAccess.Own own = access.own(caller, input.revivalId());
        final Participant participant = own.participant();
        final Instant now = Instant.now(clock);

        if (input.response() == ParticipantResponse.PENDING) {
            throw new BadRequestException("error.participant.respond.pending");
        }

        final boolean yes = input.response() == ParticipantResponse.YES;

        if (yes && !Boolean.TRUE.equals(input.termsAccepted())) {
            throw new BadRequestException("error.participant.respond.terms");
        }
        if ((yes || input.response() == ParticipantResponse.MAYBE) && participant.getRegisterConsent() == null && input.registerConsent() == null) {
            throw new BadRequestException("error.participant.respond.consent");
        }
        if (input.preferredPositionId() != null && directory.positions().stream().noneMatch(position -> position.id().equals(input.preferredPositionId()))) {
            throw new BadRequestException("error.participant.position.unknown");
        }

        final String comment = ParticipantRules.text(input.comment(), Participant.COMMENT_MAX_LENGTH, "error.participant.comment.tooLong");

        AuditContext.operation("participationRespond");

        final boolean firstYes = yes && participant.getTermsAcceptedAt() == null;

        participant.setResponse(input.response());
        participant.setRespondedAt(now);
        participant.setComment(comment);
        participant.setPreferredTaskId(input.preferredPositionId());
        if (yes) {
            participant.setTermsAcceptedAt(now);
        }
        if (input.response() != ParticipantResponse.NO && input.registerConsent() != null) {
            consent(participant, input.registerConsent(), own.revival().getId(), now);
        }

        if (input.response() == ParticipantResponse.NO && participant.isPending()) {
            participantService.close(participant, caller.person().getId());
        } else {
            repository.saveAndFlush(participant);
        }
        if (firstYes) {
            confirm(caller, own, locale);
        }
        if (participant.isRemoved()) {
            return new Participation(views.toDto(participant, new ParticipantViews.Options(false, false, false,
                    ActingPerson.participantOf(own.revival().getId()), locale)), revivalService.findById(own.revival().getId()),
                    Settings.of(null), List.of(), null, null, null, null, List.of());
        }
        return participation(own, locale);
    }

    /**
     * Records the consent and what follows from it in Spexregister. A pending person who consents
     * becomes a spexare: if Spexregister fails part way, the response is refused and they stay pending; a spexare it
     * created before failing is left there, unpublished.
     */
    private void consent(final Participant participant, final boolean value, final Long revivalId, final Instant now) {
        final String acting = ActingPerson.participantOf(revivalId);

        if (participant.getSpexareId() != null) {
            final SpexregisterClient.Spexare spexare = directory.byId(participant.getSpexareId(), acting);

            if (spexare == null) {
                throw new SpexregisterUnavailableException(new IllegalStateException("The spexare cannot be read"));
            }

            final SpexregisterClient.Consent existing = spexare.registerConsent().orElse(null);

            if (value || existing != null) {
                directory.saveRegisterConsent(participant.getSpexareId(), existing == null ? null : existing.id(), value, acting);
            }
        } else if (value) {
            final PendingPerson person = pendingRepository.findById(participant.getId())
                    .orElseThrow(() -> new BadRequestException("error.participant.removed"));
            final Long spexareId = directory.create(person.getFirstName(), person.getLastName(), acting);

            try {
                directory.saveHomeAddress(spexareId, null, new SpexregisterClient.AddressInput(person.getEmail(), person.getPhone(),
                        person.getStreetAddress(), person.getPostalCode(), person.getCity()), acting);
                directory.saveRegisterConsent(spexareId, null, true, acting);
            } catch (final SpexregisterUnavailableException e) {
                log.warn("Created an unpublished spexare for a pending participant of revival {}, but could not finish: {}", revivalId,
                        e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
                throw e;
            }
            participant.setSpexareId(spexareId);
            pendingRepository.delete(person);
        }
        participant.setRegisterConsent(value);
        participant.setRegisterConsentAt(now);
    }

    /**
     * Confirms a first yes by email to the address they log in with, with the fee and Swish details and the WhatsApp
     * group.
     */
    private void confirm(final Caller caller, final ParticipantAccess.Own own, final Locale locale) {
        final String to = caller.person().getEmail();

        if (!hasText(to)) {
            return;
        }

        final Participation participation = participation(own, locale);
        final Locale language = nu.fgv.revival.server.util.Locales.supported(locale);
        final ParticipantMailer.Confirmation confirmation = new ParticipantMailer.Confirmation(titles.ofCached(own.revival(), language),
                participation.participant().fee(),
                participation.swish() == null ? null : participation.swish().number(),
                participation.swish() == null ? null : participation.swish().message(),
                participation.whatsappLink());

        mailer.sendAfterCommit(List.of(mailer.confirmation(own.revival(), confirmation, to, language)));
    }

    /**
     * The caller's contact details. A pending person's are kept here; a spexare's are written to Spexregister
     * only with their consent, and refused without it, since they are then stored nowhere.
     */
    @Transactional
    public Participation updateContact(final ContactChange change, final Locale locale) {
        final Caller caller = callerService.current();
        final ParticipantAccess.Own own = access.own(caller, change.revivalId());
        final Participant participant = own.participant();
        final String email = ParticipantRules.email(change.email());
        final String phone = ParticipantRules.phone(change.phone());
        final String street = ParticipantRules.text(change.streetAddress(), ParticipantRules.ADDRESS_MAX_LENGTH, "error.participant.address.tooLong");
        final String postalCode = ParticipantRules.text(change.postalCode(), ParticipantRules.POSTAL_CODE_MAX_LENGTH, "error.participant.address.tooLong");
        final String city = ParticipantRules.text(change.city(), ParticipantRules.CITY_MAX_LENGTH, "error.participant.address.tooLong");

        AuditContext.operation("participationContactUpdate");
        if (participant.getSpexareId() == null) {
            final PendingPerson person = pendingRepository.findById(participant.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(Participant.class, change.revivalId()));

            if (participant.getKind() == ParticipantKind.PARTICIPANT && email == null) {
                throw new BadRequestException("error.participant.emailRequired");
            }
            if (email == null && phone == null) {
                throw new BadRequestException("error.participant.contactRequired");
            }
            person.setEmail(email);
            person.setPhone(phone);
            person.setStreetAddress(street);
            person.setPostalCode(postalCode);
            person.setCity(city);
            pendingRepository.saveAndFlush(person);
        } else {
            if (!Boolean.TRUE.equals(participant.getRegisterConsent())) {
                throw new BadRequestException("error.participant.contact.noConsent");
            }

            final String acting = ActingPerson.participantOf(own.revival().getId());
            final SpexregisterClient.Spexare spexare = directory.byId(participant.getSpexareId(), acting);

            if (spexare == null) {
                throw new SpexregisterUnavailableException(new IllegalStateException("The spexare cannot be read"));
            }
            directory.saveHomeAddress(participant.getSpexareId(), spexare.home().map(SpexregisterClient.Address::id).orElse(null),
                    new SpexregisterClient.AddressInput(email, phone, street, postalCode, city), acting);
        }
        return participation(own, locale);
    }

    /**
     * The caller's dietary needs and allergies, at most 1000 characters; blank deletes them.
     */
    @Transactional
    public Participation setDietary(final Long revivalId, final @Nullable String text, final Locale locale) {
        final Caller caller = callerService.current();
        final ParticipantAccess.Own own = access.own(caller, revivalId);
        final String trimmed = ParticipantRules.text(text, DietaryNeed.TEXT_MAX_LENGTH, "error.participant.dietary.tooLong");
        final Long participantId = own.participant().getId();

        if (trimmed == null) {
            dietaryRepository.findById(participantId).ifPresent(dietaryRepository::delete);
        } else {
            final DietaryNeed need = dietaryRepository.findById(participantId).orElseGet(DietaryNeed::new);

            need.setParticipantId(participantId);
            need.setText(trimmed);
            need.setUpdatedAt(Instant.now(clock));
            dietaryRepository.saveAndFlush(need);
        }
        return participation(own, locale);
    }

    /**
     * Suggests someone the steering group may be missing: a participant, at most
     * {@value #MAX_OPEN_REFERRALS} open suggestions each.
     */
    @Transactional
    public void createReferral(final NewReferral input) {
        final Caller caller = callerService.current();
        final ParticipantAccess.Own own = access.own(caller, input.revivalId());

        if (own.participant().getKind() != ParticipantKind.PARTICIPANT) {
            throw new ResourceNotFoundException(Participant.class, input.revivalId());
        }

        final String name = ParticipantRules.trimmed(input.name());

        if (name == null || name.length() > Referral.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.participant.referral.name", Referral.NAME_MAX_LENGTH);
        }
        if (referralRepository.countByReferredByParticipantId(own.participant().getId()) >= MAX_OPEN_REFERRALS) {
            throw new BadRequestException("error.participant.referral.tooMany", MAX_OPEN_REFERRALS);
        }

        final Referral referral = new Referral();

        referral.setRevivalId(own.revival().getId());
        referral.setReferredByParticipantId(own.participant().getId());
        referral.setName(name);
        referral.setContact(ParticipantRules.text(input.contact(), Referral.CONTACT_MAX_LENGTH, "error.participant.referral.contact"));
        referral.setComment(ParticipantRules.text(input.comment(), Referral.COMMENT_MAX_LENGTH, "error.participant.referral.comment"));
        referral.setCreatedAt(Instant.now(clock));
        referralRepository.saveAndFlush(referral);
    }

    /**
     * The QR code for the caller's fee as SVG: a participant of an {@code OPEN} revival with a Swish number and an
     * unpaid fee above nothing; NOT_FOUND otherwise.
     */
    @Transactional(readOnly = true)
    public byte[] swishQr(final Long revivalId, final Locale locale) {
        final Caller caller = callerService.current();
        final Participation participation = participation(access.own(caller, revivalId), locale);
        final SwishPayment payment = participation.swish();

        if (payment == null) {
            throw new ResourceNotFoundException(SwishPayment.class, revivalId);
        }
        return swish.svg(payment.number(), payment.amount(), payment.message());
    }

    private String text(final String key, final Locale locale, final Object... arguments) {
        return Objects.requireNonNull(messageSource.getMessage(key, arguments, key, locale));
    }

    public record Response(Long revivalId, ParticipantResponse response, @Nullable String preferredPositionId,
                           @Nullable String comment,
                           @Nullable Boolean termsAccepted, @Nullable Boolean registerConsent) {
    }

    public record ContactChange(Long revivalId, @Nullable String email, @Nullable String phone,
                                @Nullable String streetAddress,
                                @Nullable String postalCode, @Nullable String city) {
    }

    public record NewReferral(Long revivalId, String name, @Nullable String contact, @Nullable String comment) {
    }
}
