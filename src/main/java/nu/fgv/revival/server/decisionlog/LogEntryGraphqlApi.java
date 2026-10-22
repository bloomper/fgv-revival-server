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

import nu.fgv.revival.server.checklist.ChecklistItemSummaryDto;
import nu.fgv.revival.server.checklist.ChecklistTexts;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.meeting.MeetingRecorder;
import nu.fgv.revival.server.meeting.MeetingSummaryDto;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.RevivalPermissionsDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The decision log over GraphQL (contracts/schema.graphqls). A thin adapter: every rule is in {@link LogEntryService}.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Controller
public class LogEntryGraphqlApi {

    private final LogEntryService service;
    private final LogImportService importService;
    private final MeetingRecorder meetingRecorder;
    private final ChecklistTexts checklistTexts;

    public LogEntryGraphqlApi(final LogEntryService service, final LogImportService importService, final MeetingRecorder meetingRecorder,
                              final ChecklistTexts checklistTexts) {
        this.service = service;
        this.importService = importService;
        this.meetingRecorder = meetingRecorder;
        this.checklistTexts = checklistTexts;
    }

    public record LogEntryCreateInput(Long revivalId, @Nullable Long meetingId, @Nullable LocalDate date, LogEntryType type, String text,
                                      @Nullable LogEntryStatus status, @Nullable OwnerInputDto owner, @Nullable LocalDate deadline,
                                      @Nullable String comment, @Nullable List<String> tags, @Nullable String guideItemKey,
                                      @Nullable Long checklistItemId) {
    }

    public record LogEntryUpdateInput(Long id, Long version, @Nullable Long meetingId, @Nullable LocalDate date, @Nullable LogEntryType type,
                                      @Nullable String text, @Nullable LogEntryStatus status, @Nullable OwnerInputDto owner,
                                      @Nullable Boolean clearOwner, @Nullable LocalDate deadline, @Nullable Boolean clearDeadline,
                                      @Nullable String comment, @Nullable Boolean clearComment, @Nullable List<String> tags,
                                      @Nullable String guideItemKey, @Nullable Long checklistItemId, @Nullable Boolean clearLinks) {
    }

    public record LogEntryAnswerInput(Long questionId, Long questionVersion, @Nullable Long meetingId, String text,
                                      @Nullable OwnerInputDto owner, @Nullable LocalDate deadline, @Nullable String comment) {
    }

    @QueryMapping
    public List<LogEntryDto> logEntries(@Argument final Long revivalId, @Argument final @Nullable LogEntryFilterDto filter, final Locale locale) {
        return service.list(revivalId, filter, locale);
    }

    @QueryMapping
    public LogEntryDto logEntry(@Argument final Long id, final Locale locale) {
        return service.get(id, locale);
    }

    @QueryMapping
    public List<String> logTags(@Argument final Long revivalId) {
        return service.tags(revivalId);
    }

    @QueryMapping
    public @Nullable LogImportDto logImport(@Argument final Long revivalId, final Locale locale) {
        return importService.find(revivalId, locale).orElse(null);
    }

    @SchemaMapping(typeName = "RevivalPermissions", field = "canImportLog")
    public boolean canImportLog(final RevivalPermissionsDto permissions) {
        return permissions.revivalId() != null && importService.canImport(permissions.revivalId());
    }

    @MutationMapping
    public LogEntryDto logEntryCreate(@Argument final LogEntryCreateInput input, final Locale locale) {
        return service.create(new LogEntryService.Draft(input.revivalId(), input.meetingId(), input.date(), input.type(), input.text(),
                input.status(), input.owner(), input.deadline(), input.comment(), input.tags(), input.guideItemKey(),
                input.checklistItemId()), locale);
    }

    @MutationMapping
    public LogEntryDto logEntryUpdate(@Argument final LogEntryUpdateInput input, final Locale locale) {
        return service.update(new LogEntryService.Change(input.id(), input.version(), input.meetingId(), input.date(), input.type(),
                input.text(), input.status(), input.owner(), Boolean.TRUE.equals(input.clearOwner()), input.deadline(),
                Boolean.TRUE.equals(input.clearDeadline()), input.comment(), Boolean.TRUE.equals(input.clearComment()), input.tags(),
                input.guideItemKey(), input.checklistItemId(), Boolean.TRUE.equals(input.clearLinks())), locale);
    }

    @MutationMapping
    public LogEntryService.Answered logEntryAnswer(@Argument final LogEntryAnswerInput input, final Locale locale) {
        return service.answer(new LogEntryService.Answer(input.questionId(), input.questionVersion(), input.meetingId(), input.text(),
                input.owner(), input.deadline(), input.comment()), locale);
    }

    @BatchMapping(typeName = "LogEntry", field = "meeting")
    public Map<LogEntryDto, MeetingSummaryDto> meeting(final List<LogEntryDto> entries) {
        final Map<Long, MeetingSummaryDto> meetings = meetingRecorder.summaries(entries.stream()
                .map(LogEntryDto::meetingId).filter(Objects::nonNull).collect(Collectors.toSet()));

        return mapped(entries, entry -> entry.meetingId() == null ? null : meetings.get(entry.meetingId()));
    }

    @BatchMapping(typeName = "LogEntry", field = "checklistItem")
    public Map<LogEntryDto, ChecklistItemSummaryDto> checklistItem(final List<LogEntryDto> entries) {
        final Map<Long, ChecklistItemSummaryDto> items = checklistTexts.summariesByIds(entries.stream()
                .map(LogEntryDto::checklistItemId).filter(Objects::nonNull).collect(Collectors.toSet()));

        return mapped(entries, entry -> entry.checklistItemId() == null ? null : items.get(entry.checklistItemId()));
    }

    @BatchMapping(typeName = "LogEntry", field = "guideItem")
    public Map<LogEntryDto, GuideVersionView.Item> guideItem(final List<LogEntryDto> entries) {
        final Map<Long, Optional<GuideVersionView>> versions = new HashMap<>();

        return mapped(entries, entry -> entry.guideItemKey() == null ? null : versions
                .computeIfAbsent(entry.revivalId(), checklistTexts::pinnedVersion)
                .flatMap(version -> version.item(Objects.requireNonNull(entry.guideItemKey())))
                .orElse(null));
    }

    @BatchMapping(typeName = "LogEntry", field = "answers")
    public Map<LogEntryDto, LogEntrySummaryDto> answers(final List<LogEntryDto> entries) {
        final Map<Long, LogEntry> questions = service.byIds(entries.stream()
                .map(LogEntryDto::answersEntryId).filter(Objects::nonNull).collect(Collectors.toSet()));

        return mapped(entries, entry -> entry.answersEntryId() == null || !questions.containsKey(entry.answersEntryId())
                ? null : LogEntrySummaryDto.of(questions.get(entry.answersEntryId())));
    }

    @BatchMapping(typeName = "LogEntry", field = "answeredBy")
    public Map<LogEntryDto, LogEntrySummaryDto> answeredBy(final List<LogEntryDto> entries) {
        final Map<Long, LogEntry> decisions = service.answersTo(entries.stream()
                .filter(entry -> entry.type() == LogEntryType.QUESTION).map(LogEntryDto::id).toList());

        return mapped(entries, entry -> decisions.containsKey(entry.id()) ? LogEntrySummaryDto.of(decisions.get(entry.id())) : null);
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
}
