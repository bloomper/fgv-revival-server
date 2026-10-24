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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalMemberService;
import nu.fgv.revival.server.participant.ParticipantDtos.Counts;
import nu.fgv.revival.server.participant.ParticipantDtos.ParticipantDto;
import nu.fgv.revival.server.participant.ParticipantDtos.PositionCount;
import nu.fgv.revival.server.participant.ParticipantDtos.ReferralDto;
import nu.fgv.revival.server.participant.ParticipantDtos.ResponseCount;
import nu.fgv.revival.server.participant.ParticipantDtos.Settings;
import nu.fgv.revival.server.participant.ParticipantDtos.SpexareCandidate;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The steering group's side of participants (product spec 5.6, M6-FR-2–4, M6-FR-16–18): candidates from Spexregister,
 * adding, changing and removing participants, fees, settings, counts and referrals. Every method resolves the caller and
 * asks {@link AccessPolicy} first (constitution III).
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
@Service
public class ParticipantService {

    static final int SEARCH_MIN_LENGTH = 3;
    static final int MAX_SEARCH_RESULTS = 20;
    static final int MAX_BULK = SpexregisterClient.MAX_SPEXARE;
    static final int MIN_MEMBERSHIP_YEAR = 1948;
    static final int MAX_MEMBERSHIP_YEAR = 2100;

    private final ParticipantRepository repository;
    private final PendingPersonRepository pendingRepository;
    private final DietaryNeedRepository dietaryRepository;
    private final ParticipationSettingsRepository settingsRepository;
    private final ReferralRepository referralRepository;
    private final RevivalRepository revivalRepository;
    private final RevivalMemberRepository memberRepository;
    private final RevivalMemberService memberService;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final ParticipantAccess access;
    private final ParticipantViews views;
    private final SpexareDirectory directory;
    private final Clock clock;

    public ParticipantService(final ParticipantRepository repository,
                              final PendingPersonRepository pendingRepository,
                              final DietaryNeedRepository dietaryRepository,
                              final ParticipationSettingsRepository settingsRepository,
                              final ReferralRepository referralRepository,
                              final RevivalRepository revivalRepository,
                              final RevivalMemberRepository memberRepository,
                              final RevivalMemberService memberService,
                              final CallerService callerService,
                              final AccessPolicy accessPolicy,
                              final ParticipantAccess access,
                              final ParticipantViews views,
                              final SpexareDirectory directory,
                              final Clock clock) {
        this.repository = repository;
        this.pendingRepository = pendingRepository;
        this.dietaryRepository = dietaryRepository;
        this.settingsRepository = settingsRepository;
        this.referralRepository = referralRepository;
        this.revivalRepository = revivalRepository;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.access = access;
        this.views = views;
        this.directory = directory;
        this.clock = clock;
    }

    /**
     * A new participant: a spexare, or a pending person by name with email or phone.
     */
    public record NewParticipant(Long revivalId, ParticipantKind kind, @Nullable Long spexareId, @Nullable String firstName,
                                 @Nullable String lastName, @Nullable String email, @Nullable String phone, @Nullable String positionId,
                                 @Nullable String roleName, @Nullable String vocalPartId, @Nullable BigDecimal compensationPerShift) {
    }

    /**
     * Omitted fields are unchanged; the {@code clear*} flags clear them.
     */
    public record Change(Long id, Long version, @Nullable String positionId, boolean clearPosition, @Nullable String roleName,
                         boolean clearRoleName, @Nullable String vocalPartId, boolean clearVocalPart, @Nullable BigDecimal fee, boolean clearFee,
                         @Nullable BigDecimal compensationPerShift, @Nullable String firstName, @Nullable String lastName, @Nullable String email,
                         boolean clearEmail, @Nullable String phone, boolean clearPhone) {
    }

    public record SettingsChange(Long revivalId, Long version, @Nullable String swishNumber, @Nullable String whatsappLink, @Nullable String terms) {
    }

    private ParticipantViews.Options options(final Caller caller, final Revival revival, final Locale locale) {
        return new ParticipantViews.Options(accessPolicy.canReadParticipants(caller, revival), accessPolicy.canReadDietary(caller, revival), true,
                ActingPerson.of(caller), locale);
    }

    private ParticipantDto toDto(final Caller caller, final Revival revival, final Participant participant, final Locale locale) {
        return views.toDto(participant, options(caller, revival, locale));
    }

    // --- Reading ----------------------------------------------------------------------------------------------------

    /**
     * The revival's participants and helpers, by id, removed ones only when asked. SPEXREGISTER_UNAVAILABLE when any is
     * a spexare and Spexregister cannot be read.
     */
    @Transactional(readOnly = true)
    public List<ParticipantDto> list(final Long revivalId, final boolean includeRemoved, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = access.readable(caller, revivalId);
        final List<Participant> participants = includeRemoved ? repository.findByRevivalIdOrderByIdAsc(revivalId)
                : repository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revivalId);

        return views.toDtos(participants, options(caller, revival, locale));
    }

    @Transactional(readOnly = true)
    public ParticipantDto get(final Long id, final Locale locale) {
        final Caller caller = callerService.current();
        final Participant participant = access.readableParticipant(caller, id);

        return toDto(caller, revivalRepository.findById(participant.getRevivalId()).orElseThrow(), participant, locale);
    }

    /**
     * Spexare who took part in the revival's original spex or its earlier revivals, not deceased and not already a
     * participant, by last and first name, with the spex of that family they took part in, newest first (M6-FR-2).
     */
    @Transactional(readOnly = true)
    public List<SpexareCandidate> candidates(final Long revivalId) {
        final Caller caller = callerService.current();
        final Revival revival = access.writable(caller, revivalId);
        final String acting = ActingPerson.of(caller);
        final Set<Long> added = addedSpexare(revivalId);
        final List<SpexregisterClient.Spexare> listed = directory.bySpex(revival.getSpexId(), acting).stream()
                .filter(spexare -> !spexare.isDeceased() && !added.contains(Long.valueOf(spexare.id())))
                .toList();
        final Set<Long> family = directory.family(revival.getSpexId());
        final Map<Long, SpexregisterClient.Spexare> detailed = directory.byIds(listed.stream().map(spexare -> Long.valueOf(spexare.id())).toList(), acting);

        return listed.stream()
                .map(spexare -> candidate(spexare, detailed.get(Long.valueOf(spexare.id())), family, false))
                .sorted(Comparator.comparing((SpexareCandidate candidate) -> candidate.lastName().toLowerCase(Locale.ROOT))
                        .thenComparing(candidate -> candidate.firstName().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * Spexare whose name matches, at least {@value #SEARCH_MIN_LENGTH} characters after trimming, at most
     * {@value #MAX_SEARCH_RESULTS}, without the deceased; {@code added} marks those already participants.
     */
    @Transactional(readOnly = true)
    public List<SpexareCandidate> search(final Long revivalId, final String query) {
        final Caller caller = callerService.current();

        access.writable(caller, revivalId);

        final String trimmed = query.strip();

        if (trimmed.length() < SEARCH_MIN_LENGTH) {
            throw new BadRequestException("error.participant.search.tooShort", SEARCH_MIN_LENGTH);
        }

        final String acting = ActingPerson.of(caller);
        final Set<Long> added = addedSpexare(revivalId);
        final List<SpexregisterClient.Spexare> found = directory.search(trimmed, acting).stream()
                .filter(spexare -> !spexare.isDeceased())
                .limit(MAX_SEARCH_RESULTS)
                .toList();
        final Map<Long, SpexregisterClient.Spexare> detailed = directory.byIds(found.stream().map(spexare -> Long.valueOf(spexare.id())).toList(), acting);

        return found.stream()
                .map(spexare -> candidate(spexare, detailed.get(Long.valueOf(spexare.id())), null, added.contains(Long.valueOf(spexare.id()))))
                .toList();
    }

    private Set<Long> addedSpexare(final Long revivalId) {
        return repository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revivalId).stream()
                .map(Participant::getSpexareId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * "Title year" for each spex they took part in, newest first; only the given family's when there is one.
     */
    static List<String> history(final SpexregisterClient.@Nullable Spexare spexare, final @Nullable Set<Long> family) {
        if (spexare == null) {
            return List.of();
        }
        return spexare.spex().stream()
                .filter(spex -> family == null || family.contains(Long.valueOf(spex.id())))
                .sorted(Comparator.comparing((SpexregisterClient.SpexNode spex) -> spex.year() == null ? "" : spex.year()).reversed())
                .map(spex -> ((spex.title() == null ? "" : spex.title()) + " " + (spex.year() == null ? "" : spex.year())).strip())
                .distinct()
                .toList();
    }

    private static SpexareCandidate candidate(final SpexregisterClient.Spexare spexare, final SpexregisterClient.@Nullable Spexare detailed,
                                              final @Nullable Set<Long> family, final boolean added) {
        return new SpexareCandidate(Long.valueOf(spexare.id()), spexare.firstName(), spexare.lastName(), ParticipantViews.blankToNull(spexare.nickName()),
                ParticipantViews.blankToNull(spexare.graduation()), history(detailed, family), added);
    }

    /**
     * Spexregister's tasks as positions. Every signed-in caller.
     */
    public List<SpexareDirectory.Position> positions() {
        callerService.current();
        return directory.positions();
    }

    public List<SpexareDirectory.VocalPart> vocalParts() {
        callerService.current();
        return directory.vocalParts();
    }

    // --- Adding and changing ----------------------------------------------------------------------------------------

    /**
     * Adds a spexare who exists in Spexregister, is not deceased and is not already a participant, or a pending person:
     * a participant needs an email address to be invited, a helper an email address or a phone number (M6-FR-3, 4).
     */
    @Transactional
    public ParticipantDto add(final NewParticipant input, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = access.writable(caller, input.revivalId());

        AuditContext.operation("participantAdd");

        final Participant participant = new Participant();
        PendingPerson person = null;

        participant.setRevivalId(revival.getId());
        participant.setKind(input.kind());
        if (input.spexareId() != null) {
            participant.setSpexareId(requireAddable(revival.getId(), input.spexareId(), ActingPerson.of(caller)));
        } else {
            person = pendingPerson(input.kind(), input.firstName(), input.lastName(), input.email(), input.phone());
        }
        setPosition(participant, input.positionId(), input.roleName(), input.vocalPartId());
        participant.setCompensationPerShift(compensation(input.kind(), input.compensationPerShift()));

        final Participant saved = save(participant);

        if (person != null) {
            person.setParticipantId(saved.getId());
            pendingRepository.saveAndFlush(person);
        }
        return toDto(caller, revival, saved, locale);
    }

    /**
     * Adds every listed spexare as a participant, skipping those already added, unknown to Spexregister or deceased, at
     * most {@value #MAX_BULK} per call.
     */
    @Transactional
    public List<ParticipantDto> addSpexare(final Long revivalId, final List<Long> spexareIds, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = access.writable(caller, revivalId);

        if (spexareIds.size() > MAX_BULK) {
            throw new BadRequestException("error.participant.tooMany", MAX_BULK);
        }
        AuditContext.operation("participantsAddSpexare");

        final Set<Long> added = addedSpexare(revivalId);
        final Map<Long, SpexregisterClient.Spexare> known = directory.byIds(spexareIds, ActingPerson.of(caller));
        final List<Participant> created = new ArrayList<>();

        for (final Long spexareId : new java.util.LinkedHashSet<>(spexareIds)) {
            final SpexregisterClient.Spexare spexare = known.get(spexareId);

            if (spexare == null || spexare.isDeceased() || !added.add(spexareId)) {
                continue;
            }

            final Participant participant = new Participant();

            participant.setRevivalId(revivalId);
            participant.setKind(ParticipantKind.PARTICIPANT);
            participant.setSpexareId(spexareId);
            created.add(save(participant));
        }
        return views.toDtos(created, options(caller, revival, locale));
    }

    @Transactional
    public ParticipantDto update(final Change change, final Locale locale) {
        final Caller caller = callerService.current();
        final Participant participant = changeable(caller, change.id(), change.version());
        final Revival revival = revivalRepository.findById(participant.getRevivalId()).orElseThrow();

        AuditContext.operation("participantUpdate");

        final String positionId = change.clearPosition() ? null : change.positionId() != null ? change.positionId() : participant.getPositionTaskId();
        final String roleName = change.clearRoleName() ? null : change.roleName() != null ? change.roleName() : participant.getRoleName();
        final String vocalPartId = change.clearVocalPart() ? null : change.vocalPartId() != null ? change.vocalPartId() : participant.getVocalPartId();

        if (!Objects.equals(positionId, participant.getPositionTaskId()) || !Objects.equals(ParticipantRules.trimmed(roleName), participant.getRoleName())
                || !Objects.equals(vocalPartId, participant.getVocalPartId())) {
            setPosition(participant, positionId, roleName, vocalPartId);
        }
        if (change.clearFee()) {
            participant.setFee(null);
        } else if (change.fee() != null) {
            if (participant.getKind() == ParticipantKind.HELPER) {
                throw new BadRequestException("error.participant.fee.helper");
            }
            participant.setFee(ParticipantRules.amount(change.fee()));
        }
        if (change.compensationPerShift() != null) {
            participant.setCompensationPerShift(compensation(participant.getKind(), change.compensationPerShift()));
        }

        final boolean personChanged = change.firstName() != null || change.lastName() != null || change.email() != null || change.phone() != null
                || change.clearEmail() || change.clearPhone();

        if (personChanged) {
            final PendingPerson person = pendingRepository.findById(participant.getId())
                    .orElseThrow(() -> new BadRequestException("error.participant.pendingOnly"));
            final String email = change.clearEmail() ? null : change.email() != null ? ParticipantRules.email(change.email()) : person.getEmail();
            final String phone = change.clearPhone() ? null : change.phone() != null ? ParticipantRules.phone(change.phone()) : person.getPhone();

            person.setFirstName(change.firstName() != null ? ParticipantRules.name(change.firstName()) : person.getFirstName());
            person.setLastName(change.lastName() != null ? ParticipantRules.name(change.lastName()) : person.getLastName());
            requireReachable(participant.getKind(), email, phone);
            person.setEmail(email);
            person.setPhone(phone);
            pendingRepository.saveAndFlush(person);
            // The pending person is not audited, so the participant's version moves on for the change
            participant.setLastModifiedAt(Instant.now(clock));
        }
        return toDto(caller, revival, repository.saveAndFlush(participant), locale);
    }

    /**
     * Closes the participant and their participant role. A pending person's name and contact details, and anyone's
     * dietary needs, are deleted (product spec section 7).
     */
    @Transactional
    public void remove(final Long id, final Long version) {
        final Caller caller = callerService.current();
        final Participant participant = changeable(caller, id, version);

        AuditContext.operation("participantRemove");
        close(participant, caller.person().getId());
    }

    /**
     * Closes a participant without checking the caller: for removal, a pending person's "no", and the cleanup when a
     * revival leaves {@code OPEN}.
     */
    void close(final Participant participant, final @Nullable Long removedBy) {
        participant.setRemovedAt(Instant.now(clock));
        participant.setRemovedBy(removedBy);
        repository.saveAndFlush(participant);
        pendingRepository.findById(participant.getId()).ifPresent(pendingRepository::delete);
        dietaryRepository.findById(participant.getId()).ifPresent(dietaryRepository::delete);
        if (participant.getMemberId() != null) {
            memberRepository.findById(participant.getMemberId())
                    .filter(member -> !member.isRemoved())
                    .ifPresent(member -> memberService.close(member, removedBy));
        }
    }

    /**
     * The year the revival pays FGV membership for, and for whom (M7-FR-1): participants who answered yes and are not
     * removed; everyone else is unmarked. A directeur while the revival is closing or settling, or a super admin. Returns
     * the participants who may be marked.
     */
    @Transactional
    public List<ParticipantDto> setMembership(final Long revivalId, final Integer year, final Collection<Long> participantIds, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = access.visible(caller, revivalId);

        if (revival.isAnonymized()) {
            throw new BadRequestException("error.revival.anonymized");
        }
        accessPolicy.requireSetMembership(caller, revival);
        if (year == null || year < MIN_MEMBERSHIP_YEAR || year > MAX_MEMBERSHIP_YEAR) {
            throw new BadRequestException("error.membership.year", MIN_MEMBERSHIP_YEAR, MAX_MEMBERSHIP_YEAR);
        }

        final List<Participant> all = repository.findByRevivalIdOrderByIdAsc(revivalId);
        final List<Participant> eligible = all.stream()
                .filter(participant -> !participant.isRemoved() && participant.getKind() == ParticipantKind.PARTICIPANT
                        && participant.getResponse() == ParticipantResponse.YES)
                .toList();
        final Set<Long> marked = Set.copyOf(participantIds);

        if (!eligible.stream().map(Participant::getId).collect(Collectors.toSet()).containsAll(marked)) {
            throw new BadRequestException("error.membership.participant");
        }
        AuditContext.operation("membershipSet");
        if (!year.equals(revival.getMembershipYear())) {
            revival.setMembershipYear(year);
            revivalRepository.saveAndFlush(revival);
        }
        for (final Participant participant : all) {
            final boolean paid = marked.contains(participant.getId());

            if (participant.isMembershipPaid() != paid) {
                participant.setMembershipPaid(paid);
                repository.saveAndFlush(participant);
            }
        }
        return views.toDtos(eligible, options(caller, revival, locale));
    }

    /**
     * Marks a participant's fee paid or not: writers of the budget (M5's {@code FINANCE}).
     */
    @Transactional
    public ParticipantDto setFeePaid(final Long id, final Long version, final boolean paid, final Locale locale) {
        final Caller caller = callerService.current();
        final Participant participant = access.readableParticipant(caller, id);
        final Revival revival = revivalRepository.findById(participant.getRevivalId()).orElseThrow();

        accessPolicy.requireWriteBudget(caller, revival);
        requireCurrent(participant, version);
        if (participant.getKind() == ParticipantKind.HELPER) {
            throw new BadRequestException("error.participant.fee.helper");
        }
        AuditContext.operation("participantFeeSetPaid");
        if (paid && participant.getFeePaidAt() == null) {
            participant.setFeePaidAt(Instant.now(clock));
            participant.setFeePaidBy(caller.person().getId());
        } else if (!paid) {
            participant.setFeePaidAt(null);
            participant.setFeePaidBy(null);
        }
        return toDto(caller, revival, repository.saveAndFlush(participant), locale);
    }

    private Participant changeable(final Caller caller, final Long id, final Long version) {
        final Participant participant = access.readableParticipant(caller, id);

        access.writable(caller, participant.getRevivalId());
        requireCurrent(participant, version);
        if (participant.isRemoved()) {
            throw new BadRequestException("error.participant.removed");
        }
        return participant;
    }

    private static void requireCurrent(final Participant participant, final Long version) {
        if (!Objects.equals(version, participant.getVersion())) {
            throw new ConflictException("error.conflict");
        }
    }

    private Participant save(final Participant participant) {
        try {
            return repository.saveAndFlush(participant);
        } catch (final DataIntegrityViolationException _) {
            throw new BadRequestException("error.participant.duplicate");
        }
    }

    private Long requireAddable(final Long revivalId, final Long spexareId, final String acting) {
        final SpexregisterClient.Spexare spexare = directory.byId(spexareId, acting);

        if (spexare == null) {
            throw new BadRequestException("error.participant.spexare.notFound");
        }
        if (spexare.isDeceased()) {
            throw new BadRequestException("error.participant.spexare.deceased");
        }
        if (repository.existsByRevivalIdAndSpexareIdAndRemovedAtIsNull(revivalId, spexareId)) {
            throw new BadRequestException("error.participant.duplicate");
        }
        return spexareId;
    }

    /**
     * A pending person: names 1–100 characters; a participant needs an email address, a helper an email address or a phone
     * number.
     */
    static PendingPerson pendingPerson(final ParticipantKind kind, final @Nullable String firstName, final @Nullable String lastName,
                                       final @Nullable String email, final @Nullable String phone) {
        final PendingPerson person = new PendingPerson();
        final String validEmail = ParticipantRules.email(email);
        final String validPhone = ParticipantRules.phone(phone);

        person.setFirstName(ParticipantRules.name(firstName));
        person.setLastName(ParticipantRules.name(lastName));
        requireReachable(kind, validEmail, validPhone);
        person.setEmail(validEmail);
        person.setPhone(validPhone);
        return person;
    }

    private static void requireReachable(final ParticipantKind kind, final @Nullable String email, final @Nullable String phone) {
        if (kind == ParticipantKind.PARTICIPANT && email == null) {
            throw new BadRequestException("error.participant.emailRequired");
        }
        if (email == null && phone == null) {
            throw new BadRequestException("error.participant.contactRequired");
        }
    }

    /**
     * A planned position from Spexregister's tasks; a role name and vocal part only for an ensemble position.
     */
    private void setPosition(final Participant participant, final @Nullable String positionId, final @Nullable String roleName,
                             final @Nullable String vocalPartId) {
        final String trimmedRole = ParticipantRules.text(roleName, Participant.ROLE_NAME_MAX_LENGTH, "error.participant.roleName.tooLong");

        if (positionId == null) {
            if (trimmedRole != null || vocalPartId != null) {
                throw new BadRequestException("error.participant.role.notEnsemble");
            }
        } else {
            final SpexareDirectory.Position position = directory.positions().stream()
                    .filter(candidate -> candidate.id().equals(positionId))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException("error.participant.position.unknown"));

            if (!position.hasRole() && (trimmedRole != null || vocalPartId != null)) {
                throw new BadRequestException("error.participant.role.notEnsemble");
            }
            if (vocalPartId != null && directory.vocalParts().stream().noneMatch(part -> part.id().equals(vocalPartId))) {
                throw new BadRequestException("error.participant.vocalPart.unknown");
            }
        }
        participant.setPositionTaskId(positionId);
        participant.setRoleName(trimmedRole);
        participant.setVocalPartId(vocalPartId);
    }

    private static @Nullable BigDecimal compensation(final ParticipantKind kind, final @Nullable BigDecimal compensation) {
        if (compensation != null && kind != ParticipantKind.HELPER) {
            throw new BadRequestException("error.participant.compensation.helperOnly");
        }
        return ParticipantRules.amount(compensation);
    }

    // --- Settings ---------------------------------------------------------------------------------------------------

    /**
     * The revival's Swish number, WhatsApp link and extra terms, for readers of participants; null for others.
     */
    @Transactional(readOnly = true)
    public @Nullable Settings settings(final Long revivalId) {
        final Caller caller = callerService.current();
        final Revival revival = access.visible(caller, revivalId);

        if (!accessPolicy.canReadParticipants(caller, revival)) {
            return null;
        }
        return Settings.of(settingsRepository.findByRevivalId(revivalId).orElse(null));
    }

    /**
     * Replaces the settings; the first save makes them, from version 0.
     */
    @Transactional
    public Settings updateSettings(final SettingsChange change) {
        final Caller caller = callerService.current();

        access.writable(caller, change.revivalId());

        final ParticipationSettings settings = settingsRepository.findByRevivalId(change.revivalId())
                .orElseGet(() -> new ParticipationSettings(change.revivalId()));
        final long current = settings.getVersion() == null ? 0L : settings.getVersion();

        if (!Objects.equals(change.version(), current)) {
            throw new ConflictException("error.conflict");
        }
        AuditContext.operation("participationSettingsUpdate");
        settings.setSwishNumber(ParticipantRules.swishNumber(change.swishNumber()));
        settings.setWhatsappLink(ParticipantRules.whatsappLink(change.whatsappLink()));
        settings.setTerms(ParticipantRules.text(change.terms(), ParticipationSettings.TERMS_MAX_LENGTH, "error.participant.settings.terms"));
        try {
            return Settings.of(settingsRepository.saveAndFlush(settings));
        } catch (final DataIntegrityViolationException _) {
            // Two first saves at once
            throw new ConflictException("error.conflict");
        }
    }

    // --- Counts -----------------------------------------------------------------------------------------------------

    /**
     * Counts for the dashboard, from the database alone; null for callers who do not read participants. Fees count
     * participants, not helpers: paid, and unpaid among those who said yes and owe more than nothing.
     */
    @Transactional(readOnly = true)
    public @Nullable Counts counts(final Long revivalId) {
        final Caller caller = callerService.current();
        final Revival revival = access.visible(caller, revivalId);

        if (!accessPolicy.canReadParticipants(caller, revival)) {
            return null;
        }

        final List<Participant> participants = repository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revivalId);
        final Map<Long, InvitationState> invitations = views.invitations(participants);
        final BigDecimal revivalFee = views.revivalFee(revivalId);
        final Map<ParticipantResponse, Integer> byResponse = new EnumMap<>(ParticipantResponse.class);
        final Map<String, int[]> byPosition = new LinkedHashMap<>();
        final boolean anyPosition = participants.stream().anyMatch(participant -> participant.getPositionTaskId() != null);
        final Map<String, SpexareDirectory.Position> positions = anyPosition ? directory.positionsOrEmpty() : Map.of();

        for (final ParticipantResponse response : ParticipantResponse.values()) {
            byResponse.put(response, 0);
        }
        for (final Participant participant : participants) {
            byResponse.merge(participant.getResponse(), 1, Integer::sum);

            final int[] counts = byPosition.computeIfAbsent(Objects.requireNonNullElse(participant.getPositionTaskId(), ""), _ -> new int[3]);

            switch (participant.getResponse()) {
                case YES -> counts[0]++;
                case MAYBE -> counts[1]++;
                case PENDING -> counts[2]++;
                case NO -> {
                }
            }
        }

        final List<PositionCount> positionCounts = byPosition.entrySet().stream()
                .map(entry -> new PositionCount(entry.getKey().isEmpty() ? null : ParticipantViews.position(positions, entry.getKey()),
                        entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
                .sorted(Comparator.comparing((PositionCount count) -> count.position() == null ? 1 : 0)
                        .thenComparing(count -> count.position() == null ? "" : count.position().name().toLowerCase(Locale.ROOT)))
                .toList();
        final List<Participant> owing = participants.stream().filter(participant -> participant.getKind() == ParticipantKind.PARTICIPANT).toList();

        return new Counts(
                participants.size(),
                byResponse.entrySet().stream().map(entry -> new ResponseCount(entry.getKey(), entry.getValue())).toList(),
                positionCounts,
                (int) participants.stream().filter(participant -> invitations.get(participant.getId()) == InvitationState.NOT_INVITED).count(),
                (int) participants.stream().filter(participant -> invitations.get(participant.getId()) == InvitationState.INVITED).count(),
                (int) owing.stream().filter(Participant::isFeePaid).count(),
                (int) owing.stream().filter(participant -> !participant.isFeePaid() && participant.getResponse() == ParticipantResponse.YES)
                        .filter(participant -> {
                            final BigDecimal fee = ParticipantViews.fee(participant, revivalFee);

                            return fee != null && fee.signum() > 0;
                        }).count(),
                (int) participants.stream().filter(participant -> participant.getKind() == ParticipantKind.HELPER).count());
    }

    // --- Referrals --------------------------------------------------------------------------------------------------

    /**
     * Open referrals, oldest first, for readers of participants; empty for others. The referrer's name is left out while
     * Spexregister cannot be read.
     */
    @Transactional(readOnly = true)
    public List<ReferralDto> referrals(final Long revivalId, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = access.visible(caller, revivalId);

        if (!accessPolicy.canReadParticipants(caller, revival)) {
            return List.of();
        }

        final List<Referral> referrals = referralRepository.findByRevivalIdOrderByCreatedAtAscIdAsc(revivalId);

        if (referrals.isEmpty()) {
            return List.of();
        }

        final List<Participant> referrers = repository.findAllById(referrals.stream().map(Referral::getReferredByParticipantId).collect(Collectors.toSet()));
        final Map<Long, ParticipantDto> names = views.toDtos(referrers,
                        new ParticipantViews.Options(false, false, false, ActingPerson.of(caller), locale)).stream()
                .collect(Collectors.toMap(ParticipantDto::id, dto -> dto));

        return referrals.stream().map(referral -> {
            final ParticipantDto referrer = names.get(referral.getReferredByParticipantId());

            return new ReferralDto(referral.getId(), referral.getName(), referral.getContact(), referral.getComment(),
                    referrer == null ? null : (referrer.firstName() + " " + referrer.lastName()).strip(), referral.getCreatedAt());
        }).toList();
    }

    /**
     * Handles a referral: ADDED makes a pending participant of it, named by splitting at the last space, with the
     * contact as email when it looks like one and otherwise as phone, for the steering group to complete; DISMISSED
     * only deletes it. Either way its text is deleted.
     */
    @Transactional
    public @Nullable ParticipantDto handleReferral(final Long id, final ReferralOutcome outcome, final Locale locale) {
        final Caller caller = callerService.current();
        final Referral referral = referralRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(Referral.class, id));
        final Revival revival;

        try {
            revival = access.writable(caller, referral.getRevivalId());
        } catch (final ResourceNotFoundException _) {
            throw new ResourceNotFoundException(Referral.class, id);
        }
        AuditContext.operation("referralHandle");

        ParticipantDto added = null;

        if (outcome == ReferralOutcome.ADDED) {
            final String[] name = ParticipantRules.splitName(referral.getName());
            final String contact = ParticipantRules.trimmed(referral.getContact());
            final Participant participant = new Participant();
            final PendingPerson person = new PendingPerson();

            participant.setRevivalId(revival.getId());
            participant.setKind(ParticipantKind.PARTICIPANT);

            final Participant saved = save(participant);

            person.setParticipantId(saved.getId());
            person.setFirstName(truncate(name[0], PendingPerson.NAME_MAX_LENGTH));
            person.setLastName(truncate(name[1], PendingPerson.NAME_MAX_LENGTH));
            if (contact != null && ParticipantRules.looksLikeEmail(contact)) {
                person.setEmail(truncate(CallerService.normalizedEmail(contact), PendingPerson.EMAIL_MAX_LENGTH));
            } else if (contact != null) {
                person.setPhone(truncate(contact, PendingPerson.PHONE_MAX_LENGTH));
            }
            pendingRepository.saveAndFlush(person);
            added = toDto(caller, revival, saved, locale);
        }
        referralRepository.delete(referral);
        return added;
    }

    private static String truncate(final String value, final int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Whether the caller reads participants of the revival, for resolvers that leave others out.
     */
    @Transactional(readOnly = true)
    public boolean canRead(final Long revivalId) {
        final Caller caller = callerService.current();

        return revivalRepository.findById(revivalId)
                .filter(revival -> accessPolicy.canSee(caller, revival) && accessPolicy.canReadParticipants(caller, revival))
                .isPresent();
    }
}
