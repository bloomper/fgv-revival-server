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
import nu.fgv.revival.server.checklist.Checklist;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.checklist.ChecklistItemRepository;
import nu.fgv.revival.server.checklist.ChecklistItemState;
import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.ChecklistRepository;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItem;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItemRepository;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryOrigin;
import nu.fgv.revival.server.decisionlog.LogEntryRepository;
import nu.fgv.revival.server.decisionlog.LogEntryStatus;
import nu.fgv.revival.server.decisionlog.LogEntryType;
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
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Brings the sample revivals to life once the guide sync has given them their checklists and guide questions at
 * startup: progress in the checklists in line with each revival's status, event checklists, decision log entries,
 * meetings with minutes, and guide suggestions. Goes through the repositories, so the rows are audited as usual.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Slf4j
@Component
public class SampleActivityImporter {

    private static final Map<RevivalStatus, Integer> PHASES_DONE = new EnumMap<>(Map.of(
            RevivalStatus.APPROVED, 0,
            RevivalStatus.ORGANISING, 3,
            RevivalStatus.OPEN, 6,
            RevivalStatus.CLOSING, 8,
            RevivalStatus.SETTLING, 9
    ));
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
    private final ZoneId zone;

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
                                  final GuideSuggestionRepository suggestionRepository) {
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
    }

    private record SampleRevival(long id, RevivalStatus status, @Nullable LocalDate premiereDate, @Nullable Long guideVersionId,
                                 long directeur, List<SteeringGroupMember> steeringGroup) {

        List<Long> people() {
            final List<Long> people = new ArrayList<>(List.of(directeur));

            steeringGroup.forEach(member -> people.add(member.personId()));
            return people;
        }
    }

    private record SteeringGroupMember(long personId, Set<String> capabilities) {
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

        for (final SampleRevival revival : revivals) {
            transactions.executeWithoutResult(status -> {
                AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, SampleDataImporter.OPERATION, null));
                importActivity(revival);
            });
        }
        transactions.executeWithoutResult(status -> {
            AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, SampleDataImporter.OPERATION, null));
            createSuggestions(revivals);
        });
        log.info("Imported the sample activity of {} revivals in {} ms", revivals.size(), Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private record RevivalRow(long id, RevivalStatus status, @Nullable LocalDate premiereDate, @Nullable Long guideVersionId) {
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
        final Set<String> capabilities = new HashSet<>();

        jdbcClient
                .sql("SELECT capability FROM revival_member_capability WHERE member_id = ?")
                .param(memberId)
                .query(String.class)
                .list()
                .forEach(capabilities::add);
        return capabilities;
    }

    private void importActivity(final SampleRevival revival) {
        final int phasesDone = PHASES_DONE.get(revival.status());

        progressPhaseChecklists(revival, phasesDone);
        if (revival.status() != RevivalStatus.APPROVED && revival.premiereDate() != null) {
            createEventChecklist(revival, SampleChecklistTemplates.REHEARSAL_WEEKEND + " 1", revival.premiereDate().minusWeeks(6));
            createEventChecklist(revival, SampleChecklistTemplates.SHOW_WEEKEND, revival.premiereDate());
        }

        final List<LogEntry> questions = logEntryRepository.findByRevivalIdAndOriginOrderByNumberAsc(revival.id(), LogEntryOrigin.GUIDE);

        if (revival.status() == RevivalStatus.APPROVED) {
            createLogEntries(revival, null, questions, 1);
            createMeeting(revival, today().plusDays(7), MeetingStatus.PLANNED, List.of("Lokaler", "Budget"));
        } else {
            final Meeting meeting = createMeeting(revival, today().minusDays(21), MeetingStatus.CLOSED, List.of("Lokaler", "Budget", "Övriga frågor"));

            createLogEntries(revival, meeting, questions, phasesDone);
            closeMeeting(meeting);
            createMeeting(revival, today().plusDays(14), MeetingStatus.PLANNED, List.of("Biljettsläpp", "Rephelgerna"));
        }
    }

    // --- Checklists ---------------------------------------------------------------------------------------------------

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

    private void createEventChecklist(final SampleRevival revival, final String name, final LocalDate date) {
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

    private static Owner defaultOwner(final @Nullable String capability, final List<SteeringGroupMember> steeringGroup) {
        if (capability == null) {
            return Owner.none();
        }

        final List<SteeringGroupMember> holders = steeringGroup.stream().filter(member -> member.capabilities().contains(capability)).toList();

        return holders.size() == 1 ? Owner.member(holders.getFirst().personId()) : Owner.none();
    }

    // --- Decision log and meetings ------------------------------------------------------------------------------------

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

    private static @Nullable String guideItemName(final @Nullable String key) {
        return key == null ? null : ANSWERS.keySet().stream().filter(name -> SampleGuide.key(name).equals(key)).findFirst().orElse(null);
    }

    private LogEntry entry(final SampleRevival revival,
                           final LocalDate date,
                           final LogEntryType type,
                           final String text,
                           final LogEntryStatus status,
                           final @Nullable Long owner,
                           final @Nullable LocalDate deadline,
                           final Set<String> tags) {
        final LogEntry entry = new LogEntry();

        entry.setRevivalId(revival.id());
        entry.setNumber(nextNumber(revival.id()));
        entry.setDate(date);
        entry.setType(type);
        entry.setText(text);
        entry.setStatus(status);
        entry.setOwner(owner == null ? Owner.none() : Owner.member(owner));
        entry.setDeadline(deadline);
        entry.setOrigin(LogEntryOrigin.MANUAL);
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

    // --- Guide suggestions --------------------------------------------------------------------------------------------

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

    private LocalDate today() {
        return LocalDate.now(zone);
    }
}
