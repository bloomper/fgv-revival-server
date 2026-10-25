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

import nu.fgv.revival.server.budget.BudgetDtos;
import nu.fgv.revival.server.budget.BudgetService;
import nu.fgv.revival.server.closing.ClosingDtos;
import nu.fgv.revival.server.closing.ClosingService;
import nu.fgv.revival.server.closing.SyncRunStatus;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.participant.ParticipantDtos;
import nu.fgv.revival.server.participant.ParticipantService;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalCreateDto;
import nu.fgv.revival.server.revival.RevivalDto;
import nu.fgv.revival.server.revival.RevivalService;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalStatusDto;
import nu.fgv.revival.server.revival.RevivalShowDto;
import nu.fgv.revival.server.revival.RevivalStatusChangeDto;
import nu.fgv.revival.server.revival.RevivalStatusService;
import nu.fgv.revival.server.revival.RevivalUpdateDto;
import nu.fgv.revival.server.revival.UpcomingRevivalDto;
import nu.fgv.revival.server.revival.UpcomingRevivals;
import nu.fgv.revival.server.spex.SpexDto;
import nu.fgv.revival.server.spex.SpexService;
import nu.fgv.revival.server.util.Amounts;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * MCP tools for revivals. Members,
 * emails and contact details are never returned.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class RevivalMcpTools {

    private final RevivalService revivalService;
    private final RevivalStatusService statusService;
    private final CallerService callerService;
    private final BudgetService budgetService;
    private final ParticipantService participantService;
    private final ClosingService closingService;
    private final UpcomingRevivals upcomingRevivals;
    private final SpexService spexService;
    private final McpToolSupport support;

    public RevivalMcpTools(final RevivalService revivalService, final RevivalStatusService statusService, final CallerService callerService,
                           final BudgetService budgetService, final ParticipantService participantService, final ClosingService closingService,
                           final UpcomingRevivals upcomingRevivals, final SpexService spexService, final McpToolSupport support) {
        this.revivalService = revivalService;
        this.statusService = statusService;
        this.callerService = callerService;
        this.budgetService = budgetService;
        this.participantService = participantService;
        this.closingService = closingService;
        this.upcomingRevivals = upcomingRevivals;
        this.spexService = spexService;
        this.support = support;
    }

    private static RevivalDetails toDetails(final RevivalDto revival, final Caller caller) {
        final RevivalRoleAssignment assignment = caller.roleIn(revival.id());

        return new RevivalDetails(revival.id(), revival.spex().title(), revival.year(), revival.status(), revival.premiereDate(), revival.shows(),
                revival.guideVersionId(), revival.permissions().allowedStatuses(), assignment == null ? null : assignment.role());
    }

    private static McpRevival toMcpRevival(final RevivalDto revival, final Caller caller) {
        final RevivalRoleAssignment assignment = caller.roleIn(revival.id());

        return new McpRevival(
                revival.id(),
                revival.spex().title(),
                revival.spex().available(),
                revival.year(),
                revival.status(),
                revival.premiereDate(),
                assignment == null ? null : assignment.role());
    }

    @McpTool(name = "list_revivals",
            description = "List the revivals you can see, with spex, year, status and premiere date. Optionally filter by status.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<McpRevival> listRevivals(
            @McpToolParam(required = false, description = "Only these statuses: PROPOSED, APPROVED, ORGANISING, OPEN, CLOSING, SETTLING, ARCHIVED or REJECTED") final @Nullable List<RevivalStatus> status) {
        return support.call("list_revivals", () -> {
            final List<RevivalDto> revivals = revivalService.findVisible(status);
            final Caller caller = callerService.current();

            return new McpToolSupport.ListResult<>(revivals.stream().map(revival -> toMcpRevival(revival, caller)).toList());
        });
    }

    @McpTool(name = "get_revival_status",
            description = "Get a revival's status: its lifecycle status, overdue decision log rows and checklist items, open guide requirements per chapter, its budgeted and actual result when you can read its budget, its participants' responses when you can read them, and while it is closing or settling, whether its participation has been written to Spexregister.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpRevivalStatus getRevivalStatus(@McpToolParam(description = "A revival you can see") final Long revivalId) {
        return support.call("get_revival_status", () -> {
            final RevivalStatusDto status = statusService.status(revivalId, Locale.ENGLISH);

            return new McpRevivalStatus(status.revival(),
                    status.overdueLogEntries().stream().map(row -> new OverdueLogEntry(row.number(), row.type(), row.text(), McpOwner.of(row.owner()),
                            row.deadline())).toList(),
                    status.overdueChecklistItems().stream().map(ChecklistMcpTools::toMcp).map(item -> new OverdueChecklistItem(item.id(), item.checklist(),
                            item.text(), item.owner(), item.deadline())).toList(),
                    status.openRequirements().stream().map(OpenRequirements::of).toList(),
                    budgetPosition(revivalId),
                    responseCounts(revivalId),
                    closing(status.revival().status(), revivalId));
        });
    }

    @McpTool(name = "get_revival",
            description = "Get one revival you can see: spex, year, status, premiere date, shows with time and venue, the guide version it follows, the statuses you may move it to, and your role in it.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public RevivalDetails getRevival(@McpToolParam(description = "A revival you can see") final Long revivalId) {
        return support.call("get_revival", () -> toDetails(revivalService.findById(revivalId), callerService.current()));
    }

    @McpTool(name = "update_revival",
            description = "Change a revival's premiere date or its shows. Leave out what stays as it is; shows replace all shows.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public RevivalDetails updateRevival(
            @McpToolParam(description = "A revival whose details you can change") final Long revivalId,
            @McpToolParam(required = false, description = "yyyy-MM-dd. An empty string clears it.") final @Nullable String premiereDate,
            @McpToolParam(required = false, description = "Every show: date (yyyy-MM-dd), time (HH:mm, optional) and venue (optional)") final @Nullable List<RevivalShowDto> shows) {
        return support.call("update_revival", () -> {
            final RevivalDto revival = revivalService.findById(revivalId);
            final boolean clear = premiereDate != null && premiereDate.isBlank();

            return toDetails(revivalService.update(new RevivalUpdateDto(revivalId, revival.version(), null, null,
                    clear ? null : McpToolSupport.date(premiereDate), clear, shows)), callerService.current());
        });
    }

    @McpTool(name = "change_revival_status",
            description = "Move a revival to another status in its lifecycle, e.g. from ORGANISING to OPEN. get_revival lists the statuses you may move it to. Archiving and rejecting are for super admins.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public RevivalDetails changeRevivalStatus(
            @McpToolParam(description = "A revival you can see") final Long revivalId,
            @McpToolParam(description = "PROPOSED, APPROVED, ORGANISING, OPEN, CLOSING, SETTLING, ARCHIVED or REJECTED") final RevivalStatus status) {
        return support.call("change_revival_status", () -> {
            final RevivalDto revival = revivalService.findById(revivalId);

            return toDetails(revivalService.changeStatus(new RevivalStatusChangeDto(revivalId, revival.status(), status)), callerService.current());
        });
    }

    @McpTool(name = "create_revival",
            description = "Super admins: create a revival of a spex from Spexregister (search_spex finds it) in a year. It starts as PROPOSED; directeurs and steering group members are added in the web.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public RevivalDetails createRevival(
            @McpToolParam(description = "The spex's id in Spexregister") final Long spexId,
            @McpToolParam(description = "The year it plays") final Integer year) {
        return support.call("create_revival", () -> toDetails(revivalService.create(new RevivalCreateDto(spexId, year)), callerService.current()));
    }

    @McpTool(name = "search_spex",
            description = "Super admins: search Spexregister's spex by title, for create_revival.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<SpexDto> searchSpex(
            @McpToolParam(description = "Words in the title") final String query,
            @McpToolParam(required = false, description = "1-50, default 20") final @Nullable Integer limit) {
        return support.call("search_spex", () -> new McpToolSupport.ListResult<>(spexService.search(query, limit == null ? 20 : Math.clamp(limit, 1, 50))));
    }

    @McpTool(name = "list_upcoming_revivals",
            description = "List the revivals coming up, as everyone with access sees them on the start page: those being organised or open that have not played their last show, soonest first, with the premiere and the shows.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<UpcomingRevivalDto> listUpcomingRevivals() {
        return support.call("list_upcoming_revivals", () -> new McpToolSupport.ListResult<>(upcomingRevivals.list(Locale.ENGLISH)));
    }

    /**
     * Counts by response, for {@code get_revival_status}; null for callers who do not read participants. Counts only,
     * never who.
     */
    private @Nullable List<ParticipantDtos.ResponseCount> responseCounts(final Long revivalId) {
        final ParticipantDtos.Counts counts = participantService.counts(revivalId);

        return counts == null ? null : counts.byResponse();
    }

    /**
     * The budget's result, for callers who read the budget.
     */
    private @Nullable BudgetPosition budgetPosition(final Long revivalId) {
        if (!budgetService.canRead(revivalId)) {
            return null;
        }

        final BudgetDtos.Totals totals = budgetService.totals(revivalId);

        return new BudgetPosition(Amounts.format(totals.budgetedResult()), Amounts.format(totals.actualResult()));
    }

    /**
     * The sync and anonymization, while the revival is closing or settling, for callers who read its work.
     */
    private @Nullable ClosingPosition closing(final RevivalStatus status, final Long revivalId) {
        if (status != RevivalStatus.CLOSING && status != RevivalStatus.SETTLING) {
            return null;
        }

        final ClosingDtos.ClosingStatusDto closing = closingService.closing(revivalId);

        if (closing == null) {
            return null;
        }

        final ClosingDtos.SyncRunDto last = closing.lastSync();

        return new ClosingPosition(last == null ? null : last.status(), last != null && last.status() == SyncRunStatus.DONE,
                closing.membershipsPaid(), closing.surplusDecision() != null, closing.anonymizedAt() != null);
    }

    /**
     * Where the revival stands on closing: the latest sync run's status, whether the participation is in Spexregister,
     * memberships paid, whether the surplus is decided, and whether the people have been removed.
     */
    public record ClosingPosition(@Nullable SyncRunStatus lastSync, boolean synced, int membershipsPaid,
                                  boolean surplusDecided,
                                  boolean anonymized) {
    }

    /**
     * A revival as {@code list_revivals} returns it.
     */
    public record McpRevival(Long id,
                             @Nullable String spexTitle,
                             boolean spexAvailable,
                             Integer year,
                             RevivalStatus status,
                             @Nullable LocalDate premiereDate,
                             @Nullable RevivalRole role) {
    }

    /**
     * A revival as {@code get_revival} returns it: no people.
     */
    public record RevivalDetails(Long id, @Nullable String spexTitle, Integer year, RevivalStatus status, @Nullable LocalDate premiereDate,
                                 List<RevivalShowDto> shows, @Nullable Long guideVersionId, List<RevivalStatus> allowedStatuses,
                                 @Nullable RevivalRole role) {
    }

    /**
     * A revival's status as {@code get_revival_status} returns it.
     */
    public record McpRevivalStatus(RevivalStatusDto.Summary revival,
                                   List<OverdueLogEntry> overdueLogEntries,
                                   List<OverdueChecklistItem> overdueChecklistItems,
                                   List<OpenRequirements> openRequirements,
                                   @Nullable BudgetPosition budget,
                                   @Nullable List<ParticipantDtos.ResponseCount> participantResponses,
                                   @Nullable ClosingPosition closing) {
    }

    /**
     * The budgeted and actual result in kronor (income minus cost).
     */
    public record BudgetPosition(String budgetedResult, String actualResult) {
    }

    public record OpenRequirements(String chapterKey, ChecklistMcpTools.@Nullable Text chapterTitle, int open,
                                   int total) {

        static OpenRequirements of(final RevivalStatusDto.OpenRequirements requirements) {
            return new OpenRequirements(requirements.chapterKey(),
                    requirements.chapterTitle() == null ? null : ChecklistMcpTools.Text.of(requirements.chapterTitle()),
                    requirements.open(), requirements.total());
        }
    }

    public record OverdueLogEntry(int number, LogEntryType type, String text, @Nullable McpOwner owner,
                                  @Nullable LocalDate deadline) {
    }

    public record OverdueChecklistItem(Long id, ChecklistMcpTools.ChecklistRef checklist, ChecklistMcpTools.Text text,
                                       @Nullable McpOwner owner,
                                       @Nullable LocalDate deadline) {
    }
}
