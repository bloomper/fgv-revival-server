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


package nu.fgv.revival.server.decisionlog;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.checklist.ChecklistItemRepository;
import nu.fgv.revival.server.meeting.MeetingEntryChangeKind;
import nu.fgv.revival.server.meeting.MeetingRecorder;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import nu.fgv.revival.server.util.jpa.SpecificationQueries;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A revival's decision log (FR-008–FR-015). Rows are never deleted; every change is audited and, when made from a
 * meeting in progress, recorded as made in it (FR-018).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Service
public class LogEntryService {

    private static final Sort BY_NUMBER = Sort.by("number");

    private final LogEntryRepository repository;
    private final LogEntryCounters counters;
    private final RevivalRepository revivalRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final OwnerResolver ownerResolver;
    private final OwnerViews ownerViews;
    private final PersonNames personNames;
    private final MeetingRecorder meetingRecorder;
    private final SpecificationQueries specificationQueries;
    private final Clock clock;

    public LogEntryService(final LogEntryRepository repository,
                           final LogEntryCounters counters,
                           final RevivalRepository revivalRepository,
                           final ChecklistItemRepository checklistItemRepository,
                           final CallerService callerService,
                           final AccessPolicy accessPolicy,
                           final OwnerResolver ownerResolver,
                           final OwnerViews ownerViews,
                           final PersonNames personNames,
                           final MeetingRecorder meetingRecorder,
                           final SpecificationQueries specificationQueries,
                           final Clock clock) {
        this.repository = repository;
        this.counters = counters;
        this.revivalRepository = revivalRepository;
        this.checklistItemRepository = checklistItemRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.ownerResolver = ownerResolver;
        this.ownerViews = ownerViews;
        this.personNames = personNames;
        this.meetingRecorder = meetingRecorder;
        this.specificationQueries = specificationQueries;
        this.clock = clock;
    }

    /**
     * A new row. {@code meetingId} makes it belong to that meeting in progress.
     */
    public record Draft(Long revivalId, @Nullable Long meetingId, @Nullable LocalDate date, LogEntryType type, String text,
                        @Nullable LogEntryStatus status, @Nullable OwnerInputDto owner, @Nullable LocalDate deadline,
                        @Nullable String comment, @Nullable List<String> tags, @Nullable String guideItemKey,
                        @Nullable Long checklistItemId) {
    }

    /**
     * A change to a row. Fields left null are unchanged; the {@code clear*} flags remove a value.
     */
    public record Change(Long id, Long version, @Nullable Long meetingId, @Nullable LocalDate date, @Nullable LogEntryType type,
                         @Nullable String text, @Nullable LogEntryStatus status, @Nullable OwnerInputDto owner, boolean clearOwner,
                         @Nullable LocalDate deadline, boolean clearDeadline, @Nullable String comment, boolean clearComment,
                         @Nullable List<String> tags, @Nullable String guideItemKey, @Nullable Long checklistItemId,
                         boolean clearLinks) {
    }

    /**
     * The decision answering a question (FR-013).
     */
    public record Answer(Long questionId, Long questionVersion, @Nullable Long meetingId, String text, @Nullable OwnerInputDto owner,
                         @Nullable LocalDate deadline, @Nullable String comment) {
    }

    public record Answered(LogEntryDto question, LogEntryDto decision) {
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // --- Reading ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LogEntryDto> list(final Long revivalId, final @Nullable LogEntryFilterDto filter, final Locale locale) {
        final Caller caller = callerService.current();

        visible(caller, revivalId);
        return toDtos(specificationQueries.findAll(LogEntry.class,
                LogEntrySpecifications.of(revivalId, filter == null ? LogEntryFilterDto.none() : filter, caller.person().getId(), today()),
                BY_NUMBER), locale);
    }

    @Transactional(readOnly = true)
    public LogEntryDto get(final Long id, final Locale locale) {
        final Caller caller = callerService.current();
        final LogEntry entry = readable(caller, id);

        return toDto(entry, locale);
    }

    @Transactional(readOnly = true)
    public LogEntryDto getByNumber(final Long revivalId, final int number, final Locale locale) {
        final Caller caller = callerService.current();

        visible(caller, revivalId);
        return toDto(repository.findByRevivalIdAndNumber(revivalId, number)
                .orElseThrow(() -> new ResourceNotFoundException(LogEntry.class, number)), locale);
    }

    @Transactional(readOnly = true)
    public List<String> tags(final Long revivalId) {
        visible(callerService.current(), revivalId);
        return repository.findDistinctTags(revivalId);
    }

    /**
     * Rows by id, for summaries. Callers have already checked access to the revival.
     */
    @Transactional(readOnly = true)
    public Map<Long, LogEntry> byIds(final Collection<Long> ids) {
        return repository.findAllById(ids).stream().collect(Collectors.toMap(LogEntry::getId, Function.identity()));
    }

    /**
     * The decisions answering the given questions, by question id.
     */
    @Transactional(readOnly = true)
    public Map<Long, LogEntry> answersTo(final Collection<Long> questionIds) {
        return repository.findByAnswersEntryIdIn(questionIds).stream()
                .collect(Collectors.toMap(entry -> Objects.requireNonNull(entry.getAnswersEntryId()), Function.identity()));
    }

    /**
     * To-dos, open or waiting, without an owner who still counts (FR-019). Callers have checked access.
     */
    @Transactional(readOnly = true)
    public List<LogEntry> unownedOpenTodos(final Long revivalId) {
        final Set<Long> active = ownerResolver.activeMembers(revivalId).keySet();

        return repository.findByRevivalIdOrderByNumberAsc(revivalId).stream()
                .filter(entry -> entry.getType() == LogEntryType.TODO && entry.getStatus().isOpen())
                .filter(entry -> entry.getOwner().getName() == null
                        && (entry.getOwner().getPersonId() == null || !active.contains(entry.getOwner().getPersonId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LogEntry> linkedTo(final Long checklistItemId) {
        return repository.findByChecklistItemIdOrderByNumberAsc(checklistItemId);
    }

    // --- Writing ----------------------------------------------------------------------------------------------------

    @Transactional
    public LogEntryDto create(final Draft draft, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = writable(caller, draft.revivalId());

        AuditContext.operation("logEntryCreate");
        if (draft.meetingId() != null) {
            meetingRecorder.requireInProgress(draft.meetingId(), revival.getId());
        }

        final LogEntry entry = new LogEntry();

        entry.setRevivalId(revival.getId());
        entry.setNumber(counters.next(revival.getId()));
        entry.setDate(draft.date() == null ? today() : draft.date());
        entry.setType(draft.type());
        entry.setText(validText(draft.text()));
        entry.setStatus(draft.status() == null ? LogEntryStatus.OPEN : draft.status());
        entry.setOwner(ownerResolver.validate(revival.getId(), draft.owner()));
        entry.setDeadline(draft.deadline());
        entry.setComment(validComment(draft.comment()));
        entry.setTags(validTags(draft.tags()));
        entry.setGuideItemKey(draft.guideItemKey());
        entry.setChecklistItemId(validChecklistItem(revival.getId(), draft.checklistItemId()));
        entry.setMeetingId(draft.meetingId());
        entry.setOrigin(LogEntryOrigin.MANUAL);

        final LogEntry saved = repository.saveAndFlush(entry);

        if (draft.meetingId() != null) {
            meetingRecorder.record(draft.meetingId(), saved.getId(), EnumSet.of(MeetingEntryChangeKind.CREATED), caller.person().getId());
        }
        return toDto(saved, locale);
    }

    @Transactional
    public LogEntryDto update(final Change change, final Locale locale) {
        final Caller caller = callerService.current();
        final LogEntry entry = readable(caller, change.id());
        final Revival revival = writable(caller, entry.getRevivalId());

        if (!Objects.equals(change.version(), entry.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        AuditContext.operation("logEntryUpdate");
        if (change.meetingId() != null) {
            meetingRecorder.requireInProgress(change.meetingId(), revival.getId());
        }

        final Set<MeetingEntryChangeKind> kinds = EnumSet.noneOf(MeetingEntryChangeKind.class);
        final LogEntryStatus statusBefore = entry.getStatus();
        final Owner ownerBefore = entry.getOwner();
        final String before = snapshot(entry);

        if (change.date() != null) {
            entry.setDate(change.date());
        }
        if (change.type() != null) {
            entry.setType(change.type());
        }
        if (change.text() != null) {
            entry.setText(validText(change.text()));
        }
        if (change.status() != null) {
            entry.setStatus(change.status());
        }
        if (change.clearOwner()) {
            entry.setOwner(Owner.none());
        } else if (change.owner() != null) {
            entry.setOwner(ownerResolver.change(revival.getId(), entry.getOwner(), change.owner()));
        }
        if (change.clearDeadline()) {
            entry.setDeadline(null);
        } else if (change.deadline() != null) {
            entry.setDeadline(change.deadline());
        }
        if (change.clearComment()) {
            entry.setComment(null);
        } else if (change.comment() != null) {
            entry.setComment(validComment(change.comment()));
        }
        if (change.tags() != null) {
            entry.setTags(validTags(change.tags()));
        }
        if (change.clearLinks()) {
            entry.setGuideItemKey(null);
            entry.setChecklistItemId(null);
        }
        if (change.guideItemKey() != null) {
            entry.setGuideItemKey(change.guideItemKey());
        }
        if (change.checklistItemId() != null) {
            entry.setChecklistItemId(validChecklistItem(revival.getId(), change.checklistItemId()));
        }

        if (!entry.getOwner().equals(ownerBefore) && entry.getOwner().isSet()) {
            kinds.add(MeetingEntryChangeKind.ASSIGNED);
        }
        if (entry.getStatus() != statusBefore && isClosed(entry.getStatus())) {
            kinds.add(MeetingEntryChangeKind.CLOSED);
        }
        if (!snapshot(entry).equals(before)) {
            kinds.add(MeetingEntryChangeKind.UPDATED);
        }

        final LogEntry saved = repository.saveAndFlush(entry);

        if (change.meetingId() != null && !kinds.isEmpty()) {
            meetingRecorder.record(change.meetingId(), saved.getId(), kinds, caller.person().getId());
        }
        return toDto(saved, locale);
    }

    @Transactional
    public Answered answer(final Answer answer, final Locale locale) {
        final Caller caller = callerService.current();
        final LogEntry question = readable(caller, answer.questionId());
        final Revival revival = writable(caller, question.getRevivalId());

        if (!Objects.equals(answer.questionVersion(), question.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        if (question.getType() != LogEntryType.QUESTION || !question.getStatus().isOpen()) {
            throw new BadRequestException("error.log.answer.notOpenQuestion");
        }
        if (repository.findByAnswersEntryId(question.getId()).isPresent()) {
            throw new BadRequestException("error.log.answer.answered");
        }
        AuditContext.operation("logEntryAnswer");
        if (answer.meetingId() != null) {
            meetingRecorder.requireInProgress(answer.meetingId(), revival.getId());
        }

        final LogEntry decision = new LogEntry();

        decision.setRevivalId(revival.getId());
        decision.setNumber(counters.next(revival.getId()));
        decision.setDate(today());
        decision.setType(LogEntryType.DECISION);
        decision.setText(validText(answer.text()));
        decision.setOwner(ownerResolver.validate(revival.getId(), answer.owner()));
        decision.setDeadline(answer.deadline());
        decision.setComment(validComment(answer.comment()));
        decision.setAnswersEntryId(question.getId());
        decision.setMeetingId(answer.meetingId());
        decision.setOrigin(LogEntryOrigin.MANUAL);
        question.setStatus(LogEntryStatus.DONE);

        final LogEntry savedDecision = repository.saveAndFlush(decision);
        final LogEntry savedQuestion = repository.saveAndFlush(question);

        if (answer.meetingId() != null) {
            meetingRecorder.record(answer.meetingId(), savedQuestion.getId(), EnumSet.of(MeetingEntryChangeKind.ANSWERED, MeetingEntryChangeKind.CLOSED), caller.person().getId());
            meetingRecorder.record(answer.meetingId(), savedDecision.getId(), EnumSet.of(MeetingEntryChangeKind.CREATED), caller.person().getId());
        }
        return new Answered(toDto(savedQuestion, locale), toDto(savedDecision, locale));
    }

    // --- Internal, for the guide sync (research R10) -------------------------------------------------------------------

    /**
     * Creates an open question row for each guide question not yet in the log, numbered after existing rows. Called by
     * the guide sync, which is itself access-checked through the operation that triggered it.
     */
    @Transactional
    public void createGuideQuestions(final Long revivalId, final Map<String, String> textsByKey) {
        final Set<String> existing = repository.findByRevivalIdAndOriginOrderByNumberAsc(revivalId, LogEntryOrigin.GUIDE).stream()
                .map(LogEntry::getGuideItemKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        textsByKey.forEach((key, text) -> {
            if (existing.contains(key)) {
                return;
            }

            final LogEntry entry = new LogEntry();

            entry.setRevivalId(revivalId);
            entry.setNumber(counters.next(revivalId));
            entry.setDate(today());
            entry.setType(LogEntryType.QUESTION);
            entry.setText(truncate(text, LogEntry.TEXT_MAX_LENGTH));
            entry.setGuideItemKey(key);
            entry.setOrigin(LogEntryOrigin.GUIDE);
            repository.save(entry);
        });
    }

    /**
     * Clears the link to checklist items the guide sync is about to delete. The rows stay: log rows are never deleted.
     * Internal, for the guide sync.
     */
    @Transactional
    public void unlinkChecklistItems(final Collection<Long> checklistItemIds) {
        if (checklistItemIds.isEmpty()) {
            return;
        }
        repository.findByChecklistItemIdIn(checklistItemIds).forEach(entry -> {
            entry.setChecklistItemId(null);
            repository.save(entry);
        });
    }

    /**
     * The number of log rows linked to any of the checklist items. Unchecked: for callers that have checked access.
     */
    @Transactional(readOnly = true)
    public int countLinkedToChecklistItems(final Collection<Long> checklistItemIds) {
        return checklistItemIds.isEmpty() ? 0 : (int) repository.countByChecklistItemIdIn(checklistItemIds);
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    private Revival visible(final Caller caller, final Long revivalId) {
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireReadRevivalWork(caller, revival);
        return revival;
    }

    private Revival writable(final Caller caller, final Long revivalId) {
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireWriteRevivalWork(caller, revival);
        return revival;
    }

    private LogEntry readable(final Caller caller, final Long id) {
        final LogEntry entry = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(LogEntry.class, id));
        final Revival revival = revivalRepository.findById(entry.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canReadRevivalWork(caller, revival)) {
            throw new ResourceNotFoundException(LogEntry.class, id);
        }
        return entry;
    }

    private static boolean isClosed(final LogEntryStatus status) {
        return status == LogEntryStatus.DONE || status == LogEntryStatus.CANCELLED;
    }

    private static String snapshot(final LogEntry entry) {
        return Stream.of(entry.getDate(), entry.getType(), entry.getText(), entry.getDeadline(), entry.getComment(),
                        new TreeSet<>(entry.getTags()), entry.getGuideItemKey(), entry.getChecklistItemId())
                .map(String::valueOf)
                .collect(Collectors.joining("\u0000"));
    }

    static String validText(final String text) {
        final String trimmed = text.strip();

        if (trimmed.isEmpty()) {
            throw new BadRequestException("error.log.text.blank");
        }
        if (trimmed.length() > LogEntry.TEXT_MAX_LENGTH) {
            throw new BadRequestException("error.log.text.tooLong", LogEntry.TEXT_MAX_LENGTH);
        }
        return trimmed;
    }

    static @Nullable String validComment(final @Nullable String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }

        final String trimmed = comment.strip();

        if (trimmed.length() > LogEntry.TEXT_MAX_LENGTH) {
            throw new BadRequestException("error.log.comment.tooLong", LogEntry.TEXT_MAX_LENGTH);
        }
        return trimmed;
    }

    static Set<String> validTags(final @Nullable List<String> tags) {
        final Set<String> valid = new LinkedHashSet<>();

        if (tags == null) {
            return valid;
        }
        for (final String tag : tags) {
            final String trimmed = tag.strip();

            if (trimmed.isEmpty() || trimmed.length() > LogEntry.TAG_MAX_LENGTH) {
                throw new BadRequestException("error.log.tag.invalid", LogEntry.TAG_MAX_LENGTH);
            }
            valid.add(trimmed);
        }
        return valid;
    }

    private @Nullable Long validChecklistItem(final Long revivalId, final @Nullable Long checklistItemId) {
        if (checklistItemId == null) {
            return null;
        }

        final ChecklistItem item = checklistItemRepository.findById(checklistItemId)
                .orElseThrow(() -> new ResourceNotFoundException(ChecklistItem.class, checklistItemId));

        if (!item.getRevivalId().equals(revivalId)) {
            throw new BadRequestException("error.log.checklistItem.otherRevival");
        }
        return checklistItemId;
    }

    static String truncate(final String text, final int max) {
        final String trimmed = text.strip();

        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    List<LogEntryDto> toDtos(final List<LogEntry> entries, final Locale locale) {
        final List<OwnerViews.Ref> refs = entries.stream().map(entry -> new OwnerViews.Ref(entry.getRevivalId(), entry.getOwner())).toList();
        final Map<OwnerViews.Ref, @Nullable OwnerDto> owners = ownerViews.toDtos(refs, locale);
        final Set<String> subjects = new LinkedHashSet<>();

        entries.forEach(entry -> {
            subjects.add(entry.getCreatedBy());
            if (entry.getLastModifiedBy() != null) {
                subjects.add(entry.getLastModifiedBy());
            }
        });

        final Map<String, String> names = subjects.isEmpty() ? Map.of() : personNames.bySubjects(subjects, locale);
        final LocalDate today = today();
        final List<LogEntryDto> dtos = new ArrayList<>(entries.size());

        for (final LogEntry entry : entries) {
            dtos.add(new LogEntryDto(entry.getId(), entry.getRevivalId(), entry.getNumber(), entry.getDate(), entry.getType(),
                    entry.getText(), entry.getStatus(), owners.get(new OwnerViews.Ref(entry.getRevivalId(), entry.getOwner())),
                    entry.getDeadline(), entry.isOverdue(today), entry.getComment(), entry.getTags().stream().sorted().toList(),
                    entry.getMeetingId(), entry.getGuideItemKey(), entry.getChecklistItemId(), entry.getAnswersEntryId(),
                    entry.getOrigin(), names.get(entry.getCreatedBy()), entry.getCreatedAt(),
                    entry.getLastModifiedBy() == null ? null : names.get(entry.getLastModifiedBy()), entry.getLastModifiedAt(),
                    entry.getVersion()));
        }
        return dtos;
    }

    LogEntryDto toDto(final LogEntry entry, final Locale locale) {
        return toDtos(List.of(entry), locale).getFirst();
    }
}
