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

package nu.fgv.revival.server.mailing;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.mailing.MailingDtos.MailingAudience;
import nu.fgv.revival.server.mailing.MailingDtos.MailingDto;
import nu.fgv.revival.server.mailing.MailingDtos.MailingPlanEntry;
import nu.fgv.revival.server.mailing.MailingDtos.MailingRecipientDto;
import nu.fgv.revival.server.mailing.MailingDtos.MailingRecipientPreview;
import nu.fgv.revival.server.mailing.MailingDtos.MailingTemplateDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantDirectory;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.MailUnavailableException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A revival's mailings: the steering group drafts, a directeur or a member
 * with {@code COMMS} sends, each recipient getting their own message, and sends again to those it failed for. The
 * audience is resolved when the mailing is sent, and addresses are read then and never stored. Also the revival's
 * mailing plan from the super admin's templates. Nothing is sent without a person confirming.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
@Slf4j
public class MailingService {

    /**
     * How many days ahead a plan entry counts as due.
     */
    static final int DUE_WITHIN_DAYS = 7;

    private final MailingRepository repository;
    private final MailingRecipientRepository recipientRepository;
    private final MailingTemplateRepository templateRepository;
    private final MailingAudiences audiences;
    private final MailingMailer mailer;
    private final EventRepository eventRepository;
    private final ParticipantRepository participantRepository;
    private final ParticipantDirectory participantDirectory;
    private final RevivalRepository revivalRepository;
    private final RevivalTitles titles;
    private final SpexareDirectory spexareDirectory;
    private final PersonNames personNames;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public MailingService(final MailingRepository repository,
                          final MailingRecipientRepository recipientRepository,
                          final MailingTemplateRepository templateRepository,
                          final MailingAudiences audiences,
                          final MailingMailer mailer,
                          final EventRepository eventRepository,
                          final ParticipantRepository participantRepository,
                          final ParticipantDirectory participantDirectory,
                          final RevivalRepository revivalRepository,
                          final RevivalTitles titles,
                          final SpexareDirectory spexareDirectory,
                          final PersonNames personNames,
                          final CallerService callerService,
                          final AccessPolicy accessPolicy,
                          final TransactionTemplate transactions,
                          final Clock clock) {
        this.repository = repository;
        this.recipientRepository = recipientRepository;
        this.templateRepository = templateRepository;
        this.audiences = audiences;
        this.mailer = mailer;
        this.eventRepository = eventRepository;
        this.participantRepository = participantRepository;
        this.participantDirectory = participantDirectory;
        this.revivalRepository = revivalRepository;
        this.titles = titles;
        this.spexareDirectory = spexareDirectory;
        this.personNames = personNames;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Refuses with MAIL_UNAVAILABLE when there were addresses and none of them could be sent to.
     */
    private static void failIfNothingWentOut(final Long mailingId, final Collection<MailingRecipientStatus> results) {
        if (results.contains(MailingRecipientStatus.FAILED) && !results.contains(MailingRecipientStatus.SENT)) {
            log.warn("Mailing {} could not be sent to anyone", mailingId);
            throw new MailUnavailableException(null);
        }
        log.info("Sent mailing {}: {} sent, {} failed, {} without an address", mailingId,
                results.stream().filter(result -> result == MailingRecipientStatus.SENT).count(),
                results.stream().filter(result -> result == MailingRecipientStatus.FAILED).count(),
                results.stream().filter(result -> result == MailingRecipientStatus.NO_ADDRESS).count());
    }

    private static PlanItem item(final MailingTemplate template, final @Nullable Event event, final LocalDate dueDate,
                                 final Map<String, List<Mailing>> planned) {
        final List<Mailing> mailings = planned.getOrDefault(key(template.getId(), event == null ? null : event.getId()), List.of());

        return new PlanItem(template, event, dueDate, mailings.isEmpty() ? null : mailings.getFirst(),
                mailings.stream().anyMatch(mailing -> mailing.getStatus() == MailingStatus.SENT));
    }

    static String key(final Long templateId, final @Nullable Long eventId) {
        return templateId + "/" + (eventId == null ? "premiere" : eventId);
    }

    private static @Nullable PersonRefDto person(final @Nullable Long id, final Map<Long, String> names) {
        return id == null ? null : new PersonRefDto(id, names.get(id));
    }

    /**
     * Newest first. Readers of mailings.
     */
    @Transactional(readOnly = true)
    public List<MailingDto> list(final Long revivalId, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = readableRevival(caller, revivalId);

        return toDtos(repository.findByRevivalIdOrderByCreatedAtDescIdDesc(revivalId), revival, caller, locale);
    }

    @Transactional(readOnly = true)
    public MailingDto get(final Long id, final Locale locale) {
        final Caller caller = callerService.current();
        final Mailing mailing = readable(caller, id);

        return toDto(mailing, revivalRepository.findById(mailing.getRevivalId()).orElseThrow(), caller, locale);
    }

    /**
     * Who the audience reaches now, by name, and whether an address was found: what a send would use.
     */
    @Transactional(readOnly = true)
    public List<MailingRecipientPreview> preview(final Long revivalId, final AudienceInput input, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = readableRevival(caller, revivalId);
        final MailingAudiences.Audience audience = audience(revival, input);

        return audiences.resolve(revivalId, audience, ParticipantDirectory.actingPerson(caller), locale).stream()
                .map(recipient -> new MailingRecipientPreview(recipient.displayName(), recipient.email() != null))
                .toList();
    }

    /**
     * A new draft. From a plan entry, its template and event are kept so the plan shows it; the web fills in
     * the template's text.
     */
    @Transactional
    public MailingDto create(final Input input, final Locale locale) {
        final Caller caller = callerService.current();

        if (input.revivalId() == null) {
            throw new BadRequestException("error.mailing.revival");
        }

        final Revival revival = draftable(caller, input.revivalId());
        final Mailing mailing = new Mailing();

        AuditContext.operation("mailingCreate");
        mailing.setRevivalId(revival.getId());
        define(mailing, revival, input);
        planEntry(mailing, revival, input.templateId(), input.eventId());
        mailing.setCreatedByPerson(caller.person().getId());
        return toDto(repository.saveAndFlush(mailing), revival, caller, locale);
    }

    @Transactional
    public MailingDto update(final Long id, final Long version, final Input input, final Locale locale) {
        final Caller caller = callerService.current();
        final Mailing mailing = changeableDraft(caller, id, version);
        final Revival revival = revivalRepository.findById(mailing.getRevivalId()).orElseThrow();

        AuditContext.operation("mailingUpdate");
        define(mailing, revival, input);
        return toDto(repository.saveAndFlush(mailing), revival, caller, locale);
    }

    /**
     * Cancels a draft; it stays readable.
     */
    @Transactional
    public MailingDto cancel(final Long id, final Long version, final Locale locale) {
        final Caller caller = callerService.current();
        final Mailing mailing = changeableDraft(caller, id, version);

        AuditContext.operation("mailingCancel");
        mailing.setStatus(MailingStatus.CANCELLED);
        mailing.setCancelledAt(clock.instant());
        return toDto(repository.saveAndFlush(mailing), revivalRepository.findById(mailing.getRevivalId()).orElseThrow(), caller, locale);
    }

    /**
     * Sends a draft to its audience as it is now, one message per recipient. The messages go out outside any
     * transaction; then the mailing and each recipient's result are saved together. When every recipient with an
     * address fails, nothing is saved and the draft stays a draft.
     */
    public MailingDto send(final Long id, final Long version, final Locale locale) {
        if (!mailer.available()) {
            throw new MailUnavailableException(null);
        }

        final Prepared prepared = Objects.requireNonNull(transactions.execute(status -> {
            final Caller caller = callerService.current();
            final Mailing mailing = readable(caller, id);
            final Revival revival = revivalRepository.findById(mailing.getRevivalId()).orElseThrow();

            accessPolicy.requireSendMailings(caller, revival);
            if (mailing.getStatus() != MailingStatus.DRAFT) {
                throw new BadRequestException("error.mailing.notDraft");
            }
            if (!Objects.equals(version, mailing.getVersion())) {
                throw new ConflictException("error.conflict");
            }

            final List<MailingAudiences.Recipient> recipients = audiences.resolve(revival.getId(),
                    new MailingAudiences.Audience(mailing.getAudienceKind(), mailing.getAudienceTaskId(), mailing.getAudienceEventId()),
                    ParticipantDirectory.actingPerson(caller), locale);

            if (recipients.isEmpty()) {
                throw new BadRequestException("error.mailing.noRecipients");
            }
            return new Prepared(caller, mailing.getId(), revival.getId(), content(caller, revival, mailing, locale), recipients);
        }));
        final Map<MailingAudiences.Recipient, MailingRecipientStatus> results = new LinkedHashMap<>();

        for (final MailingAudiences.Recipient recipient : prepared.recipients()) {
            results.put(recipient, recipient.email() == null ? MailingRecipientStatus.NO_ADDRESS
                    : mailer.send(prepared.content(), recipient.email()) ? MailingRecipientStatus.SENT : MailingRecipientStatus.FAILED);
        }
        failIfNothingWentOut(prepared.mailingId(), results.values());

        return Objects.requireNonNull(transactions.execute(status -> {
            AuditContext.operation("mailingSend");

            final Mailing mailing = repository.findById(prepared.mailingId()).orElseThrow();

            mailing.setStatus(MailingStatus.SENT);
            mailing.setSentByPerson(prepared.caller().person().getId());
            mailing.setSentAt(clock.instant());
            results.forEach((recipient, result) -> {
                final MailingRecipient row = new MailingRecipient();

                row.setMailingId(mailing.getId());
                row.setParticipantId(recipient.participantId());
                row.setPersonId(recipient.personId());
                row.setStatus(result);
                row.setAttempt(1);
                recipientRepository.save(row);
            });
            recipientRepository.flush();
            return toDto(repository.saveAndFlush(mailing), revivalRepository.findById(prepared.revivalId()).orElseThrow(), prepared.caller(), locale);
        }));
    }

    /**
     * Sends a sent mailing again to the recipients it failed for, with their addresses as they are now.
     */
    public MailingDto retry(final Long id, final Locale locale) {
        if (!mailer.available()) {
            throw new MailUnavailableException(null);
        }

        final Retry retry = Objects.requireNonNull(transactions.execute(status -> {
            final Caller caller = callerService.current();
            final Mailing mailing = readable(caller, id);
            final Revival revival = revivalRepository.findById(mailing.getRevivalId()).orElseThrow();

            accessPolicy.requireSendMailings(caller, revival);
            if (mailing.getStatus() != MailingStatus.SENT) {
                throw new BadRequestException("error.mailing.notSent");
            }

            final List<MailingRecipient> failed = recipientRepository.findByMailingIdOrderByIdAsc(mailing.getId()).stream()
                    .filter(recipient -> recipient.getStatus() == MailingRecipientStatus.FAILED)
                    .toList();

            if (failed.isEmpty()) {
                throw new BadRequestException("error.mailing.nothingToRetry");
            }
            return new Retry(caller, mailing.getId(), revival.getId(), content(caller, revival, mailing, locale), failed,
                    audiences.addresses(failed.stream().map(MailingRecipient::getParticipantId).filter(Objects::nonNull).toList(),
                            failed.stream().map(MailingRecipient::getPersonId).filter(Objects::nonNull).toList(),
                            ParticipantDirectory.actingPerson(caller)));
        }));
        final Map<Long, MailingRecipientStatus> results = new LinkedHashMap<>();

        for (final MailingRecipient recipient : retry.failed()) {
            final String email = recipient.getParticipantId() != null ? retry.addresses().participants().get(recipient.getParticipantId())
                    : retry.addresses().people().get(recipient.getPersonId());

            results.put(recipient.getId(), email == null ? MailingRecipientStatus.NO_ADDRESS
                    : mailer.send(retry.content(), email) ? MailingRecipientStatus.SENT : MailingRecipientStatus.FAILED);
        }
        failIfNothingWentOut(retry.mailingId(), results.values());

        return Objects.requireNonNull(transactions.execute(status -> {
            AuditContext.operation("mailingRetry");
            recipientRepository.findByMailingIdOrderByIdAsc(retry.mailingId()).stream()
                    .filter(recipient -> results.containsKey(recipient.getId()))
                    .forEach(recipient -> {
                        recipient.setStatus(results.get(recipient.getId()));
                        recipient.setAttempt(recipient.getAttempt() + 1);
                        recipientRepository.save(recipient);
                    });
            recipientRepository.flush();
            return toDto(repository.findById(retry.mailingId()).orElseThrow(), revivalRepository.findById(retry.revivalId()).orElseThrow(),
                    retry.caller(), locale);
        }));
    }

    private MailingMailer.Content content(final Caller caller, final Revival revival, final Mailing mailing, final Locale locale) {
        final String name = caller.person().getDisplayName();

        return new MailingMailer.Content(titles.ofCached(revival, locale), name == null || name.isBlank() ? "" : name.strip(),
                caller.person().getEmail(), mailing.getSubject(), mailing.getBody(), locale);
    }

    /**
     * The revival's plan for readers of mailings, by due date; empty for others.
     */
    @Transactional(readOnly = true)
    public List<MailingPlanEntry> plan(final Long revivalId, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = revivalRepository.findById(revivalId).orElse(null);

        if (revival == null || !accessPolicy.canSee(caller, revival) || !accessPolicy.canReadMailings(caller, revival)) {
            return List.of();
        }

        final LocalDate today = LocalDate.now(clock);
        final List<PlanItem> items = planItems(revival);
        final Map<Long, MailingDto> mailings = toDtos(items.stream().map(PlanItem::mailing).filter(Objects::nonNull).toList(), revival, caller, locale)
                .stream().collect(Collectors.toMap(MailingDto::id, Function.identity()));

        return items.stream().map(item -> new MailingPlanEntry(MailingTemplateDto.of(item.template()),
                item.event() == null ? null : EventDto.of(item.event(), revival.getPremiereDate()), item.dueDate(),
                item.mailing() == null ? null : mailings.get(item.mailing().getId()), item.due(today))).toList();
    }

    /**
     * The plan entry a mailing was drafted from, as the plan shows it; null when it was not, or the entry is gone.
     */
    @Transactional(readOnly = true)
    public @Nullable MailingPlanEntry plannedFor(final MailingDto mailing, final Locale locale) {
        if (mailing.templateId() == null) {
            return null;
        }

        final Revival revival = revivalRepository.findById(mailing.revivalId()).orElseThrow();
        final PlanItem item = planItems(revival).stream()
                .filter(candidate -> candidate.template().getId().equals(mailing.templateId())
                        && Objects.equals(candidate.event() == null ? null : candidate.event().getId(), mailing.planEventId()))
                .findFirst()
                .orElse(null);

        if (item == null) {
            return null;
        }

        final Caller caller = callerService.current();
        final MailingDto latest = item.mailing() == null ? null
                : item.mailing().getId().equals(mailing.id()) ? mailing : toDto(item.mailing(), revival, caller, locale);

        return new MailingPlanEntry(MailingTemplateDto.of(item.template()),
                item.event() == null ? null : EventDto.of(item.event(), revival.getPremiereDate()), item.dueDate(), latest,
                item.due(LocalDate.now(clock)));
    }

    /**
     * The active templates × the revival's events of their types that are not cancelled, or its premiere, by due date.
     * No caller check.
     */
    List<PlanItem> planItems(final Revival revival) {
        final List<MailingTemplate> templates = templateRepository.findAllByOrderByPositionAscIdAsc().stream().filter(template -> !template.isRetired())
                .toList();

        if (templates.isEmpty()) {
            return List.of();
        }

        final List<Event> events = eventRepository.findByRevivalId(revival.getId()).stream().filter(event -> !event.isCancelled())
                .sorted(Comparator.comparing(Event::getStartDate).thenComparing(Event::getId)).toList();
        final Map<String, List<Mailing>> planned = repository.findByRevivalIdOrderByCreatedAtDescIdDesc(revival.getId()).stream()
                .filter(mailing -> mailing.getTemplateId() != null && mailing.getStatus() != MailingStatus.CANCELLED)
                .collect(Collectors.groupingBy(mailing -> key(Objects.requireNonNull(mailing.getTemplateId()), mailing.getPlanEventId())));
        final List<PlanItem> items = new ArrayList<>();

        for (final MailingTemplate template : templates) {
            if (template.getEventTypeId() != null) {
                events.stream().filter(event -> template.getEventTypeId().equals(event.getTypeId()))
                        .forEach(event -> items.add(item(template, event, template.getDue().apply(event.getStartDate()), planned)));
            } else if (revival.getPremiereDate() != null) {
                items.add(item(template, null, template.getDue().apply(revival.getPremiereDate()), planned));
            }
        }
        items.sort(Comparator.comparing(PlanItem::dueDate).thenComparing(item -> item.template().getPosition())
                .thenComparing(item -> item.event() == null ? 0L : item.event().getId()));
        return items;
    }

    private Revival readableRevival(final Caller caller, final Long revivalId) {
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireReadMailings(caller, revival);
        return revival;
    }

    private Revival draftable(final Caller caller, final Long revivalId) {
        final Revival revival = readableRevival(caller, revivalId);

        accessPolicy.requireDraftMailings(caller, revival);
        return revival;
    }

    /**
     * A mailing of a revival whose mailings the caller reads; missing otherwise.
     */
    private Mailing readable(final Caller caller, final Long id) {
        final Mailing mailing = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(Mailing.class, id));
        final Revival revival = revivalRepository.findById(mailing.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canSee(caller, revival) || !accessPolicy.canReadMailings(caller, revival)) {
            throw new ResourceNotFoundException(Mailing.class, id);
        }
        return mailing;
    }

    private Mailing changeableDraft(final Caller caller, final Long id, final Long version) {
        final Mailing mailing = readable(caller, id);

        accessPolicy.requireDraftMailings(caller, revivalRepository.findById(mailing.getRevivalId()).orElseThrow());
        if (mailing.getStatus() != MailingStatus.DRAFT) {
            throw new BadRequestException("error.mailing.notDraft");
        }
        if (!Objects.equals(version, mailing.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return mailing;
    }

    private void define(final Mailing mailing, final Revival revival, final Input input) {
        final MailingAudiences.Audience audience = audience(revival, input.audience());
        final String subject = input.subject() == null ? "" : input.subject().strip();
        final String body = input.body() == null ? "" : input.body().strip();

        if (subject.isEmpty() || subject.length() > Mailing.SUBJECT_MAX_LENGTH) {
            throw new BadRequestException("error.mailing.subject", Mailing.SUBJECT_MAX_LENGTH);
        }
        if (body.isEmpty() || body.length() > Mailing.BODY_MAX_LENGTH) {
            throw new BadRequestException("error.mailing.body", Mailing.BODY_MAX_LENGTH);
        }
        mailing.setAudienceKind(audience.kind());
        mailing.setAudienceTaskId(audience.taskId());
        mailing.setAudienceEventId(audience.eventId());
        mailing.setSubject(subject);
        mailing.setBody(body);
    }

    /**
     * The audience with only what its kind uses: a position that Spexregister knows, when it can be read, or an event
     * of the revival that is not cancelled.
     */
    private MailingAudiences.Audience audience(final Revival revival, final AudienceInput input) {
        return switch (input.kind()) {
            case POSITION -> {
                final String positionId = input.positionId() == null ? "" : input.positionId().strip();
                final Map<String, SpexareDirectory.Position> positions = spexareDirectory.positionsOrEmpty();

                if (positionId.isEmpty() || positionId.length() > 36 || !positions.isEmpty() && !positions.containsKey(positionId)) {
                    throw new BadRequestException("error.mailing.position");
                }
                yield new MailingAudiences.Audience(input.kind(), positionId, null);
            }
            case EVENT_ATTENDEES -> {
                final Long eventId = input.eventId();

                if (eventId == null || eventRepository.findById(eventId)
                        .filter(event -> event.getRevivalId().equals(revival.getId()) && !event.isCancelled()).isEmpty()) {
                    throw new BadRequestException("error.mailing.event");
                }
                yield new MailingAudiences.Audience(input.kind(), null, eventId);
            }
            default -> new MailingAudiences.Audience(input.kind(), null, null);
        };
    }

    /**
     * The plan entry a new draft is made from: an active template, with an event of the revival of the template's type
     * that is not cancelled, or no event for a template relative to the premiere.
     */
    private void planEntry(final Mailing mailing, final Revival revival, final @Nullable Long templateId, final @Nullable Long eventId) {
        if (templateId == null) {
            if (eventId != null) {
                throw new BadRequestException("error.mailing.planEntry");
            }
            return;
        }

        final MailingTemplate template = templateRepository.findById(templateId).filter(candidate -> !candidate.isRetired())
                .orElseThrow(() -> new BadRequestException("error.mailing.planEntry"));

        if (template.getEventTypeId() == null ? eventId != null
                : eventId == null || eventRepository.findById(eventId).filter(event -> event.getRevivalId().equals(revival.getId())
                && !event.isCancelled() && template.getEventTypeId().equals(event.getTypeId())).isEmpty()) {
            throw new BadRequestException("error.mailing.planEntry");
        }
        mailing.setTemplateId(templateId);
        mailing.setPlanEventId(eventId);
    }

    private MailingDto toDto(final Mailing mailing, final Revival revival, final Caller caller, final Locale locale) {
        return toDtos(List.of(mailing), revival, caller, locale).getFirst();
    }

    /**
     * Mailings of one revival with their recipients by name: participants from Spexregister or the pending person, a
     * spexare unnamed while Spexregister cannot be read; people by their display name.
     */
    private List<MailingDto> toDtos(final List<Mailing> mailings, final Revival revival, final Caller caller, final Locale locale) {
        if (mailings.isEmpty()) {
            return List.of();
        }

        final Map<Long, List<MailingRecipient>> recipients = recipientRepository.findByMailingIdIn(mailings.stream().map(Mailing::getId).toList())
                .stream().collect(Collectors.groupingBy(MailingRecipient::getMailingId));
        final List<MailingRecipient> all = recipients.values().stream().flatMap(List::stream).toList();
        final List<Participant> participants = participantRepository.findAllById(all.stream().map(MailingRecipient::getParticipantId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        final Map<Long, ParticipantDirectory.Named> participantNames = participantDirectory.named(participants,
                ParticipantDirectory.actingPerson(caller), locale);
        final Set<Long> people = new HashSet<>();

        all.stream().map(MailingRecipient::getPersonId).filter(Objects::nonNull).forEach(people::add);
        mailings.forEach(mailing -> {
            if (mailing.getCreatedByPerson() != null) {
                people.add(mailing.getCreatedByPerson());
            }
            if (mailing.getSentByPerson() != null) {
                people.add(mailing.getSentByPerson());
            }
        });

        final Map<Long, String> personNamesById = people.isEmpty() ? Map.of() : personNames.byIds(people, locale);
        final boolean needsPositions = mailings.stream().anyMatch(mailing -> mailing.getAudienceTaskId() != null);
        final Map<String, SpexareDirectory.Position> positions = needsPositions ? spexareDirectory.positionsOrEmpty() : Map.of();
        final Map<Long, Event> events = new HashMap<>();
        final boolean canSend = accessPolicy.canSendMailings(caller, revival);

        eventRepository.findAllById(mailings.stream().map(Mailing::getAudienceEventId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(event -> events.put(event.getId(), event));

        return mailings.stream().map(mailing -> {
            final Event event = mailing.getAudienceEventId() == null ? null : events.get(mailing.getAudienceEventId());
            final String taskId = mailing.getAudienceTaskId();

            return new MailingDto(
                    mailing.getId(),
                    mailing.getRevivalId(),
                    new MailingAudience(mailing.getAudienceKind(),
                            taskId == null ? null : positions.getOrDefault(taskId, new SpexareDirectory.Position(taskId, taskId, null, false)),
                            event == null ? null : EventDto.of(event, revival.getPremiereDate())),
                    mailing.getSubject(),
                    mailing.getBody(),
                    mailing.getStatus(),
                    mailing.getTemplateId(),
                    mailing.getPlanEventId(),
                    person(mailing.getCreatedByPerson(), personNamesById),
                    mailing.getCreatedAt(),
                    person(mailing.getSentByPerson(), personNamesById),
                    mailing.getSentAt(),
                    recipients.getOrDefault(mailing.getId(), List.of()).stream()
                            .map(recipient -> new MailingRecipientDto(recipient.getParticipantId() != null
                                    ? participantNames.containsKey(recipient.getParticipantId())
                                    ? participantNames.get(recipient.getParticipantId()).displayName() : ""
                                    : Objects.requireNonNullElse(personNamesById.get(recipient.getPersonId()), ""), recipient.getStatus()))
                            .sorted(Comparator.comparing(MailingRecipientDto::displayName, String.CASE_INSENSITIVE_ORDER))
                            .toList(),
                    canSend && mailing.getStatus() == MailingStatus.DRAFT,
                    mailing.getVersion() == null ? 0L : mailing.getVersion());
        }).toList();
    }

    public record AudienceInput(MailingAudienceKind kind, @Nullable String positionId, @Nullable Long eventId) {
    }

    /**
     * A draft as written. The revival, template and event are read only on create.
     */
    public record Input(@Nullable Long revivalId, AudienceInput audience, String subject, String body,
                        @Nullable Long templateId,
                        @Nullable Long eventId) {
    }

    private record Prepared(Caller caller, Long mailingId, Long revivalId, MailingMailer.Content content,
                            List<MailingAudiences.Recipient> recipients) {
    }

    private record Retry(Caller caller, Long mailingId, Long revivalId, MailingMailer.Content content,
                         List<MailingRecipient> failed,
                         MailingAudiences.Addresses addresses) {
    }

    /**
     * One entry of a revival's plan, before it is shown.
     */
    record PlanItem(MailingTemplate template, @Nullable Event event, LocalDate dueDate, @Nullable Mailing mailing,
                    boolean sent) {

        boolean due(final LocalDate today) {
            return !sent && !dueDate.isAfter(today.plusDays(DUE_WITHIN_DAYS));
        }
    }
}
