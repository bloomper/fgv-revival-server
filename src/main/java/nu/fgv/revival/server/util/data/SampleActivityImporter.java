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

package nu.fgv.revival.server.util.data;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.audit.AuditSource;
import nu.fgv.revival.server.calendar.CalendarFeed;
import nu.fgv.revival.server.calendar.CalendarFeedRepository;
import nu.fgv.revival.server.checklist.Checklist;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.checklist.ChecklistItemRepository;
import nu.fgv.revival.server.checklist.ChecklistItemState;
import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.ChecklistRepository;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItem;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItemRepository;
import nu.fgv.revival.server.closing.SyncOperation;
import nu.fgv.revival.server.closing.SyncRun;
import nu.fgv.revival.server.closing.SyncRunRepository;
import nu.fgv.revival.server.closing.SyncRunStatus;
import nu.fgv.revival.server.closing.SyncStep;
import nu.fgv.revival.server.closing.SyncStepRepository;
import nu.fgv.revival.server.closing.SyncStepStatus;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryCounters;
import nu.fgv.revival.server.decisionlog.LogEntryOrigin;
import nu.fgv.revival.server.decisionlog.LogEntryRepository;
import nu.fgv.revival.server.decisionlog.LogEntryStatus;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventProposals;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.event.EventType;
import nu.fgv.revival.server.event.EventTypeDto;
import nu.fgv.revival.server.event.EventTypeRepository;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestion;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionKind;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionRepository;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionStatus;
import nu.fgv.revival.server.meeting.Meeting;
import nu.fgv.revival.server.meeting.MeetingEntryChange;
import nu.fgv.revival.server.meeting.MeetingEntryChangeKind;
import nu.fgv.revival.server.meeting.MeetingEntryChangeRepository;
import nu.fgv.revival.server.meeting.MeetingRepository;
import nu.fgv.revival.server.meeting.MeetingStatus;
import nu.fgv.revival.server.meeting.Minutes;
import nu.fgv.revival.server.meeting.MinutesEmail;
import nu.fgv.revival.server.meeting.MinutesEmailRecipient;
import nu.fgv.revival.server.meeting.MinutesEmailRecipientStatus;
import nu.fgv.revival.server.meeting.MinutesEmailRepository;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalAnonymization;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.spexcalendar.SpexCalendarDate;
import nu.fgv.revival.server.spexcalendar.SpexCalendarDateRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Brings the sample revivals to life once the guide sync has given them their checklists and guide questions at
 * startup: progress in the checklists in line with each revival's status, events with their checklists (one cancelled),
 * decision log entries (some imported from a workbook), meetings with minutes sent by email, guide suggestions,
 * budgets, materials and inventory, calendar links, and Chalmersspexet's dates, one of them clashing with an open
 * revival's premiere. Revivals that are or have been open get participants, availability, shifts and mailings; those
 * that have settled the surplus decision, the FGV membership and a sync to Spexregister, failed while settling and done
 * once archived. Last, the revival archived {@value SampleDataImporter#ANONYMIZED_YEARS_AGO} years ago is anonymized,
 * as the nightly job would. Goes through the repositories, so the rows are audited as usual; {@link SampleHistory} then
 * spreads that audit trail over each revival's time.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SampleActivityImporter {

    private static final Map<RevivalStatus, Integer> PHASES_DONE = new EnumMap<>(Map.of(
            RevivalStatus.APPROVED, 0,
            RevivalStatus.ORGANISING, 3,
            RevivalStatus.OPEN, 6,
            RevivalStatus.CLOSING, 8,
            RevivalStatus.SETTLING, 9,
            RevivalStatus.ARCHIVED, 10
    ));
    private static final Set<RevivalStatus> PARTICIPATING = EnumSet.of(RevivalStatus.OPEN, RevivalStatus.CLOSING, RevivalStatus.SETTLING,
            RevivalStatus.ARCHIVED);
    private static final Set<RevivalStatus> SETTLED = EnumSet.of(RevivalStatus.SETTLING, RevivalStatus.ARCHIVED);
    private static final Map<String, String> ANSWERS = Map.of(
            "b-party-alcohol", "Lokalen har sprittillstånd, så drycken köps via restaurangen.",
            "b-timeline-current", "Årets spex repar i februari, så våra rephelger ligger i mars.",
            "c-fee", "Deltagaravgiften blir 1 200 kr. Vi erbjuder inget resebidrag.",
            "d-bank-invoices", "Fakturorna går direkt till oss, vi får inte kårpris den här gången.",
            "e-storage", "Vi får låna V-förrådet, nycklarna finns hos FGV:s ordförande.",
            "g-tickets", "Biljetterna säljs via Nortic och släpps tre månader före premiären."
    );

    private final boolean importSampleData;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactions;
    private final ChecklistRepository checklistRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final ChecklistTemplateItemRepository templateItemRepository;
    private final LogEntryRepository logEntryRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingEntryChangeRepository meetingEntryChangeRepository;
    private final GuideSuggestionRepository suggestionRepository;
    private final EventRepository eventRepository;
    private final EventTypeRepository eventTypeRepository;
    private final SampleBudget sampleBudget;
    private final SampleStaffing sampleStaffing;
    private final SampleMaterials sampleMaterials;
    private final SpexregisterClient spexregisterClient;
    private final RevivalRepository revivalRepository;
    private final RevivalAnonymization anonymization;
    private final SpexCalendarDateRepository spexCalendarRepository;
    private final MinutesEmailRepository minutesEmailRepository;
    private final CalendarFeedRepository calendarFeedRepository;
    private final SyncRunRepository syncRunRepository;
    private final SyncStepRepository syncStepRepository;
    private final LogEntryCounters logEntryCounters;
    private final SampleHistory sampleHistory;
    private final ZoneId zone;
    private final SecureRandom rnd = new SecureRandom();

    public SampleActivityImporter(@Value("${fgv-revival.data.import-sample-data:false}") final boolean importSampleData,
                                  @Value("${fgv-revival.time-zone}") final ZoneId zone,
                                  final JdbcClient jdbcClient,
                                  final TransactionTemplate transactions,
                                  final ChecklistRepository checklistRepository,
                                  final ChecklistItemRepository checklistItemRepository,
                                  final ChecklistTemplateItemRepository templateItemRepository,
                                  final LogEntryRepository logEntryRepository,
                                  final MeetingRepository meetingRepository,
                                  final MeetingEntryChangeRepository meetingEntryChangeRepository,
                                  final GuideSuggestionRepository suggestionRepository,
                                  final EventRepository eventRepository,
                                  final EventTypeRepository eventTypeRepository,
                                  final SampleBudget sampleBudget,
                                  final SampleStaffing sampleStaffing,
                                  final SampleMaterials sampleMaterials,
                                  final SpexregisterClient spexregisterClient,
                                  final RevivalRepository revivalRepository,
                                  final RevivalAnonymization anonymization,
                                  final SpexCalendarDateRepository spexCalendarRepository,
                                  final MinutesEmailRepository minutesEmailRepository,
                                  final CalendarFeedRepository calendarFeedRepository,
                                  final SyncRunRepository syncRunRepository,
                                  final SyncStepRepository syncStepRepository,
                                  final LogEntryCounters logEntryCounters,
                                  final SampleHistory sampleHistory) {
        this.importSampleData = importSampleData;
        this.zone = zone;
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
        this.checklistRepository = checklistRepository;
        this.checklistItemRepository = checklistItemRepository;
        this.templateItemRepository = templateItemRepository;
        this.logEntryRepository = logEntryRepository;
        this.meetingRepository = meetingRepository;
        this.meetingEntryChangeRepository = meetingEntryChangeRepository;
        this.suggestionRepository = suggestionRepository;
        this.eventRepository = eventRepository;
        this.eventTypeRepository = eventTypeRepository;
        this.sampleBudget = sampleBudget;
        this.sampleStaffing = sampleStaffing;
        this.sampleMaterials = sampleMaterials;
        this.spexregisterClient = spexregisterClient;
        this.revivalRepository = revivalRepository;
        this.anonymization = anonymization;
        this.spexCalendarRepository = spexCalendarRepository;
        this.minutesEmailRepository = minutesEmailRepository;
        this.calendarFeedRepository = calendarFeedRepository;
        this.syncRunRepository = syncRunRepository;
        this.syncStepRepository = syncStepRepository;
        this.logEntryCounters = logEntryCounters;
        this.sampleHistory = sampleHistory;
    }

    /**
     * The steering group member with the finance area, or the directeur.
     */
    private static long payer(final SampleRevival revival) {
        return revival.steeringGroup().stream().filter(member -> member.capabilities().contains("FINANCE")).map(SteeringGroupMember::personId)
                .findFirst().orElse(revival.directeur());
    }

    private static Owner defaultOwner(final @Nullable String capability, final List<SteeringGroupMember> steeringGroup) {
        if (capability == null) {
            return Owner.none();
        }

        final List<SteeringGroupMember> holders = steeringGroup.stream().filter(member -> member.capabilities().contains(capability)).toList();

        return holders.size() == 1 ? Owner.member(holders.getFirst().personId()) : Owner.none();
    }

    private static @Nullable String guideItemName(final @Nullable String key) {
        return key == null ? null : ANSWERS.keySet().stream().filter(name -> SampleGuide.key(name).equals(key)).findFirst().orElse(null);
    }

    private static GuideSuggestion suggestion(final SampleRevival revival,
                                              final long versionId,
                                              final GuideSuggestionKind kind,
                                              final @Nullable String item,
                                              final String chapter,
                                              final @Nullable String type,
                                              final @Nullable String text,
                                              final @Nullable String reason) {
        final GuideSuggestion suggestion = new GuideSuggestion();

        suggestion.setRevivalId(revival.id());
        suggestion.setAuthorId(revival.directeur());
        suggestion.setVersionId(versionId);
        suggestion.setKind(kind);
        suggestion.setItemKey(item == null ? null : SampleGuide.key(item));
        suggestion.setChapterKey(SampleGuide.key(chapter));
        suggestion.setProposedType(type);
        suggestion.setProposedTextSv(text);
        suggestion.setReason(reason);
        return suggestion;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() {
        if (importSampleData) {
            importSampleActivity();
        }
    }

    public void importSampleActivity() {
        final long started = System.nanoTime();
        final List<SampleRevival> revivals = revivals();
        final SamplePositions positions = SamplePositions.read(spexregisterClient);

        transactions.executeWithoutResult(status -> {
            AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, SampleDataImporter.OPERATION, null));
            linkEventTemplates();
            sampleBudget.insertCatalog();
            sampleStaffing.insertTemplates();
            createSpexCalendar(revivals);
        });
        for (final SampleRevival revival : revivals) {
            transactions.executeWithoutResult(status -> {
                AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, SampleDataImporter.OPERATION, null));
                importActivity(revival, positions);
            });
        }
        transactions.executeWithoutResult(status -> {
            AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, SampleDataImporter.OPERATION, null));
            createSuggestions(revivals);
        });
        anonymization.anonymizeExpired();
        transactions.executeWithoutResult(status -> sampleHistory.seed());
        log.info("Imported the sample activity of {} revivals in {} ms", revivals.size(), Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private List<SampleRevival> revivals() {
        return jdbcClient
                .sql("SELECT id, status, premiere_date, guide_version_id FROM revival ORDER BY id")
                .query((resultSet, row) -> new RevivalRow(
                        resultSet.getLong("id"),
                        RevivalStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("premiere_date", LocalDate.class),
                        resultSet.getObject("guide_version_id", Long.class)))
                .list()
                .stream()
                .filter(row -> PHASES_DONE.containsKey(row.status()))
                .map(this::withMembers)
                .filter(Objects::nonNull)
                .toList();
    }

    private @Nullable SampleRevival withMembers(final RevivalRow revival) {
        final long id = revival.id();
        final List<Long> directeurs = jdbcClient
                .sql("SELECT person_id FROM revival_member WHERE revival_id = ? AND role = 'DIRECTEUR' AND removed_at IS NULL AND person_id IS NOT NULL")
                .param(id)
                .query(Long.class)
                .list();

        if (directeurs.isEmpty()) {
            return null;
        }

        final List<SteeringGroupMember> steeringGroup = jdbcClient
                .sql("""
                        SELECT m.id, m.person_id FROM revival_member m
                        WHERE m.revival_id = ? AND m.role = 'STEERING_GROUP' AND m.removed_at IS NULL AND m.person_id IS NOT NULL
                        ORDER BY m.id
                        """)
                .param(id)
                .query((resultSet, row) -> new SteeringGroupMember(resultSet.getLong("person_id"), capabilities(resultSet.getLong("id"))))
                .list();

        return new SampleRevival(id, revival.status(), revival.premiereDate(), revival.guideVersionId(), directeurs.getFirst(), steeringGroup);
    }

    private Set<String> capabilities(final long memberId) {
        return new HashSet<>(jdbcClient
                .sql("SELECT capability FROM revival_member_capability WHERE member_id = ?")
                .param(memberId)
                .query(String.class)
                .list());
    }

    private void importActivity(final SampleRevival revival, final SamplePositions positions) {
        final int phasesDone = PHASES_DONE.get(revival.status());

        // The guide sync leaves archived revivals alone, so it has not numbered their log
        logEntryCounters.ensure(revival.id());
        progressPhaseChecklists(revival, phasesDone);
        if (revival.status() != RevivalStatus.APPROVED && revival.premiereDate() != null) {
            final SampleEvents events = createEvents(revival, revival.premiereDate());

            if (PARTICIPATING.contains(revival.status())) {
                sampleStaffing.insert(revival.id(), revival.status(), events.rehearsal(), events.showWeekend(), revival.directeur(), payer(revival),
                        positions, today());
            }
        }

        final List<LogEntry> questions = logEntryRepository.findByRevivalIdAndOriginOrderByNumberAsc(revival.id(), LogEntryOrigin.GUIDE);

        if (revival.status() == RevivalStatus.APPROVED) {
            createLogEntries(revival, null, questions, 1);
            createMeeting(revival, today().plusDays(7), MeetingStatus.PLANNED, List.of("Lokaler", "Budget"));
        } else {
            final Meeting meeting = createMeeting(revival, today().minusDays(21), MeetingStatus.CLOSED, List.of("Lokaler", "Budget", "Övriga frågor"));

            createLogEntries(revival, meeting, questions, phasesDone);
            closeMeeting(meeting);
            emailMinutes(meeting);
            importLog(revival);
            if (revival.status() != RevivalStatus.ARCHIVED) {
                createMeeting(revival, today().plusDays(14), MeetingStatus.PLANNED, List.of("Biljettsläpp", "Rephelgerna"));
            }
        }
        if (revival.status() != RevivalStatus.APPROVED && revival.premiereDate() != null) {
            // An archived revival's money moved around its premiere
            sampleBudget.insert(revival.id(), revival.status(), revival.premiereDate(), payer(revival), revival.directeur(),
                    revival.status() == RevivalStatus.ARCHIVED ? revival.premiereDate().plusDays(30) : today());
        }
        if (revival.status() != RevivalStatus.APPROVED) {
            sampleMaterials.insert(revival.id(), revival.directeur(), positions);
            createCalendarFeeds(revival);
        }
        if (SETTLED.contains(revival.status()) && revival.premiereDate() != null) {
            settle(revival, revival.premiereDate());
        }
    }

    private void progressPhaseChecklists(final SampleRevival revival, final int phasesDone) {
        for (final Checklist checklist : checklistRepository.findByRevivalIdAndKind(revival.id(), ChecklistKind.CHAPTER)) {
            final int phase = SampleGuide.chapterIndex(Objects.requireNonNull(checklist.getChapterKey()));
            final List<ChecklistItem> items = checklistItemRepository.findByChecklistIdOrderByPositionAscIdAsc(checklist.getId());

            if (phase > phasesDone) {
                continue;
            }
            for (int index = 0; index < items.size(); index++) {
                final ChecklistItem item = items.get(index);

                if (phase < phasesDone) {
                    if (!item.isRequired() && index == items.size() - 1 && index > 0) {
                        setState(item, ChecklistItemState.NOT_APPLICABLE, "Behövs inte den här gången", revival.directeur(), 60);
                    } else {
                        setState(item, ChecklistItemState.DONE, null, revival.directeur(), 60 - phase * 5);
                    }
                } else if (index % 2 == 0) {
                    setState(item, ChecklistItemState.DONE, null, revival.directeur(), 5);
                } else {
                    if (!item.getOwner().isSet()) {
                        item.setOwner(Owner.member(revival.people().get(index % revival.people().size())));
                    }
                    item.setDeadline(today().plusDays(index == 1 ? -3 : 7L * index));
                    item.setDeadlineManual(true);
                    item.setComment(index == 1 ? "Påmint på senaste mötet" : null);
                    checklistItemRepository.save(item);
                }
            }
        }
    }

    private void setState(final ChecklistItem item, final ChecklistItemState state, final @Nullable String reason, final long by, final int daysAgo) {
        item.setState(state);
        item.setReason(reason);
        item.setStateChangedBy(by);
        item.setStateChangedAt(Instant.now().minus(Duration.ofDays(daysAgo)));
        checklistItemRepository.save(item);
    }

    /**
     * The sample event templates on the types they are for, so that a new rehearsal or show weekend gets its checklist.
     */
    private void linkEventTemplates() {
        for (final EventType type : eventTypeRepository.findAllByOrderByPositionAscIdAsc()) {
            final String template = type.getNameSv().startsWith(SampleChecklistTemplates.REHEARSAL_WEEKEND) ? SampleChecklistTemplates.REHEARSAL_WEEKEND
                    : type.getNameSv().equals(SampleChecklistTemplates.SHOW_WEEKEND) ? SampleChecklistTemplates.SHOW_WEEKEND : null;

            if (template != null && type.getChecklistTemplateId() == null) {
                type.setChecklistTemplateId(jdbcClient
                        .sql("SELECT id FROM checklist_template WHERE kind = 'EVENT' AND name_sv = ? AND retired_at IS NULL")
                        .param(template)
                        .query(Long.class)
                        .single());
                eventTypeRepository.save(type);
            }
        }
    }

    /**
     * The first rehearsal weekend on the date proposed for it and the show weekend, each with its checklist, a free
     * event without one and a cancelled one.
     */
    private SampleEvents createEvents(final SampleRevival revival, final LocalDate premiere) {
        final List<EventTypeDto> types = eventTypeRepository.findAllByOrderByPositionAscIdAsc().stream().map(EventTypeDto::of).toList();
        final EventTypeDto rehearsal = types.stream().filter(type -> type.name().sv().equals(SampleChecklistTemplates.REHEARSAL_WEEKEND + " 1"))
                .findFirst().orElseThrow();
        final EventTypeDto show = types.stream().filter(type -> type.name().sv().equals(SampleChecklistTemplates.SHOW_WEEKEND)).findFirst().orElseThrow();
        final LocalDate rehearsalStart = EventProposals.propose(premiere, List.of(rehearsal), List.of()).getFirst().startDate();
        final Event rehearsalEvent = createEvent(revival, rehearsal, rehearsal.name().sv(), rehearsalStart, null);
        final Event showEvent = createEvent(revival, show, show.name().sv(), premiere, "Kårhuset");

        createEventChecklist(revival, rehearsalEvent.getTitle(), rehearsalEvent.getStartDate(), rehearsalEvent.getId());
        createEventChecklist(revival, showEvent.getTitle(), showEvent.getStartDate(), showEvent.getId());
        createEvent(revival, null, "Förrådsbesök", premiere.minusDays(30), "V-förrådet");

        final Event visit = createEvent(revival, null, "Studiebesök hos Chalmersspexet", premiere.minusDays(60), "RunAn");

        visit.setCancelledAt(Instant.now().minus(Duration.ofDays(3)));
        eventRepository.saveAndFlush(visit);
        return new SampleEvents(rehearsalEvent, showEvent);
    }

    private Event createEvent(final SampleRevival revival, final @Nullable EventTypeDto type, final String title, final LocalDate start,
                              final @Nullable String location) {
        final Event event = new Event();

        event.setRevivalId(revival.id());
        event.setTypeId(type == null ? null : type.id());
        event.setTitle(title);
        event.setStartDate(start);
        event.setEndDate(start.plusDays(type == null ? 0 : type.days() - 1L));
        event.setLocation(location);
        event.setDescription(type == null || type.description() == null ? null : type.description().sv());
        event.setRevivalPays(type != null && type.revivalPays());
        return eventRepository.saveAndFlush(event);
    }

    private void createEventChecklist(final SampleRevival revival, final String name, final LocalDate date, final Long eventId) {
        final long templateId = jdbcClient
                .sql("SELECT id FROM checklist_template WHERE kind = 'EVENT' AND name_sv = ? AND retired_at IS NULL")
                .param(date.equals(revival.premiereDate()) ? SampleChecklistTemplates.SHOW_WEEKEND : SampleChecklistTemplates.REHEARSAL_WEEKEND)
                .query(Long.class)
                .single();
        final Checklist checklist = new Checklist();

        checklist.setRevivalId(revival.id());
        checklist.setKind(ChecklistKind.EVENT);
        checklist.setName(name);
        checklist.setDate(date);
        checklist.setTemplateId(templateId);
        checklist.setEventId(eventId);

        final Checklist saved = checklistRepository.saveAndFlush(checklist);
        final List<ChecklistTemplateItem> templateItems = templateItemRepository.findByTemplateIdAndRetiredAtIsNullOrderByPositionAscIdAsc(templateId);

        for (int position = 0; position < templateItems.size(); position++) {
            final ChecklistTemplateItem templateItem = templateItems.get(position);
            final ChecklistItem item = new ChecklistItem();

            item.setChecklistId(saved.getId());
            item.setRevivalId(revival.id());
            item.setPosition(position);
            item.setText(templateItem.getTextSv());
            item.setGuideItemKey(templateItem.getGuideItemKey());
            item.setTemplateItemId(templateItem.getId());
            item.setDefaultCapability(templateItem.getDefaultCapability());
            item.setRelativeDeadline(templateItem.getDeadline() == null ? null : templateItem.getDeadline().copy());
            item.setDeadline(item.getRelativeDeadline() == null ? null : item.getRelativeDeadline().apply(date));
            item.setOwner(defaultOwner(templateItem.getDefaultCapability(), revival.steeringGroup()));
            if (item.getDeadline() != null && item.getDeadline().isBefore(today())) {
                item.setState(ChecklistItemState.DONE);
                item.setStateChangedBy(revival.directeur());
                item.setStateChangedAt(item.getDeadline().atStartOfDay(zone).toInstant());
            }
            checklistItemRepository.save(item);
        }
    }

    private void createLogEntries(final SampleRevival revival, final @Nullable Meeting meeting, final List<LogEntry> questions, final int phasesDone) {
        final long somebody = revival.people().getLast();
        final LocalDate earlier = today().minusDays(40);
        final LocalDate meetingDate = meeting == null ? earlier : meeting.getDate();

        final LogEntry venue = entry(revival, earlier, LogEntryType.DECISION, "Föreställningarna spelas i kårhuset.", LogEntryStatus.DONE, null, null, Set.of("lokal"));
        final LogEntry quote = entry(revival, earlier, LogEntryType.TODO, "Ta in offert på ljud och ljus.", LogEntryStatus.DONE, somebody, earlier.plusDays(14), Set.of("lokal", "ekonomi"));
        final LogEntry matinee = entry(revival, earlier, LogEntryType.QUESTION, "Ska vi ha en matinéföreställning på söndagen?", LogEntryStatus.WAITING, revival.directeur(), null, Set.of());

        entry(revival, earlier, LogEntryType.TODO, "Undersök om vi kan låna kostymer från årets spex.", LogEntryStatus.CANCELLED, null, null, Set.of("kostym"));
        if (meeting == null) {
            return;
        }

        final LogEntry budget = entry(revival, meetingDate, LogEntryType.DECISION, "Budgeten fastställs enligt casseurens förslag.", LogEntryStatus.DONE, null, null, Set.of("ekonomi"));
        final LogEntry poster = entry(revival, meetingDate, LogEntryType.TODO, "Ta fram ett förslag på affisch.", LogEntryStatus.OPEN, somebody, today().plusDays(10), Set.of("marknadsföring"));

        budget.setMeetingId(meeting.getId());
        poster.setMeetingId(meeting.getId());
        logEntryRepository.save(budget);
        logEntryRepository.save(poster);
        record(meeting, budget, MeetingEntryChangeKind.CREATED);
        record(meeting, poster, MeetingEntryChangeKind.CREATED);
        record(meeting, quote, MeetingEntryChangeKind.CLOSED);

        questions.stream()
                .filter(question -> guideItemName(question.getGuideItemKey()) != null)
                .limit(Math.max(1, phasesDone))
                .forEach(question -> {
                    final LogEntry answer = entry(revival, meetingDate, LogEntryType.DECISION, ANSWERS.get(guideItemName(question.getGuideItemKey())),
                            LogEntryStatus.DONE, null, null, Set.of());

                    answer.setAnswersEntryId(question.getId());
                    answer.setMeetingId(meeting.getId());
                    question.setStatus(LogEntryStatus.DONE);
                    logEntryRepository.save(answer);
                    logEntryRepository.save(question);
                    record(meeting, question, MeetingEntryChangeKind.ANSWERED);
                    record(meeting, question, MeetingEntryChangeKind.CLOSED);
                    record(meeting, answer, MeetingEntryChangeKind.CREATED);
                });
        venue.setComment("Beslutat efter att ha jämfört med konserthuset.");
        matinee.setComment("Väntar på besked från lokalen.");
        logEntryRepository.save(venue);
        logEntryRepository.save(matinee);
    }

    private LogEntry entry(final SampleRevival revival,
                           final LocalDate date,
                           final LogEntryType type,
                           final String text,
                           final LogEntryStatus status,
                           final @Nullable Long owner,
                           final @Nullable LocalDate deadline,
                           final Set<String> tags) {
        return entry(revival, date, type, text, status, owner, deadline, tags, LogEntryOrigin.MANUAL);
    }

    private LogEntry entry(final SampleRevival revival,
                           final LocalDate date,
                           final LogEntryType type,
                           final String text,
                           final LogEntryStatus status,
                           final @Nullable Long owner,
                           final @Nullable LocalDate deadline,
                           final Set<String> tags,
                           final LogEntryOrigin origin) {
        final LogEntry entry = new LogEntry();

        entry.setRevivalId(revival.id());
        entry.setNumber(nextNumber(revival.id()));
        entry.setDate(date);
        entry.setType(type);
        entry.setText(text);
        entry.setStatus(status);
        entry.setOwner(owner == null ? Owner.none() : Owner.member(owner));
        entry.setDeadline(deadline);
        entry.setOrigin(origin);
        entry.setTags(new LinkedHashSet<>(tags));
        return logEntryRepository.saveAndFlush(entry);
    }

    private int nextNumber(final long revivalId) {
        jdbcClient.sql("UPDATE log_entry_counter SET last_number = last_number + 1 WHERE revival_id = ?").param(revivalId).update();
        return jdbcClient.sql("SELECT last_number FROM log_entry_counter WHERE revival_id = ?").param(revivalId).query(Integer.class).single();
    }

    private Meeting createMeeting(final SampleRevival revival, final LocalDate date, final MeetingStatus status, final List<String> agenda) {
        final Meeting meeting = new Meeting();

        meeting.setRevivalId(revival.id());
        meeting.setDate(date);
        meeting.setTitle(status == MeetingStatus.PLANNED ? "Direktionsmöte" : "Direktionsmöte om lokaler och budget");
        meeting.setStatus(status);
        meeting.setAgendaPoints(new ArrayList<>(agenda));
        if (status == MeetingStatus.CLOSED) {
            meeting.setOthersPresent("JK:s kassör");
            meeting.setRecorderPersonId(revival.directeur());
            meeting.setAttendeeIds(new LinkedHashSet<>(revival.people()));
            meeting.setStartedAt(date.atTime(18, 0).atZone(zone).toInstant());
            meeting.setClosedAt(date.atTime(20, 30).atZone(zone).toInstant());
            meeting.setClosedBy(revival.directeur());
        }
        return meetingRepository.saveAndFlush(meeting);
    }

    private void record(final Meeting meeting, final LogEntry entry, final MeetingEntryChangeKind kind) {
        final MeetingEntryChange change = new MeetingEntryChange();

        change.setMeetingId(meeting.getId());
        change.setEntryId(entry.getId());
        change.setKind(kind);
        change.setChangedBy(Objects.requireNonNull(meeting.getRecorderPersonId()));
        change.setChangedAt(Objects.requireNonNull(meeting.getStartedAt()).plus(Duration.ofMinutes(30)));
        meetingEntryChangeRepository.save(change);
    }

    /**
     * The minutes as {@code MinutesBuilder} makes them when a meeting closes.
     */
    private void closeMeeting(final Meeting meeting) {
        final List<MeetingEntryChange> changes = meetingEntryChangeRepository.findByMeetingIdOrderByIdAsc(meeting.getId());
        final List<LogEntry> entries = logEntryRepository.findAllById(changes.stream().map(MeetingEntryChange::getEntryId).distinct().toList()).stream()
                .sorted(Comparator.comparingInt(LogEntry::getNumber))
                .toList();
        final List<Minutes.Row> decisions = new ArrayList<>();
        final List<Minutes.Row> todos = new ArrayList<>();
        final List<Minutes.Row> questions = new ArrayList<>();
        final List<Minutes.Row> closed = new ArrayList<>();

        for (final LogEntry entry : entries) {
            final Set<MeetingEntryChangeKind> kinds = EnumSet.noneOf(MeetingEntryChangeKind.class);

            changes.stream().filter(change -> change.getEntryId().equals(entry.getId())).forEach(change -> kinds.add(change.getKind()));

            final Minutes.Row row = new Minutes.Row(entry.getId(), entry.getNumber(), entry.getType(), entry.getText(),
                    entry.getOwner().getPersonId(), entry.getOwner().getName(), entry.getDeadline(), entry.getStatus());

            if (kinds.contains(MeetingEntryChangeKind.CREATED)) {
                switch (entry.getType()) {
                    case DECISION -> decisions.add(row);
                    case TODO -> todos.add(row);
                    case QUESTION -> questions.add(row);
                }
            } else if (entry.getType() == LogEntryType.TODO && kinds.contains(MeetingEntryChangeKind.ASSIGNED)) {
                todos.add(row);
            }
            if (kinds.contains(MeetingEntryChangeKind.CLOSED)) {
                closed.add(row);
            }
        }
        meeting.setMinutes(new Minutes(decisions, todos, questions, closed, meeting.getAgendaPoints(), List.copyOf(meeting.getAttendeeIds()),
                meeting.getOthersPresent(), meeting.getRecorderPersonId()));
        meetingRepository.save(meeting);
    }

    /**
     * The minutes sent to the attendees in Swedish, one address bouncing.
     */
    private void emailMinutes(final Meeting meeting) {
        final MinutesEmail email = new MinutesEmail();
        final List<Long> attendees = List.copyOf(meeting.getAttendeeIds());

        email.setMeetingId(meeting.getId());
        email.setLanguage("sv");
        email.setSentBy(Objects.requireNonNull(meeting.getClosedBy()));
        email.setSentAt(Objects.requireNonNull(meeting.getClosedAt()).plus(Duration.ofMinutes(15)));
        for (int index = 0; index < attendees.size(); index++) {
            email.getRecipients().add(new MinutesEmailRecipient(attendees.get(index),
                    index == attendees.size() - 1 && index > 0 ? MinutesEmailRecipientStatus.FAILED : MinutesEmailRecipientStatus.SENT));
        }
        minutesEmailRepository.save(email);
    }

    /**
     * Two rows from the steering group's old log, imported while the Excel import still existed.
     */
    private void importLog(final SampleRevival revival) {
        final LocalDate date = today().minusDays(60);

        entry(revival, date, LogEntryType.DECISION, "Vi söker FGV:s lån för uppsättningen.", LogEntryStatus.DONE, null, null, Set.of("ekonomi"),
                LogEntryOrigin.IMPORT);
        entry(revival, date, LogEntryType.TODO, "Fråga originalförfattarna om tillstånd.", LogEntryStatus.DONE, null, null, Set.of(),
                LogEntryOrigin.IMPORT);
    }

    /**
     * The directeur's calendar link, and one they revoked before it. The hashes are random, so no link opens them.
     */
    private void createCalendarFeeds(final SampleRevival revival) {
        for (final boolean revoked : List.of(true, false)) {
            final CalendarFeed feed = new CalendarFeed();
            final byte[] token = new byte[32];

            rnd.nextBytes(token);
            feed.setRevivalId(revival.id());
            feed.setPersonId(revival.directeur());
            feed.setTokenHash(HexFormat.of().formatHex(token));
            feed.setCreatedAt(Instant.now().minus(Duration.ofDays(revoked ? 40 : 20)));
            feed.setRevokedAt(revoked ? Instant.now().minus(Duration.ofDays(20)) : null);
            calendarFeedRepository.saveAndFlush(feed);
        }
    }

    /**
     * The surplus decision, the FGV membership for the year of the premiere, and a sync to Spexregister: failed while
     * the revival settles, so it can be resumed for real, and done once it is archived. Every sample participant is a
     * pending person, so only the revival itself is written.
     */
    private void settle(final SampleRevival revival, final LocalDate premiere) {
        final Revival current = revivalRepository.findById(revival.id()).orElseThrow();
        final boolean archived = revival.status() == RevivalStatus.ARCHIVED;
        final Instant started = premiere.plusDays(40).atTime(20, 0).atZone(zone).toInstant();
        final SyncRun run = new SyncRun();
        final SyncStep step = new SyncStep();
        final byte[] hash = new byte[32];

        entry(revival, premiere.plusDays(35), LogEntryType.DECISION, "Överskottet går till FGV:s fond för kommande uppsättningar.", LogEntryStatus.DONE,
                null, null, Set.of("överskott"));
        current.setMembershipYear(premiere.getYear());
        revivalRepository.save(current);

        rnd.nextBytes(hash);
        run.setRevivalId(revival.id());
        run.setStartedBy(revival.directeur());
        run.setStartedAt(started);
        run.setFinishedAt(started.plus(Duration.ofSeconds(archived ? 12 : 31)));
        run.setStatus(archived ? SyncRunStatus.DONE : SyncRunStatus.FAILED);
        run.setPreviewHash(HexFormat.of().formatHex(hash));

        final SyncRun saved = syncRunRepository.saveAndFlush(run);

        step.setRunId(saved.getId());
        step.setOperation(SyncOperation.REVIVAL);
        step.setStatus(archived ? SyncStepStatus.EXISTED : SyncStepStatus.FAILED);
        step.setErrorCode(archived ? null : SpexregisterClient.SpexregisterCallException.UNAVAILABLE);
        step.setUpdatedAt(Objects.requireNonNull(saved.getFinishedAt()));
        syncStepRepository.saveAndFlush(step);
    }

    /**
     * Chalmersspexet's shows in February this year and next, and a guest performance in the week of the first open
     * revival's premiere, which the timeline flags. Unless the dates are there already.
     */
    private void createSpexCalendar(final List<SampleRevival> revivals) {
        if (!spexCalendarRepository.findAllByOrderByDateAscIdAsc().isEmpty()) {
            return;
        }
        for (final int year : List.of(today().getYear(), today().getYear() + 1)) {
            final LocalDate premiere = LocalDate.of(year, 2, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.THURSDAY));

            spexCalendarDate(premiere, "Chalmersspexet: premiär");
            spexCalendarDate(premiere.plusDays(1), "Chalmersspexet");
            spexCalendarDate(premiere.plusDays(2), "Chalmersspexet");
            spexCalendarDate(premiere.plusDays(9), "Chalmersspexet: sista föreställningen");
        }
        revivals.stream()
                .filter(revival -> revival.status() == RevivalStatus.OPEN && revival.premiereDate() != null)
                .findFirst()
                .ifPresent(revival -> spexCalendarDate(Objects.requireNonNull(revival.premiereDate()).plusDays(1), "Chalmersspexet: gästspel"));
    }

    private void spexCalendarDate(final LocalDate date, final String label) {
        final SpexCalendarDate spexCalendarDate = new SpexCalendarDate();

        spexCalendarDate.setDate(date);
        spexCalendarDate.setLabel(label);
        spexCalendarRepository.saveAndFlush(spexCalendarDate);
    }

    /**
     * An accepted suggestion behind the tip v2 added, one rejected and two open ones.
     */
    private void createSuggestions(final List<SampleRevival> revivals) {
        final List<Long> versions = jdbcClient.sql("SELECT id FROM guide_version WHERE status = 'PUBLISHED' ORDER BY published_at").query(Long.class).list();
        final long admin = jdbcClient.sql("SELECT id FROM person WHERE email = ?").param(SampleDataImporter.ADMIN).query(Long.class).single();

        if (versions.size() < 2 || revivals.isEmpty()) {
            return;
        }

        final Instant secondPublished = jdbcClient.sql("SELECT published_at FROM guide_version WHERE id = ?").param(versions.getLast())
                .query((resultSet, row) -> resultSet.getTimestamp(1).toInstant()).single();
        final SampleRevival first = revivals.getFirst();
        final SampleRevival last = revivals.getLast();

        final GuideSuggestion accepted = suggestion(first, versions.getFirst(), GuideSuggestionKind.ADD, null, SampleGuide.CHAPTER_F, SampleGuide.TIP,
                "Filma genrepet, det är ovärderligt för de som missade en rephelg.", "Flera i ensemblen missade sista rephelgen.");

        accepted.setStatus(GuideSuggestionStatus.ACCEPTED);
        accepted.setDecidedBy(admin);
        accepted.setDecidedAt(secondPublished.minus(Duration.ofDays(3)));
        accepted.setAcceptedIntoVersionId(versions.getLast());

        final GuideSuggestion rejected = suggestion(last, versions.getLast(), GuideSuggestionKind.REMOVE, "i-optional", SampleGuide.CHAPTER_I, null, null,
                "Ingen gör turné längre.");

        rejected.setStatus(GuideSuggestionStatus.REJECTED);
        rejected.setDecidedBy(admin);
        rejected.setDecidedAt(Instant.now().minus(Duration.ofDays(5)));
        rejected.setDecisionReason("Turnéer förekommer fortfarande, och kapitlet säger redan att de är frivilliga.");

        final GuideSuggestion change = suggestion(first, versions.getLast(), GuideSuggestionKind.CHANGE, "c-buffer", SampleGuide.CHAPTER_C, SampleGuide.TIP,
                "Sätt av minst tio procent av budgeten som buffert.", "Ett konkret belopp är lättare att följa.");
        final GuideSuggestion add = suggestion(last, versions.getLast(), GuideSuggestionKind.ADD, null, SampleGuide.CHAPTER_G, SampleGuide.TIP,
                "Lägg upp evenemanget i sociala medier minst två månader före premiären.", null);

        suggestionRepository.saveAll(List.of(accepted, rejected, change, add));
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private record SampleRevival(long id, RevivalStatus status, @Nullable LocalDate premiereDate,
                                 @Nullable Long guideVersionId,
                                 long directeur, List<SteeringGroupMember> steeringGroup) {

        List<Long> people() {
            final List<Long> people = new ArrayList<>(List.of(directeur));

            steeringGroup.forEach(member -> people.add(member.personId()));
            return people;
        }
    }

    private record SteeringGroupMember(long personId, Set<String> capabilities) {
    }

    private record RevivalRow(long id, RevivalStatus status, @Nullable LocalDate premiereDate,
                              @Nullable Long guideVersionId) {
    }

    private record SampleEvents(Event rehearsal, Event showWeekend) {
    }
}
