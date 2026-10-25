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
import nu.fgv.revival.server.budget.BudgetCategoryDto;
import nu.fgv.revival.server.budget.BudgetCategoryService;
import nu.fgv.revival.server.budget.BudgetLine;
import nu.fgv.revival.server.budget.BudgetScenario;
import nu.fgv.revival.server.budget.BudgetScenarioService;
import nu.fgv.revival.server.budget.BudgetTransaction;
import nu.fgv.revival.server.budget.ReferenceRevivalDto;
import nu.fgv.revival.server.budget.ReferenceRevivalService;
import nu.fgv.revival.server.budget.ScenarioParameters;
import nu.fgv.revival.server.budget.ScenarioResultDto;
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
 * A revival's budget over MCP: its position for readers of the budget, never who paid what, and recording a
 * transaction for its writers. The same rules as GraphQL.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class BudgetMcpTools {

    private final BudgetService budgetService;
    private final TransactionService transactionService;
    private final BudgetCategoryService categoryService;
    private final OwnerResolver ownerResolver;
    private final OwnerViews ownerViews;
    private final CallerService callerService;
    private final BudgetScenarioService scenarioService;
    private final ReferenceRevivalService referenceService;
    private final McpToolSupport support;

    public BudgetMcpTools(final BudgetService budgetService, final TransactionService transactionService, final BudgetCategoryService categoryService,
                          final OwnerResolver ownerResolver, final OwnerViews ownerViews, final CallerService callerService,
                          final BudgetScenarioService scenarioService, final ReferenceRevivalService referenceService, final McpToolSupport support) {
        this.scenarioService = scenarioService;
        this.referenceService = referenceService;
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

    private @Nullable String categoryId(final @Nullable String category) {
        return category == null || category.isBlank() ? null : categoryService.resolveActive(category).getId();
    }

    private static @Nullable BigDecimal quantity(final @Nullable Double quantity) {
        return quantity == null ? null : BigDecimal.valueOf(quantity);
    }

    private BudgetDtos.Line line(final Long revivalId, final Long lineId) {
        return McpToolSupport.byId(budgetService.lines(revivalId, true), BudgetDtos.Line::id, lineId, BudgetLine.class);
    }

    private BudgetDtos.Transaction transaction(final Long revivalId, final Long transactionId) {
        return McpToolSupport.byId(transactionService.list(revivalId, true), BudgetDtos.Transaction::id, transactionId, BudgetTransaction.class);
    }

    private TransactionView toView(final BudgetDtos.Transaction transaction) {
        return new TransactionView(transaction.id(), transaction.date(), Amounts.format(transaction.amount()), transaction.categoryId(),
                transaction.description(), transaction.budgetLineId(), McpOwner.of(ownerViews.toDto(transaction.revivalId(), transaction.payer(), Locale.ENGLISH)),
                transaction.reimbursed(), transaction.receipt() != null, transaction.cancelled());
    }

    @McpTool(name = "list_budget_lines",
            description = "List a revival's budget lines: category, description, quantity, amount each and in total, the event or decision it belongs to, and comment.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<BudgetDtos.Line> listBudgetLines(
            @McpToolParam(description = "A revival whose budget you can read") final Long revivalId,
            @McpToolParam(required = false, description = "Also removed lines") final @Nullable Boolean includeRemoved) {
        return support.call("list_budget_lines", () -> new McpToolSupport.ListResult<>(budgetService.lines(revivalId, Boolean.TRUE.equals(includeRemoved))));
    }

    @McpTool(name = "create_budget_line",
            description = "Add a line to a revival's budget: what it is, the category, how many and the amount each in kronor.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public BudgetDtos.Line createBudgetLine(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(description = "An active budget category, by id or by its Swedish or English name") final String category,
            @McpToolParam(description = "What it is, 1-255 characters") final String description,
            @McpToolParam(description = "The amount each in kronor, e.g. 1200 or 349.50") final String unitAmount,
            @McpToolParam(required = false, description = "How many, default 1") final @Nullable Double quantity,
            @McpToolParam(required = false, description = "The event it belongs to, by id") final @Nullable Long eventId,
            @McpToolParam(required = false, description = "A comment") final @Nullable String comment) {
        return support.call("create_budget_line", () -> budgetService.createLine(new BudgetService.NewLine(revivalId, categoryId(category), description,
                quantity(quantity), McpToolSupport.amount(unitAmount), eventId, null, comment, null)));
    }

    @McpTool(name = "update_budget_line",
            description = "Change a budget line. Leave out what stays as it is; an empty comment clears it.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public BudgetDtos.Line updateBudgetLine(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(description = "The line, by id") final Long lineId,
            @McpToolParam(required = false, description = "A budget category, by id or name") final @Nullable String category,
            @McpToolParam(required = false, description = "What it is") final @Nullable String description,
            @McpToolParam(required = false, description = "The amount each in kronor") final @Nullable String unitAmount,
            @McpToolParam(required = false, description = "How many") final @Nullable Double quantity,
            @McpToolParam(required = false, description = "The event it belongs to, by id") final @Nullable Long eventId,
            @McpToolParam(required = false, description = "A comment; an empty string clears it") final @Nullable String comment) {
        return support.call("update_budget_line", () -> {
            final BudgetDtos.Line line = line(revivalId, lineId);

            return budgetService.updateLine(new BudgetService.LineChange(lineId, line.version(), categoryId(category), description, quantity(quantity),
                    McpToolSupport.amount(unitAmount), eventId, false, null, false, comment == null || comment.isBlank() ? null : comment,
                    comment != null && comment.isBlank()));
        });
    }

    @McpTool(name = "set_budget_line_removed",
            description = "Remove a budget line from the totals, or restore a removed one. Removed lines stay in the history; nothing is deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public BudgetDtos.Line setBudgetLineRemoved(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(description = "The line, by id") final Long lineId,
            @McpToolParam(description = "true to remove, false to restore") final Boolean removed) {
        return support.call("set_budget_line_removed", () -> {
            final BudgetDtos.Line line = line(revivalId, lineId);

            return removed ? budgetService.removeLine(lineId, line.version()) : budgetService.restoreLine(lineId, line.version());
        });
    }

    @McpTool(name = "list_budget_proposals",
            description = "List the budget lines a revival's events propose from their event types, for those not added yet: the event, the category, the quantity and the amount.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<BudgetDtos.Proposal> listBudgetProposals(@McpToolParam(description = "A revival whose budget you can read") final Long revivalId) {
        return support.call("list_budget_proposals", () -> new McpToolSupport.ListResult<>(budgetService.proposals(revivalId)));
    }

    @McpTool(name = "list_transactions",
            description = "List a revival's transactions: date, amount, category, description, budget line, who paid a cost by name, whether it is reimbursed and has a receipt. Never bank details.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<TransactionView> listTransactions(
            @McpToolParam(description = "A revival whose budget you can read") final Long revivalId,
            @McpToolParam(required = false, description = "Also cancelled ones") final @Nullable Boolean includeCancelled) {
        return support.call("list_transactions", () -> new McpToolSupport.ListResult<>(transactionService.list(revivalId, Boolean.TRUE.equals(includeCancelled))
                .stream().map(this::toView).toList()));
    }

    @McpTool(name = "update_transaction",
            description = "Change a transaction that is not cancelled. Leave out what stays as it is; an empty paidBy clears who paid.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public TransactionView updateTransaction(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(description = "The transaction, by id") final Long transactionId,
            @McpToolParam(required = false, description = "yyyy-MM-dd") final @Nullable String date,
            @McpToolParam(required = false, description = "The amount in kronor") final @Nullable String amount,
            @McpToolParam(required = false, description = "A budget category, by id or name") final @Nullable String category,
            @McpToolParam(required = false, description = "What it was") final @Nullable String description,
            @McpToolParam(required = false, description = "The budget line it belongs to, by id") final @Nullable Long budgetLineId,
            @McpToolParam(required = false, description = "As in add_transaction; an empty string clears it") final @Nullable String paidBy) {
        return support.call("update_transaction", () -> {
            final BudgetDtos.Transaction transaction = transaction(revivalId, transactionId);

            return toView(transactionService.update(new TransactionService.TransactionChange(transactionId, transaction.version(), McpToolSupport.date(date),
                    McpToolSupport.amount(amount), categoryId(category), description, budgetLineId, false, support.owner(revivalId, paidBy),
                    paidBy != null && paidBy.isBlank())));
        });
    }

    @McpTool(name = "cancel_transaction",
            description = "Cancel a transaction recorded by mistake. It stays readable and never counts in the totals; transactions are never deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public TransactionView cancelTransaction(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(description = "The transaction, by id") final Long transactionId) {
        return support.call("cancel_transaction", () -> toView(transactionService.cancel(transactionId, transaction(revivalId, transactionId).version())));
    }

    @McpTool(name = "set_transaction_reimbursed",
            description = "Mark a cost someone paid out of their own pocket as reimbursed, or not.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public TransactionView setTransactionReimbursed(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(description = "The transaction, by id") final Long transactionId,
            @McpToolParam(description = "true when paid back") final Boolean reimbursed) {
        return support.call("set_transaction_reimbursed", () -> toView(transactionService.setReimbursed(transactionId,
                transaction(revivalId, transactionId).version(), reimbursed)));
    }

    @McpTool(name = "update_budget_settings",
            description = "Change a revival's budget settings: participants planned, the participation fee and the loan from FGV/JK. Leave out what stays as it is.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public BudgetDtos.Settings updateBudgetSettings(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(required = false, description = "How many participants are planned") final @Nullable Integer participantsPlanned,
            @McpToolParam(required = false, description = "The fee in kronor") final @Nullable String participationFee,
            @McpToolParam(required = false, description = "The loan in kronor") final @Nullable String loanAmount,
            @McpToolParam(required = false, description = "When the loan came in, yyyy-MM-dd") final @Nullable String loanReceivedOn,
            @McpToolParam(required = false, description = "When it was paid back, yyyy-MM-dd") final @Nullable String loanRepaidOn) {
        return support.call("update_budget_settings", () -> {
            final BudgetDtos.Settings settings = budgetService.settings(revivalId);

            budgetService.updateSettings(new BudgetService.SettingsChange(revivalId, settings.version(),
                    participantsPlanned == null ? settings.participantsPlanned() : participantsPlanned,
                    participationFee == null ? settings.participationFee() : McpToolSupport.amount(participationFee),
                    loanAmount == null ? settings.loanAmount() : McpToolSupport.amount(loanAmount),
                    loanReceivedOn == null ? settings.loanReceivedOn() : McpToolSupport.date(loanReceivedOn),
                    loanRepaidOn == null ? settings.loanRepaidOn() : McpToolSupport.date(loanRepaidOn)));
            return budgetService.settings(revivalId);
        });
    }

    @McpTool(name = "calculate_budget_scenario",
            description = "Work out what the result would be with other numbers: participants, the fee, ticket prices and counts, the venue's and the party's cost. Nothing is saved.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public ScenarioResultDto calculateBudgetScenario(
            @McpToolParam(description = "A revival whose budget you can read") final Long revivalId,
            @McpToolParam(description = "The numbers to try; leave out what stays as budgeted") final ScenarioParameters parameters) {
        return support.call("calculate_budget_scenario", () -> scenarioService.calculate(revivalId, parameters));
    }

    @McpTool(name = "list_budget_scenarios",
            description = "List a revival's saved budget scenarios with their numbers and results.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<BudgetDtos.Scenario> listBudgetScenarios(@McpToolParam(description = "A revival whose budget you can read") final Long revivalId) {
        return support.call("list_budget_scenarios", () -> new McpToolSupport.ListResult<>(scenarioService.list(revivalId)));
    }

    @McpTool(name = "save_budget_scenario",
            description = "Save a budget scenario under a name, or with a scenarioId replace a saved one.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public BudgetDtos.Scenario saveBudgetScenario(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(description = "Its name") final String name,
            @McpToolParam(description = "The numbers, as in calculate_budget_scenario") final ScenarioParameters parameters,
            @McpToolParam(required = false, description = "A saved scenario to replace, by id") final @Nullable Long scenarioId) {
        return support.call("save_budget_scenario", () -> scenarioId == null
                ? scenarioService.save(revivalId, name, parameters)
                : scenarioService.update(scenarioId, McpToolSupport.byId(scenarioService.list(revivalId), BudgetDtos.Scenario::id, scenarioId,
                BudgetScenario.class).version(), name, parameters));
    }

    @McpTool(name = "remove_budget_scenario",
            description = "Remove a saved budget scenario from the list. It stays in the history; nothing is deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpToolSupport.ListResult<BudgetDtos.Scenario> removeBudgetScenario(
            @McpToolParam(description = "A revival whose budget you can change") final Long revivalId,
            @McpToolParam(description = "The scenario, by id") final Long scenarioId) {
        return support.call("remove_budget_scenario", () -> {
            scenarioService.remove(scenarioId, McpToolSupport.byId(scenarioService.list(revivalId), BudgetDtos.Scenario::id, scenarioId,
                    BudgetScenario.class).version());
            return new McpToolSupport.ListResult<>(scenarioService.list(revivalId));
        });
    }

    @McpTool(name = "list_budget_categories",
            description = "List the budget categories: id, name, whether income or cost, and their role.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<BudgetCategoryDto> listBudgetCategories(
            @McpToolParam(required = false, description = "Also retired ones") final @Nullable Boolean includeRetired) {
        return support.call("list_budget_categories", () -> new McpToolSupport.ListResult<>(categoryService.list(Boolean.TRUE.equals(includeRetired)).stream()
                .map(BudgetCategoryDto::of).toList()));
    }

    @McpTool(name = "list_reference_revivals",
            description = "List earlier revivals' budget figures per category, anonymous, to compare with.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<ReferenceRevivalDto> listReferenceRevivals() {
        return support.call("list_reference_revivals", () -> new McpToolSupport.ListResult<>(referenceService.list()));
    }

    @McpTool(name = "create_reference_revival",
            description = "Super admins: add an earlier revival's figures: a label, year, participants, comment and budgeted and actual amounts per category.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public ReferenceRevivalDto createReferenceRevival(@McpToolParam(description = "The figures") final ReferenceRevivalService.Definition definition) {
        return support.call("create_reference_revival", () -> referenceService.create(definition));
    }

    @McpTool(name = "update_reference_revival",
            description = "Super admins: replace an earlier revival's figures.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ReferenceRevivalDto updateReferenceRevival(
            @McpToolParam(description = "The reference revival, by id") final Long referenceId,
            @McpToolParam(description = "The figures, as in create_reference_revival") final ReferenceRevivalService.Definition definition) {
        return support.call("update_reference_revival", () -> referenceService.update(referenceId, referenceService.get(referenceId).version(), definition));
    }

    private ResolvedPayer resolve(final Long revivalId, final @Nullable String paidBy) {
        if (paidBy == null || paidBy.isBlank()) {
            return new ResolvedPayer(null, LogMcpTools.Resolution.NONE);
        }
        return switch (ownerResolver.resolve(revivalId, paidBy, callerService.current())) {
            case OwnerResolver.Resolution.Member member ->
                    new ResolvedPayer(new OwnerInputDto(member.personId(), null), LogMcpTools.Resolution.MEMBER);
            case OwnerResolver.Resolution.FreeText name ->
                    new ResolvedPayer(new OwnerInputDto(null, name.name()), LogMcpTools.Resolution.FREE_TEXT);
            case OwnerResolver.Resolution.Ambiguous ambiguous ->
                    throw new BadRequestException("error.owner.ambiguous", paidBy.strip(), String.join(", ", ambiguous.candidates()));
        };
    }

    private record ResolvedPayer(@Nullable OwnerInputDto input, LogMcpTools.Resolution resolution) {
    }

    public record Position(String income, String cost, String result) {
    }

    public record Category(String id, ChecklistMcpTools.Text name, BudgetCategoryKind kind, String budgeted,
                           String actual) {
    }

    public record Loan(@Nullable String amount, @Nullable LocalDate receivedOn, @Nullable LocalDate repaidOn) {
    }

    /**
     * A budget as get_budget_summary returns it: amounts only, no payers.
     */
    public record BudgetSummary(Position budgeted, Position actual, List<Category> categories, Loan loan,
                                @Nullable Integer participantsPlanned,
                                int unreimbursedCount, String unreimbursed, int missingReceipts) {
    }

    /**
     * A transaction as list_transactions returns it: who paid by name, never bank details or the receipt itself.
     */
    public record TransactionView(Long id, LocalDate date, String amount, String categoryId, String description, @Nullable Long budgetLineId,
                                  @Nullable McpOwner paidBy, boolean reimbursed, boolean receipt, boolean cancelled) {
    }

    /**
     * A transaction as add_transaction returns it, with how the payer's name was read.
     */
    public record TransactionWritten(Long id, LocalDate date, String amount, ChecklistMcpTools.Text category,
                                     BudgetCategoryKind kind,
                                     String description, @Nullable McpOwner paidBy,
                                     LogMcpTools.Resolution paidByResolution) {
    }
}
