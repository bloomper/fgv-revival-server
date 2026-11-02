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

import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.checklist.ChecklistItemSummaryDto;
import nu.fgv.revival.server.checklist.ChecklistTexts;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.event.EventService;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.meeting.Meeting;
import nu.fgv.revival.server.meeting.MeetingRecorder;
import nu.fgv.revival.server.meeting.MeetingSummaryDto;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The decision log over GraphQL.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class LogEntryGraphqlApi {

    private final ExternalIds ids;

    private final LogEntryService service;
    private final MeetingRecorder meetingRecorder;
    private final ChecklistTexts checklistTexts;
    private final EventService eventService;

    public LogEntryGraphqlApi(final LogEntryService service, final MeetingRecorder meetingRecorder, final ChecklistTexts checklistTexts,
                              final EventService eventService,
                              final ExternalIds ids) {
        this.ids = ids;
        this.service = service;
        this.meetingRecorder = meetingRecorder;
        this.checklistTexts = checklistTexts;
        this.eventService = eventService;
    }

    private static <V> Map<LogEntryDto, V> mapped(final List<LogEntryDto> entries, final java.util.function.Function<LogEntryDto, @Nullable V> value) {
        final Map<LogEntryDto, V> result = new HashMap<>();

        entries.forEach(entry -> {
            final V found = value.apply(entry);

            if (found != null) {
                result.put(entry, found);
            }
        });
        return result;
    }

    @QueryMapping
    public List<LogEntryDto> logEntries(@Argument final UUID revivalId, @Argument final @Nullable LogEntryFilterDto filter, final Locale locale) {
        return service.list(ids.idOf(Revival.class, revivalId), filter, locale);
    }

    @QueryMapping
    public LogEntryDto logEntry(@Argument final UUID id, final Locale locale) {
        return service.get(ids.idOf(LogEntry.class, id), locale);
    }

    @QueryMapping
    public List<String> logTags(@Argument final UUID revivalId) {
        return service.tags(ids.idOf(Revival.class, revivalId));
    }

    @MutationMapping
    public LogEntryDto logEntryCreate(@Argument final LogEntryCreateInput input, final Locale locale) {
        return service.create(new LogEntryService.Draft(ids.idOf(Revival.class, input.revivalId()), ids.idOfNullable(Meeting.class, input.meetingId()), input.date(), input.type(), input.text(),
                input.status(), input.owner(), input.deadline(), input.comment(), input.tags(), input.guideItemKey(),
                ids.idOfNullable(ChecklistItem.class, input.checklistItemId()), ids.idOfNullable(Event.class, input.eventId())), locale);
    }

    @MutationMapping
    public LogEntryDto logEntryUpdate(@Argument final LogEntryUpdateInput input, final Locale locale) {
        return service.update(new LogEntryService.Change(ids.idOf(LogEntry.class, input.id()), input.version(), ids.idOfNullable(Meeting.class, input.meetingId()), input.date(), input.type(),
                input.text(), input.status(), input.owner(), Boolean.TRUE.equals(input.clearOwner()), input.deadline(),
                Boolean.TRUE.equals(input.clearDeadline()), input.comment(), Boolean.TRUE.equals(input.clearComment()), input.tags(),
                input.guideItemKey(), ids.idOfNullable(ChecklistItem.class, input.checklistItemId()), ids.idOfNullable(Event.class, input.eventId()), Boolean.TRUE.equals(input.clearEvent()),
                Boolean.TRUE.equals(input.clearLinks())), locale);
    }

    @MutationMapping
    public LogEntryService.Answered logEntryAnswer(@Argument final LogEntryAnswerInput input, final Locale locale) {
        return service.answer(new LogEntryService.Answer(ids.idOf(LogEntry.class, input.questionId()), input.questionVersion(), ids.idOfNullable(Meeting.class, input.meetingId()), input.text(),
                input.owner(), input.deadline(), input.comment()), locale);
    }

    @BatchMapping(typeName = "LogEntry", field = "meeting")
    public Map<LogEntryDto, MeetingSummaryDto> meeting(final List<LogEntryDto> entries) {
        final Map<UUID, MeetingSummaryDto> meetings = meetingRecorder.summaries(ids.idsOf(Meeting.class, entries.stream()
                        .map(LogEntryDto::meetingId).filter(Objects::nonNull).distinct().toList()))
                .values().stream().collect(Collectors.toMap(MeetingSummaryDto::id, summary -> summary));

        return mapped(entries, entry -> entry.meetingId() == null ? null : meetings.get(entry.meetingId()));
    }

    @BatchMapping(typeName = "LogEntry", field = "checklistItem")
    public Map<LogEntryDto, ChecklistItemSummaryDto> checklistItem(final List<LogEntryDto> entries) {
        final Map<UUID, ChecklistItemSummaryDto> items = checklistTexts.summariesByIds(ids.idsOf(ChecklistItem.class, entries.stream()
                        .map(LogEntryDto::checklistItemId).filter(Objects::nonNull).distinct().toList()))
                .values().stream().collect(Collectors.toMap(ChecklistItemSummaryDto::id, summary -> summary));

        return mapped(entries, entry -> entry.checklistItemId() == null ? null : items.get(entry.checklistItemId()));
    }

    @BatchMapping(typeName = "LogEntry", field = "event")
    public Map<LogEntryDto, EventDto> event(final List<LogEntryDto> entries) {
        final Map<UUID, EventDto> events = new HashMap<>();

        entries.stream().filter(entry -> entry.eventId() != null).map(LogEntryDto::revivalId).distinct()
                .forEach(revivalId -> eventService.list(ids.idOf(Revival.class, revivalId), true).forEach(event -> events.put(event.id(), event)));
        return mapped(entries, entry -> entry.eventId() == null ? null : events.get(entry.eventId()));
    }

    @BatchMapping(typeName = "LogEntry", field = "guideItem")
    public Map<LogEntryDto, GuideVersionView.Item> guideItem(final List<LogEntryDto> entries) {
        final Map<Long, Optional<GuideVersionView>> versions = new HashMap<>();

        return mapped(entries, entry -> entry.guideItemKey() == null ? null : versions
                .computeIfAbsent(ids.idOf(Revival.class, entry.revivalId()), checklistTexts::pinnedVersion)
                .flatMap(version -> version.item(Objects.requireNonNull(entry.guideItemKey())))
                .orElse(null));
    }

    @BatchMapping(typeName = "LogEntry", field = "answers")
    public Map<LogEntryDto, LogEntrySummaryDto> answers(final List<LogEntryDto> entries) {
        final Map<UUID, LogEntry> questions = service.byIds(ids.idsOf(LogEntry.class, entries.stream()
                        .map(LogEntryDto::answersEntryId).filter(Objects::nonNull).distinct().toList()))
                .values().stream()
                .collect(Collectors.toMap(LogEntry::getUuid, entry -> entry));

        return mapped(entries, entry -> entry.answersEntryId() == null || !questions.containsKey(entry.answersEntryId())
                ? null : LogEntrySummaryDto.of(questions.get(entry.answersEntryId())));
    }

    @BatchMapping(typeName = "LogEntry", field = "answeredBy")
    public Map<LogEntryDto, LogEntrySummaryDto> answeredBy(final List<LogEntryDto> entries) {
        final List<UUID> questions = entries.stream().filter(entry -> entry.type() == LogEntryType.QUESTION).map(LogEntryDto::id).toList();
        final List<Long> questionIds = ids.idsOf(LogEntry.class, questions);
        final Map<Long, LogEntry> answers = service.answersTo(questionIds);
        final Map<UUID, LogEntry> decisions = new HashMap<>();

        for (int i = 0; i < questions.size(); i++) {
            final LogEntry answer = answers.get(questionIds.get(i));

            if (answer != null) {
                decisions.put(questions.get(i), answer);
            }
        }

        return mapped(entries, entry -> decisions.containsKey(entry.id()) ? LogEntrySummaryDto.of(decisions.get(entry.id())) : null);
    }

    public record LogEntryCreateInput(UUID revivalId, @Nullable UUID meetingId, @Nullable LocalDate date,
                                      LogEntryType type, String text,
                                      @Nullable LogEntryStatus status, @Nullable OwnerInputDto owner,
                                      @Nullable LocalDate deadline,
                                      @Nullable String comment, @Nullable List<String> tags,
                                      @Nullable String guideItemKey,
                                      @Nullable UUID checklistItemId, @Nullable UUID eventId) {
    }

    public record LogEntryUpdateInput(UUID id, Long version, @Nullable UUID meetingId, @Nullable LocalDate date,
                                      @Nullable LogEntryType type,
                                      @Nullable String text, @Nullable LogEntryStatus status,
                                      @Nullable OwnerInputDto owner,
                                      @Nullable Boolean clearOwner, @Nullable LocalDate deadline,
                                      @Nullable Boolean clearDeadline,
                                      @Nullable String comment, @Nullable Boolean clearComment,
                                      @Nullable List<String> tags,
                                      @Nullable String guideItemKey, @Nullable UUID checklistItemId,
                                      @Nullable UUID eventId, @Nullable Boolean clearEvent,
                                      @Nullable Boolean clearLinks) {
    }

    public record LogEntryAnswerInput(UUID questionId, Long questionVersion, @Nullable UUID meetingId, String text,
                                      @Nullable OwnerInputDto owner, @Nullable LocalDate deadline,
                                      @Nullable String comment) {
    }
}
