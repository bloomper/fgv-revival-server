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
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.budget.BudgetDtos.Budget;
import nu.fgv.revival.server.budget.BudgetDtos.CategorySummary;
import nu.fgv.revival.server.budget.BudgetDtos.Line;
import nu.fgv.revival.server.budget.BudgetDtos.Proposal;
import nu.fgv.revival.server.budget.BudgetDtos.Scenario;
import nu.fgv.revival.server.budget.BudgetDtos.Settings;
import nu.fgv.revival.server.budget.BudgetDtos.Totals;
import nu.fgv.revival.server.budget.BudgetDtos.Transaction;
import nu.fgv.revival.server.decisionlog.LogEntrySummaryDto;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.event.EventService;
import nu.fgv.revival.server.event.EventTypeDto;
import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.revival.RevivalDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A revival's budget over GraphQL (budget.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class BudgetGraphqlApi {

    private final BudgetService budgetService;
    private final TransactionService transactionService;
    private final BudgetScenarioService scenarioService;
    private final EventTypeBudgetLineService typeLineService;
    private final EventService eventService;
    private final OwnerViews ownerViews;

    public BudgetGraphqlApi(final BudgetService budgetService,
                            final TransactionService transactionService,
                            final BudgetScenarioService scenarioService,
                            final EventTypeBudgetLineService typeLineService,
                            final EventService eventService,
                            final OwnerViews ownerViews) {
        this.budgetService = budgetService;
        this.transactionService = transactionService;
        this.scenarioService = scenarioService;
        this.typeLineService = typeLineService;
        this.eventService = eventService;
        this.ownerViews = ownerViews;
    }

    private static @Nullable BigDecimal decimal(final @Nullable Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static EventTypeBudgetLineService.Definition definition(final EventTypeBudgetLineInput input) {
        return new EventTypeBudgetLineService.Definition(input.eventTypeId(), input.categoryId(), input.description().sv(),
                input.description().en(), input.quantityRule(), decimal(input.quantity()), input.unitAmount());
    }

    @SchemaMapping(typeName = "Revival", field = "budget")
    public @Nullable Budget budget(final RevivalDto revival) {
        return budgetService.budget(revival.id());
    }

    @SchemaMapping(typeName = "Budget", field = "settings")
    public Settings settings(final Budget budget) {
        return budgetService.settings(budget.revivalId());
    }

    @SchemaMapping(typeName = "Budget", field = "lines")
    public List<Line> lines(final Budget budget, @Argument final @Nullable Boolean includeRemoved) {
        return budgetService.lines(budget.revivalId(), Boolean.TRUE.equals(includeRemoved));
    }

    @SchemaMapping(typeName = "Budget", field = "transactions")
    public List<Transaction> transactions(final Budget budget, @Argument final @Nullable Boolean includeCancelled) {
        return transactionService.list(budget.revivalId(), Boolean.TRUE.equals(includeCancelled));
    }

    @SchemaMapping(typeName = "Budget", field = "categories")
    public List<CategorySummary> categories(final Budget budget) {
        return budgetService.categories(budget.revivalId());
    }

    @SchemaMapping(typeName = "Budget", field = "totals")
    public Totals totals(final Budget budget) {
        return budgetService.totals(budget.revivalId());
    }

    @SchemaMapping(typeName = "Budget", field = "proposals")
    public List<Proposal> proposals(final Budget budget) {
        return budgetService.proposals(budget.revivalId());
    }

    @SchemaMapping(typeName = "Budget", field = "scenarios")
    public List<Scenario> scenarios(final Budget budget) {
        return scenarioService.list(budget.revivalId());
    }

    @QueryMapping
    public ScenarioResultDto budgetScenarioCalculate(@Argument final Long revivalId, @Argument final ScenarioParameters input) {
        return scenarioService.calculate(revivalId, input);
    }

    @MutationMapping
    public Budget budgetSettingsUpdate(@Argument final BudgetSettingsInput input) {
        budgetService.updateSettings(new BudgetService.SettingsChange(input.revivalId(), input.version(), input.participantsPlanned(),
                input.participationFee(), input.loanAmount(), input.loanReceivedOn(), input.loanRepaidOn()));
        return new Budget(input.revivalId());
    }

    @MutationMapping
    public Line budgetLineCreate(@Argument final BudgetLineCreateInput input) {
        return budgetService.createLine(new BudgetService.NewLine(input.revivalId(), input.categoryId(), input.description(), decimal(input.quantity()),
                input.unitAmount(), input.eventId(), input.logEntryId(), input.comment(), input.eventTypeLineId()));
    }

    @MutationMapping
    public Line budgetLineUpdate(@Argument final BudgetLineUpdateInput input) {
        return budgetService.updateLine(new BudgetService.LineChange(input.id(), input.version(), input.categoryId(), input.description(),
                decimal(input.quantity()), input.unitAmount(), input.eventId(), Boolean.TRUE.equals(input.clearEvent()), input.logEntryId(),
                Boolean.TRUE.equals(input.clearLogEntry()), input.comment(), Boolean.TRUE.equals(input.clearComment())));
    }

    @MutationMapping
    public Line budgetLineRemove(@Argument final Long id, @Argument final Long version) {
        return budgetService.removeLine(id, version);
    }

    @MutationMapping
    public Line budgetLineRestore(@Argument final Long id, @Argument final Long version) {
        return budgetService.restoreLine(id, version);
    }

    @BatchMapping(typeName = "BudgetLine", field = "event")
    public Map<Line, EventDto> lineEvent(final List<Line> lines) {
        final Map<Long, EventDto> events = new HashMap<>();
        final Map<Line, EventDto> result = new HashMap<>();

        lines.stream().filter(line -> line.eventId() != null).map(Line::revivalId).distinct()
                .forEach(revivalId -> eventService.list(revivalId, true).forEach(event -> events.put(event.id(), event)));
        lines.stream().filter(line -> line.eventId() != null && events.containsKey(line.eventId()))
                .forEach(line -> result.put(line, events.get(line.eventId())));
        return result;
    }

    @BatchMapping(typeName = "BudgetLine", field = "logEntry")
    public Map<Line, LogEntrySummaryDto> lineLogEntry(final List<Line> lines) {
        final Map<Long, LogEntrySummaryDto> decisions = budgetService.decisions(lines.stream().map(Line::logEntryId).filter(Objects::nonNull).toList());
        final Map<Line, LogEntrySummaryDto> result = new HashMap<>();

        lines.stream().filter(line -> line.logEntryId() != null && decisions.containsKey(line.logEntryId()))
                .forEach(line -> result.put(line, decisions.get(line.logEntryId())));
        return result;
    }

    @MutationMapping
    public Transaction transactionCreate(@Argument final TransactionCreateInput input) {
        return transactionService.create(new TransactionService.NewTransaction(input.revivalId(), input.date(), input.amount(), input.categoryId(),
                input.description(), input.budgetLineId(), input.paidBy()));
    }

    @MutationMapping
    public Transaction transactionUpdate(@Argument final TransactionUpdateInput input) {
        return transactionService.update(new TransactionService.TransactionChange(input.id(), input.version(), input.date(), input.amount(),
                input.categoryId(), input.description(), input.budgetLineId(), Boolean.TRUE.equals(input.clearBudgetLine()), input.paidBy(),
                Boolean.TRUE.equals(input.clearPaidBy())));
    }

    @MutationMapping
    public Transaction transactionCancel(@Argument final Long id, @Argument final Long version) {
        return transactionService.cancel(id, version);
    }

    @MutationMapping
    public Transaction transactionSetReimbursed(@Argument final Long id, @Argument final Long version, @Argument final boolean reimbursed) {
        return transactionService.setReimbursed(id, version, reimbursed);
    }

    @BatchMapping(typeName = "Transaction", field = "budgetLine")
    public Map<Transaction, Line> transactionLine(final List<Transaction> transactions) {
        final Map<Long, Line> lines = budgetService.linesById(transactions.stream().map(Transaction::budgetLineId).filter(Objects::nonNull).toList());
        final Map<Transaction, Line> result = new HashMap<>();

        transactions.stream().filter(transaction -> transaction.budgetLineId() != null && lines.containsKey(transaction.budgetLineId()))
                .forEach(transaction -> result.put(transaction, lines.get(transaction.budgetLineId())));
        return result;
    }

    /**
     * Per transaction, since batch loaders do not get the request's language; a budget has tens of transactions.
     */
    @SchemaMapping(typeName = "Transaction", field = "paidBy")
    public @Nullable OwnerDto paidBy(final Transaction transaction, final Locale locale) {
        return transaction.payer().isSet() ? ownerViews.toDto(transaction.revivalId(), transaction.payer(), locale) : null;
    }

    @MutationMapping
    public Scenario budgetScenarioSave(@Argument final BudgetScenarioSaveInput input) {
        return scenarioService.save(input.revivalId(), input.name(), input.parameters());
    }

    @MutationMapping
    public Scenario budgetScenarioUpdate(@Argument final Long id, @Argument final Long version, @Argument final BudgetScenarioSaveInput input) {
        return scenarioService.update(id, version, input.name(), input.parameters());
    }

    @MutationMapping
    public void budgetScenarioRemove(@Argument final Long id, @Argument final Long version) {
        scenarioService.remove(id, version);
    }

    @BatchMapping(typeName = "EventType", field = "budgetLines")
    public Map<EventTypeDto, List<EventTypeBudgetLineDto>> eventTypeBudgetLines(final List<EventTypeDto> types) {
        final Map<Long, List<EventTypeBudgetLineDto>> lines = typeLineService.byTypes(types.stream().map(EventTypeDto::id).toList());

        return types.stream().distinct().collect(Collectors.toMap(Function.identity(), type -> lines.getOrDefault(type.id(), List.of())));
    }

    @MutationMapping
    public EventTypeDto eventTypeBudgetLineCreate(@Argument final EventTypeBudgetLineInput input) {
        return typeLineService.create(definition(input));
    }

    @MutationMapping
    public EventTypeDto eventTypeBudgetLineUpdate(@Argument final Long id, @Argument final Long version, @Argument final EventTypeBudgetLineInput input) {
        return typeLineService.update(id, version, definition(input));
    }

    @MutationMapping
    public EventTypeDto eventTypeBudgetLineRemove(@Argument final Long id, @Argument final Long version) {
        return typeLineService.remove(id, version);
    }

    public record BudgetSettingsInput(Long revivalId, Long version, @Nullable Integer participantsPlanned,
                                      @Nullable BigDecimal participationFee,
                                      @Nullable BigDecimal loanAmount, @Nullable LocalDate loanReceivedOn,
                                      @Nullable LocalDate loanRepaidOn) {
    }

    public record BudgetLineCreateInput(Long revivalId, String categoryId, String description,
                                        @Nullable Double quantity, BigDecimal unitAmount,
                                        @Nullable Long eventId, @Nullable Long logEntryId, @Nullable String comment,
                                        @Nullable Long eventTypeLineId) {
    }

    public record BudgetLineUpdateInput(Long id, Long version, @Nullable String categoryId,
                                        @Nullable String description, @Nullable Double quantity,
                                        @Nullable BigDecimal unitAmount, @Nullable Long eventId,
                                        @Nullable Boolean clearEvent,
                                        @Nullable Long logEntryId, @Nullable Boolean clearLogEntry,
                                        @Nullable String comment,
                                        @Nullable Boolean clearComment) {
    }

    public record TransactionCreateInput(Long revivalId, LocalDate date, BigDecimal amount, String categoryId,
                                         String description,
                                         @Nullable Long budgetLineId, @Nullable OwnerInputDto paidBy) {
    }

    public record TransactionUpdateInput(Long id, Long version, @Nullable LocalDate date, @Nullable BigDecimal amount,
                                         @Nullable String categoryId,
                                         @Nullable String description, @Nullable Long budgetLineId,
                                         @Nullable Boolean clearBudgetLine,
                                         @Nullable OwnerInputDto paidBy, @Nullable Boolean clearPaidBy) {
    }

    public record BudgetScenarioSaveInput(@Nullable Long revivalId, String name, ScenarioParameters parameters) {
    }

    public record EventTypeBudgetLineInput(@Nullable Long eventTypeId, String categoryId,
                                           LocalizedTextInput description,
                                           BudgetLineQuantity quantityRule, @Nullable Double quantity,
                                           BigDecimal unitAmount) {
    }
}
