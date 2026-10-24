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
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalDto;
import nu.fgv.revival.server.revival.RevivalService;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalStatusDto;
import nu.fgv.revival.server.revival.RevivalStatusService;
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
 * MCP tools for revivals. A thin adapter over {@link RevivalService}: the same rules as GraphQL (FR-025). Members,
 * emails and contact details are never returned (constitution V).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Component
public class RevivalMcpTools {

    private final RevivalService revivalService;
    private final RevivalStatusService statusService;
    private final CallerService callerService;
    private final BudgetService budgetService;
    private final ParticipantMcpTools participantTools;
    private final ClosingService closingService;
    private final McpToolSupport support;

    public RevivalMcpTools(final RevivalService revivalService, final RevivalStatusService statusService, final CallerService callerService,
                           final BudgetService budgetService, final ParticipantMcpTools participantTools, final ClosingService closingService,
                           final McpToolSupport support) {
        this.revivalService = revivalService;
        this.statusService = statusService;
        this.callerService = callerService;
        this.budgetService = budgetService;
        this.participantTools = participantTools;
        this.closingService = closingService;
        this.support = support;
    }

    @McpTool(name = "list_revivals",
            description = "List the revivals you can see, with spex, year, status and premiere date. Optionally filter by status.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<McpRevival> listRevivals(
            @McpToolParam(required = false, description = "Only these statuses: PROPOSED, APPROVED, ORGANISING, OPEN, CLOSING, SETTLING, ARCHIVED or REJECTED")
            final @Nullable List<RevivalStatus> status) {
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
                    participantTools.responseCounts(revivalId),
                    closing(status.revival().status(), revivalId));
        });
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
    public record ClosingPosition(@Nullable SyncRunStatus lastSync, boolean synced, int membershipsPaid, boolean surplusDecided,
                                  boolean anonymized) {
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

    /**
     * A revival as {@code list_revivals} returns it (contracts/mcp.md).
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
     * A revival's status as {@code get_revival_status} returns it (contracts/mcp.md).
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

    public record OpenRequirements(String chapterKey, ChecklistMcpTools.@Nullable Text chapterTitle, int open, int total) {

        static OpenRequirements of(final RevivalStatusDto.OpenRequirements requirements) {
            return new OpenRequirements(requirements.chapterKey(),
                    requirements.chapterTitle() == null ? null : ChecklistMcpTools.Text.of(requirements.chapterTitle()),
                    requirements.open(), requirements.total());
        }
    }

    public record OverdueLogEntry(int number, LogEntryType type, String text, @Nullable McpOwner owner, @Nullable LocalDate deadline) {
    }

    public record OverdueChecklistItem(Long id, ChecklistMcpTools.ChecklistRef checklist, ChecklistMcpTools.Text text, @Nullable McpOwner owner,
                                       @Nullable LocalDate deadline) {
    }
}
