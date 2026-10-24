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

import nu.fgv.revival.server.budget.BudgetCategory;
import nu.fgv.revival.server.budget.BudgetCategoryKind;
import nu.fgv.revival.server.budget.BudgetCategoryService;
import nu.fgv.revival.server.budget.BudgetDtos;
import nu.fgv.revival.server.budget.BudgetService;
import nu.fgv.revival.server.budget.TransactionService;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.Amounts;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * A revival's budget over MCP (M5-FR-13): its position for readers of the budget, never who paid what, and recording a
 * transaction for its writers. The same rules as GraphQL.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Component
public class BudgetMcpTools {

    private final BudgetService budgetService;
    private final TransactionService transactionService;
    private final BudgetCategoryService categoryService;
    private final OwnerResolver ownerResolver;
    private final OwnerViews ownerViews;
    private final CallerService callerService;
    private final McpToolSupport support;

    public BudgetMcpTools(final BudgetService budgetService, final TransactionService transactionService, final BudgetCategoryService categoryService,
                          final OwnerResolver ownerResolver, final OwnerViews ownerViews, final CallerService callerService,
                          final McpToolSupport support) {
        this.budgetService = budgetService;
        this.transactionService = transactionService;
        this.categoryService = categoryService;
        this.ownerResolver = ownerResolver;
        this.ownerViews = ownerViews;
        this.callerService = callerService;
        this.support = support;
    }

    @McpTool(name = "get_budget_summary",
            description = "Get a revival's budget position: budgeted and actual income, cost and result, per category, the loan from FGV/JK, and how many costs are not yet reimbursed and how many transactions lack a receipt. Amounts are Swedish kronor.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public BudgetSummary getBudgetSummary(@McpToolParam(description = "A revival whose budget you can read") final Long revivalId) {
        return support.call("get_budget_summary", () -> {
            final BudgetDtos.Totals totals = budgetService.totals(revivalId);
            final BudgetDtos.Settings settings = budgetService.settings(revivalId);

            return new BudgetSummary(
                    new Position(Amounts.format(totals.budgetedIncome()), Amounts.format(totals.budgetedCost()), Amounts.format(totals.budgetedResult())),
                    new Position(Amounts.format(totals.actualIncome()), Amounts.format(totals.actualCost()), Amounts.format(totals.actualResult())),
                    budgetService.categories(revivalId).stream().map(summary -> new Category(summary.category().id(),
                            ChecklistMcpTools.Text.of(summary.category().name()), summary.category().kind(), Amounts.format(summary.budgeted()),
                            Amounts.format(summary.actual()))).toList(),
                    new Loan(settings.loanAmount() == null ? null : Amounts.format(settings.loanAmount()), settings.loanReceivedOn(),
                            settings.loanRepaidOn()),
                    settings.participantsPlanned(),
                    totals.unreimbursedCount(),
                    Amounts.format(totals.unreimbursed()),
                    totals.missingReceipts());
        });
    }

    @McpTool(name = "add_transaction",
            description = "Record money that came in or went out in a revival's budget. Income or cost follows the category. For a cost someone paid out of their own pocket, give who paid: a steering group member's name, \"me\", or the name of someone outside the steering group. Transactions are never deleted; they can be cancelled in the web.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public TransactionWritten addTransaction(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(description = "The date, yyyy-MM-dd") final String date,
            @McpToolParam(description = "The amount in kronor, positive, at most two decimals, e.g. 1200 or 349.50") final String amount,
            @McpToolParam(description = "An active budget category, by id or by its Swedish or English name") final String category,
            @McpToolParam(description = "What it was, 1-255 characters") final String description,
            @McpToolParam(required = false, description = "Who paid a cost out of their own pocket: a display name, or me. A unique member match is that member; several matches are refused with the candidates; none is a free-text name.") final @Nullable String paidBy) {
        return support.call("add_transaction", () -> {
            final @Nullable LocalDate day = McpToolSupport.date(date);
            final @Nullable BigDecimal value = Amounts.parse(amount);

            if (day == null) {
                throw new BadRequestException("error.date.invalid");
            }
            if (value == null) {
                throw new BadRequestException("error.budget.amount", "0.01", "10000000");
            }

            final BudgetCategory resolved = categoryService.resolveActive(category);
            final ResolvedPayer payer = resolve(revivalId, paidBy);
            final BudgetDtos.Transaction transaction = transactionService.create(new TransactionService.NewTransaction(revivalId, day, value,
                    resolved.getId(), description, null, payer.input()));
            final OwnerDto owner = ownerViews.toDto(transaction.revivalId(), transaction.payer(), Locale.ENGLISH);

            return new TransactionWritten(transaction.id(), transaction.date(), Amounts.format(transaction.amount()),
                    new ChecklistMcpTools.Text(resolved.getNameSv(), resolved.getNameEn()), resolved.getKind(), transaction.description(),
                    McpOwner.of(owner), payer.resolution());
        });
    }

    private ResolvedPayer resolve(final Long revivalId, final @Nullable String paidBy) {
        if (paidBy == null || paidBy.isBlank()) {
            return new ResolvedPayer(null, LogMcpTools.Resolution.NONE);
        }
        return switch (ownerResolver.resolve(revivalId, paidBy, callerService.current())) {
            case OwnerResolver.Resolution.Member member -> new ResolvedPayer(new OwnerInputDto(member.personId(), null), LogMcpTools.Resolution.MEMBER);
            case OwnerResolver.Resolution.FreeText name -> new ResolvedPayer(new OwnerInputDto(null, name.name()), LogMcpTools.Resolution.FREE_TEXT);
            case OwnerResolver.Resolution.Ambiguous ambiguous ->
                    throw new BadRequestException("error.owner.ambiguous", paidBy.strip(), String.join(", ", ambiguous.candidates()));
        };
    }

    private record ResolvedPayer(@Nullable OwnerInputDto input, LogMcpTools.Resolution resolution) {
    }

    public record Position(String income, String cost, String result) {
    }

    public record Category(String id, ChecklistMcpTools.Text name, BudgetCategoryKind kind, String budgeted, String actual) {
    }

    public record Loan(@Nullable String amount, @Nullable LocalDate receivedOn, @Nullable LocalDate repaidOn) {
    }

    /**
     * A budget as get_budget_summary returns it: amounts only, no payers.
     */
    public record BudgetSummary(Position budgeted, Position actual, List<Category> categories, Loan loan, @Nullable Integer participantsPlanned,
                                int unreimbursedCount, String unreimbursed, int missingReceipts) {
    }

    /**
     * A transaction as add_transaction returns it, with how the payer's name was read.
     */
    public record TransactionWritten(Long id, LocalDate date, String amount, ChecklistMcpTools.Text category, BudgetCategoryKind kind,
                                     String description, @Nullable McpOwner paidBy, LogMcpTools.Resolution paidByResolution) {
    }
}
