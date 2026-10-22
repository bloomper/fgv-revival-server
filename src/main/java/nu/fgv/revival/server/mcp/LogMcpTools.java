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


package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.decisionlog.LogEntryDto;
import nu.fgv.revival.server.decisionlog.LogEntryFilterDto;
import nu.fgv.revival.server.decisionlog.LogEntryService;
import nu.fgv.revival.server.decisionlog.LogEntryStatus;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * The decision log over MCP (contracts/mcp.md, FR-043–FR-045). The same service methods as GraphQL, with the token's
 * person as the actor; owners are given as names and resolved by {@link OwnerResolver}.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Component
public class LogMcpTools {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final LogEntryService logEntryService;
    private final OwnerResolver ownerResolver;
    private final CallerService callerService;
    private final McpToolSupport support;

    public LogMcpTools(final LogEntryService logEntryService, final OwnerResolver ownerResolver, final CallerService callerService,
                       final McpToolSupport support) {
        this.logEntryService = logEntryService;
        this.ownerResolver = ownerResolver;
        this.callerService = callerService;
        this.support = support;
    }

    @McpTool(name = "list_log_entries",
            description = "List rows of a revival's decision log. Filters combine: mine, open (open or waiting), overdue, types, tag and text.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public LogEntries listLogEntries(
            @McpToolParam(description = "A revival you can see") final Long revivalId,
            @McpToolParam(required = false, description = "Rows you own") final @Nullable Boolean mine,
            @McpToolParam(required = false, description = "OPEN or WAITING") final @Nullable Boolean open,
            @McpToolParam(required = false, description = "Open rows past their deadline") final @Nullable Boolean overdue,
            @McpToolParam(required = false, description = "DECISION, TODO or QUESTION") final @Nullable List<LogEntryType> types,
            @McpToolParam(required = false, description = "A tag") final @Nullable String tag,
            @McpToolParam(required = false, description = "Words in the text, comment or owner name") final @Nullable String text,
            @McpToolParam(required = false, description = "1-200, default 50. The first rows by number.") final @Nullable Integer limit) {
        return support.call("list_log_entries", () -> {
            final List<LogEntryDto> rows = logEntryService.list(revivalId, new LogEntryFilterDto(mine, open, overdue, types, null,
                    tag == null || tag.isBlank() ? null : List.of(tag.strip()), text), Locale.ENGLISH);
            final int first = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

            return new LogEntries(rows.stream().limit(first).map(LogMcpTools::toMcp).toList(), rows.size());
        });
    }

    @McpTool(name = "create_log_entry",
            description = "Add a decision, to-do or question to a revival's decision log. The owner is a steering group member's name, \"me\", or the name of someone outside the steering group.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public LogEntryWritten createLogEntry(
            @McpToolParam(description = "A revival you can work in") final Long revivalId,
            @McpToolParam(description = "DECISION, TODO or QUESTION") final LogEntryType type,
            @McpToolParam(description = "At most 5,000 characters") final String text,
            @McpToolParam(required = false, description = "A display name, or me. A unique member match is that member; several matches are refused with the candidates; none is a free-text owner.") final @Nullable String owner,
            @McpToolParam(required = false, description = "yyyy-MM-dd") final @Nullable String deadline,
            @McpToolParam(required = false, description = "A comment") final @Nullable String comment,
            @McpToolParam(required = false, description = "Tags") final @Nullable List<String> tags) {
        return support.call("create_log_entry", () -> {
            final ResolvedOwner resolved = resolve(revivalId, owner);
            final LogEntryDto created = logEntryService.create(new LogEntryService.Draft(revivalId, null, null, type, text, null, resolved.input(),
                    date(deadline), comment, tags, null, null), Locale.ENGLISH);

            return LogEntryWritten.of(created, resolved.resolution());
        });
    }

    @McpTool(name = "update_log_entry",
            description = "Change a row in a revival's decision log, by its number: status, owner, deadline, comment, text or tags. Rows are never deleted; set the status to CANCELLED instead.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public LogEntryWritten updateLogEntry(
            @McpToolParam(description = "A revival you can work in") final Long revivalId,
            @McpToolParam(description = "The row's number") final Integer number,
            @McpToolParam(required = false, description = "OPEN, DONE, WAITING or CANCELLED") final @Nullable LogEntryStatus status,
            @McpToolParam(required = false, description = "As in create_log_entry. An empty string clears it.") final @Nullable String owner,
            @McpToolParam(required = false, description = "yyyy-MM-dd. An empty string clears it.") final @Nullable String deadline,
            @McpToolParam(required = false, description = "Replaces the comment. An empty string clears it.") final @Nullable String comment,
            @McpToolParam(required = false, description = "The row's text") final @Nullable String text,
            @McpToolParam(required = false, description = "Replaces the tags") final @Nullable List<String> tags) {
        return support.call("update_log_entry", () -> {
            final LogEntryDto row = logEntryService.getByNumber(revivalId, number, Locale.ENGLISH);
            final boolean clearOwner = owner != null && owner.isBlank();
            final ResolvedOwner resolved = clearOwner ? new ResolvedOwner(null, Resolution.NONE) : resolve(revivalId, owner);
            final LogEntryDto updated = logEntryService.update(new LogEntryService.Change(row.id(), row.version(), null, null, null, text, status,
                    resolved.input(), clearOwner, deadline == null || deadline.isBlank() ? null : date(deadline), deadline != null && deadline.isBlank(),
                    comment == null || comment.isBlank() ? null : comment, comment != null && comment.isBlank(), tags, null, null, false), Locale.ENGLISH);

            return LogEntryWritten.of(updated, owner == null ? (updated.owner() == null ? Resolution.NONE
                    : updated.owner().person() != null ? Resolution.MEMBER : Resolution.FREE_TEXT) : resolved.resolution());
        });
    }

    private ResolvedOwner resolve(final Long revivalId, final @Nullable String owner) {
        if (owner == null || owner.isBlank()) {
            return new ResolvedOwner(null, Resolution.NONE);
        }
        return switch (ownerResolver.resolve(revivalId, owner, callerService.current())) {
            case OwnerResolver.Resolution.Member member -> new ResolvedOwner(new OwnerInputDto(member.personId(), null), Resolution.MEMBER);
            case OwnerResolver.Resolution.FreeText name -> new ResolvedOwner(new OwnerInputDto(null, name.name()), Resolution.FREE_TEXT);
            case OwnerResolver.Resolution.Ambiguous ambiguous ->
                    throw new BadRequestException("error.owner.ambiguous", owner.strip(), String.join(", ", ambiguous.candidates()));
        };
    }

    private static @Nullable LocalDate date(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (final DateTimeParseException e) {
            throw new BadRequestException("error.date.invalid");
        }
    }

    private static LogEntry toMcp(final LogEntryDto row) {
        return new LogEntry(row.number(), row.date(), row.type(), row.status(), row.overdue(), row.text(), McpOwner.of(row.owner()), row.deadline(),
                row.comment(), row.tags(), row.meetingId());
    }

    private record ResolvedOwner(@Nullable OwnerInputDto input, Resolution resolution) {
    }

    public enum Resolution {
        MEMBER, FREE_TEXT, NONE
    }

    public record LogEntry(int number, LocalDate date, LogEntryType type, LogEntryStatus status, boolean overdue, String text,
                           @Nullable McpOwner owner, @Nullable LocalDate deadline, @Nullable String comment, List<String> tags,
                           @Nullable Long meeting) {
    }

    public record LogEntries(List<LogEntry> items, int total) {
    }

    /**
     * A row as list_log_entries returns it, plus how the owner's name was read, so the assistant can say when it was not
     * a member (FR-045).
     */
    public record LogEntryWritten(int number, LocalDate date, LogEntryType type, LogEntryStatus status, boolean overdue, String text,
                                  @Nullable McpOwner owner, @Nullable LocalDate deadline, @Nullable String comment, List<String> tags,
                                  @Nullable Long meeting, Resolution ownerResolution) {

        static LogEntryWritten of(final LogEntryDto row, final Resolution resolution) {
            return new LogEntryWritten(row.number(), row.date(), row.type(), row.status(), row.overdue(), row.text(), McpOwner.of(row.owner()),
                    row.deadline(), row.comment(), row.tags(), row.meetingId(), resolution);
        }
    }
}
