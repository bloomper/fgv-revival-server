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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.budget.BudgetDtos.CategorySummary;
import nu.fgv.revival.server.budget.BudgetDtos.Line;
import nu.fgv.revival.server.budget.BudgetDtos.Proposal;
import nu.fgv.revival.server.budget.BudgetDtos.ReferenceFigureView;
import nu.fgv.revival.server.budget.BudgetDtos.Settings;
import nu.fgv.revival.server.budget.BudgetDtos.Totals;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryRepository;
import nu.fgv.revival.server.decisionlog.LogEntrySummaryDto;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.event.EventService;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalCreated;
import nu.fgv.revival.server.staffing.HelperCompensation;
import nu.fgv.revival.server.util.Amounts;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A revival's budget (product spec 5.10, M5-FR-4, FR-7–FR-9): its settings, budget lines, totals per kind and per
 * category with reference figures, and the lines its events' types propose. Readers are super admins, observers and
 * its directeurs and steering group; writers its directeurs and the steering group members with the finance area.
 * Lines are removed, never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Service
public class BudgetService {

    static final BigDecimal MIN_QUANTITY = new BigDecimal("0.01");
    static final BigDecimal MAX_QUANTITY = new BigDecimal("100000");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");
    static final String MAX_AMOUNT_TEXT = "10000000";

    private final BudgetSettingsRepository settingsRepository;
    private final BudgetLineRepository lineRepository;
    private final BudgetTransactionRepository transactionRepository;
    private final EventTypeBudgetLineRepository typeLineRepository;
    private final BudgetCategoryService categoryService;
    private final ReferenceRevivalService referenceService;
    private final EventRepository eventRepository;
    private final EventService eventService;
    private final LogEntryRepository logEntryRepository;
    private final CallerService callerService;
    private final BudgetAccess access;
    private final HelperCompensation helperCompensation;
    private final Clock clock;

    public BudgetService(final BudgetSettingsRepository settingsRepository,
                         final BudgetLineRepository lineRepository,
                         final BudgetTransactionRepository transactionRepository,
                         final EventTypeBudgetLineRepository typeLineRepository,
                         final BudgetCategoryService categoryService,
                         final ReferenceRevivalService referenceService,
                         final EventRepository eventRepository,
                         final EventService eventService,
                         final LogEntryRepository logEntryRepository,
                         final CallerService callerService,
                         final BudgetAccess access,
                         final HelperCompensation helperCompensation,
                         final Clock clock) {
        this.settingsRepository = settingsRepository;
        this.lineRepository = lineRepository;
        this.transactionRepository = transactionRepository;
        this.typeLineRepository = typeLineRepository;
        this.categoryService = categoryService;
        this.referenceService = referenceService;
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.logEntryRepository = logEntryRepository;
        this.callerService = callerService;
        this.access = access;
        this.helperCompensation = helperCompensation;
        this.clock = clock;
    }

    private static @Nullable BudgetCategoryKind kind(final Map<String, BudgetCategory> categories, final String categoryId) {
        final BudgetCategory category = categories.get(categoryId);

        return category == null ? null : category.getKind();
    }

    private static List<ReferenceFigureView> referenceFigures(final List<ReferenceRevivalDto> references, final String categoryId) {
        final List<ReferenceFigureView> views = new ArrayList<>();

        for (final ReferenceRevivalDto reference : references) {
            reference.figures().stream().filter(figure -> figure.categoryId().equals(categoryId)).findFirst().ifPresent(figure ->
                    views.add(new ReferenceFigureView(reference.label(), reference.year(), reference.participants(), figure.budgeted(),
                            figure.actual(), perParticipant(figure.budgeted(), reference.participants()),
                            perParticipant(figure.actual(), reference.participants()))));
        }
        return views;
    }

    /**
     * In whole kronor.
     */
    private static @Nullable BigDecimal perParticipant(final @Nullable BigDecimal amount, final @Nullable Integer participants) {
        return amount == null || participants == null || participants <= 0 ? null
                : amount.divide(BigDecimal.valueOf(participants), 0, RoundingMode.HALF_UP).setScale(Amounts.SCALE, RoundingMode.UNNECESSARY);
    }

    // --- Budget -----------------------------------------------------------------------------------------------------

    static String validDescription(final @Nullable String description) {
        final String trimmed = description == null ? "" : description.strip();

        if (trimmed.isEmpty() || trimmed.length() > BudgetLine.DESCRIPTION_MAX_LENGTH) {
            throw new BadRequestException("error.budget.description", BudgetLine.DESCRIPTION_MAX_LENGTH);
        }
        return trimmed;
    }

    static BigDecimal validQuantity(final BigDecimal quantity) {
        final @Nullable BigDecimal exact = Amounts.exact(quantity);

        if (exact == null || exact.compareTo(MIN_QUANTITY) < 0 || exact.compareTo(MAX_QUANTITY) > 0) {
            throw new BadRequestException("error.budget.quantity", MAX_QUANTITY.toPlainString());
        }
        return exact;
    }

    /**
     * Between {@code min} and {@value #MAX_AMOUNT_TEXT} kronor, at most two decimals; null stays null.
     */
    static @Nullable BigDecimal validAmount(final @Nullable BigDecimal amount, final BigDecimal min) {
        if (amount == null) {
            return null;
        }

        final @Nullable BigDecimal exact = Amounts.exact(amount);

        if (exact == null || exact.compareTo(min) < 0 || exact.compareTo(MAX_AMOUNT) > 0) {
            throw new BadRequestException("error.budget.amount", Amounts.format(min), MAX_AMOUNT_TEXT);
        }
        return exact;
    }

    // --- Settings ---------------------------------------------------------------------------------------------------

    private static @Nullable String validComment(final @Nullable String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }

        final String trimmed = comment.strip();

        if (trimmed.length() > BudgetLine.COMMENT_MAX_LENGTH) {
            throw new BadRequestException("error.budget.comment.tooLong", BudgetLine.COMMENT_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * The revival's budget, or null when the caller may not read it: a revival field that does not fail (M5-FR-6).
     */
    @Transactional(readOnly = true)
    public BudgetDtos.@Nullable Budget budget(final Long revivalId) {
        return access.canRead(callerService.current(), revivalId) ? new BudgetDtos.Budget(revivalId) : null;
    }

    /**
     * Whether the caller reads the revival's budget.
     */
    @Transactional(readOnly = true)
    public boolean canRead(final Long revivalId) {
        return access.canRead(callerService.current(), revivalId);
    }

    // --- Lines ------------------------------------------------------------------------------------------------------

    /**
     * The decision log rows lines refer to, by id. No caller check: callers have checked the lines.
     */
    @Transactional(readOnly = true)
    public Map<Long, LogEntrySummaryDto> decisions(final Collection<Long> ids) {
        return ids.isEmpty() ? Map.of()
                : logEntryRepository.findAllById(Set.copyOf(ids)).stream().collect(Collectors.toMap(LogEntry::getId, LogEntrySummaryDto::of));
    }

    /**
     * Every revival gets its settings when it is created.
     */
    @EventListener
    public void revivalCreated(final RevivalCreated event) {
        if (settingsRepository.findByRevivalId(event.revivalId()).isEmpty()) {
            settingsRepository.saveAndFlush(new BudgetSettings(event.revivalId()));
        }
    }

    @Transactional(readOnly = true)
    public Settings settings(final Long revivalId) {
        access.readable(callerService.current(), revivalId);
        return Settings.of(settingsRepository.findByRevivalId(revivalId).orElse(null));
    }

    /**
     * Replaces the settings. A revival made without them (sample data) gets them here, from version 0.
     */
    @Transactional
    public Settings updateSettings(final SettingsChange change) {
        access.writable(callerService.current(), change.revivalId());

        final BudgetSettings settings = settingsRepository.findByRevivalId(change.revivalId()).orElseGet(() -> new BudgetSettings(change.revivalId()));
        final long current = settings.getVersion() == null ? 0L : settings.getVersion();

        if (!Objects.equals(change.version(), current)) {
            throw new ConflictException("error.conflict");
        }
        if (change.participantsPlanned() != null && (change.participantsPlanned() < 0 || change.participantsPlanned() > BudgetSettings.MAX_PARTICIPANTS)) {
            throw new BadRequestException("error.budget.participants", BudgetSettings.MAX_PARTICIPANTS);
        }
        if (change.loanReceivedOn() != null && change.loanRepaidOn() != null && change.loanRepaidOn().isBefore(change.loanReceivedOn())) {
            throw new BadRequestException("error.budget.loan.repaidBeforeReceived");
        }
        AuditContext.operation("budgetSettingsUpdate");
        settings.setParticipantsPlanned(change.participantsPlanned());
        settings.setParticipationFee(validAmount(change.participationFee(), BigDecimal.ZERO));
        settings.setLoanAmount(validAmount(change.loanAmount(), BigDecimal.ZERO));
        settings.setLoanReceivedOn(change.loanReceivedOn());
        settings.setLoanRepaidOn(change.loanRepaidOn());
        return Settings.of(settingsRepository.saveAndFlush(settings));
    }

    /**
     * By category order, then id.
     */
    @Transactional(readOnly = true)
    public List<Line> lines(final Long revivalId, final boolean includeRemoved) {
        access.readable(callerService.current(), revivalId);

        final Map<String, Integer> positions = positions();

        return lineRepository.findByRevivalId(revivalId).stream()
                .filter(line -> includeRemoved || !line.isRemoved())
                .sorted(Comparator.comparing((BudgetLine line) -> positions.getOrDefault(line.getCategoryId(), Integer.MAX_VALUE))
                        .thenComparing(BudgetLine::getId))
                .map(Line::of)
                .toList();
    }

    /**
     * Lines by id, for the transactions that refer to them. No caller check: callers have checked the transactions.
     */
    @Transactional(readOnly = true)
    public Map<Long, Line> linesById(final Collection<Long> ids) {
        return ids.isEmpty() ? Map.of()
                : lineRepository.findAllById(Set.copyOf(ids)).stream().collect(Collectors.toMap(BudgetLine::getId, Line::of));
    }

    // --- Overview ---------------------------------------------------------------------------------------------------

    @Transactional
    public Line createLine(final NewLine newLine) {
        final Caller caller = callerService.current();

        access.writable(caller, newLine.revivalId());
        categoryService.requireAssignable(List.of(newLine.categoryId()), List.of());

        final BudgetLine line = new BudgetLine();

        line.setRevivalId(newLine.revivalId());
        line.setCategoryId(newLine.categoryId());
        line.setDescription(validDescription(newLine.description()));
        line.setQuantity(validQuantity(newLine.quantity() == null ? BigDecimal.ONE : newLine.quantity()));
        line.setUnitAmount(Objects.requireNonNull(validAmount(newLine.unitAmount(), BigDecimal.ZERO)));
        line.setEventId(validEvent(newLine.revivalId(), newLine.eventId()));
        line.setLogEntryId(validDecision(newLine.revivalId(), newLine.logEntryId()));
        line.setComment(validComment(newLine.comment()));
        if (newLine.eventTypeLineId() != null) {
            if (newLine.eventId() == null) {
                throw new BadRequestException("error.budget.line.proposalWithoutEvent");
            }
            validProposal(newLine.eventId(), newLine.eventTypeLineId());
            requireNotAdded(newLine.revivalId(), newLine.eventId(), newLine.eventTypeLineId(), null);
            line.setEventTypeLineId(newLine.eventTypeLineId());
        }
        AuditContext.operation("budgetLineCreate");
        return Line.of(lineRepository.saveAndFlush(line));
    }

    /**
     * A line keeps a retired category it already has. Moving it to another event, or off its event, makes it an
     * ordinary line, so the event type's proposal for its event shows again.
     */
    @Transactional
    public Line updateLine(final LineChange change) {
        final BudgetLine line = changeable(callerService.current(), change.id(), change.version());

        if (line.isRemoved()) {
            throw new BadRequestException("error.budget.line.removed");
        }
        AuditContext.operation("budgetLineUpdate");
        if (change.categoryId() != null) {
            categoryService.requireAssignable(List.of(change.categoryId()), List.of(line.getCategoryId()));
            line.setCategoryId(change.categoryId());
        }
        if (change.description() != null) {
            line.setDescription(validDescription(change.description()));
        }
        if (change.quantity() != null) {
            line.setQuantity(validQuantity(change.quantity()));
        }
        if (change.unitAmount() != null) {
            line.setUnitAmount(Objects.requireNonNull(validAmount(change.unitAmount(), BigDecimal.ZERO)));
        }
        if (change.clearEvent()) {
            line.setEventId(null);
            line.setEventTypeLineId(null);
        } else if (change.eventId() != null && !change.eventId().equals(line.getEventId())) {
            line.setEventId(validEvent(line.getRevivalId(), change.eventId()));
            line.setEventTypeLineId(null);
        }
        if (change.clearLogEntry()) {
            line.setLogEntryId(null);
        } else if (change.logEntryId() != null) {
            line.setLogEntryId(validDecision(line.getRevivalId(), change.logEntryId()));
        }
        if (change.clearComment()) {
            line.setComment(null);
        } else if (change.comment() != null) {
            line.setComment(validComment(change.comment()));
        }
        return Line.of(lineRepository.saveAndFlush(line));
    }

    @Transactional
    public Line removeLine(final Long id, final Long version) {
        final Caller caller = callerService.current();
        final BudgetLine line = changeable(caller, id, version);

        if (line.isRemoved()) {
            throw new BadRequestException("error.budget.line.removed");
        }
        AuditContext.operation("budgetLineRemove");
        line.setRemovedAt(clock.instant());
        line.setRemovedBy(caller.person().getId());
        return Line.of(lineRepository.saveAndFlush(line));
    }

    @Transactional
    public Line restoreLine(final Long id, final Long version) {
        final BudgetLine line = changeable(callerService.current(), id, version);

        if (!line.isRemoved()) {
            throw new BadRequestException("error.budget.line.notRemoved");
        }
        if (line.getEventId() != null && line.getEventTypeLineId() != null) {
            requireNotAdded(line.getRevivalId(), line.getEventId(), line.getEventTypeLineId(), line.getId());
        }
        AuditContext.operation("budgetLineRestore");
        line.setRemovedAt(null);
        line.setRemovedBy(null);
        return Line.of(lineRepository.saveAndFlush(line));
    }

    /**
     * Budgeted from the lines that are not removed, actual from the transactions that are not cancelled (M5-FR-7). The
     * loan is neither income nor cost. The helpers' compensation for the shifts they hold is a budgeted cost too, see
     * {@link #helperCompensation(Long)}.
     */
    @Transactional(readOnly = true)
    public Totals totals(final Long revivalId) {
        access.readable(callerService.current(), revivalId);
        return totalsOf(revivalId);
    }

    /**
     * As {@link #totals}, without a caller check, for MCP and the export, which have checked.
     */
    @Transactional(readOnly = true)
    public Totals totalsOf(final Long revivalId) {
        final Map<String, BudgetCategory> categories = categoryService.all().stream()
                .collect(Collectors.toMap(BudgetCategory::getId, Function.identity()));
        final List<BudgetLine> lines = lineRepository.findByRevivalId(revivalId).stream().filter(line -> !line.isRemoved()).toList();
        final List<BudgetTransaction> transactions = transactionRepository.findByRevivalId(revivalId).stream()
                .filter(transaction -> !transaction.isCancelled()).toList();
        final BigDecimal budgetedIncome = Amounts.round(lines.stream().filter(line -> kind(categories, line.getCategoryId()) == BudgetCategoryKind.INCOME)
                .map(BudgetLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        final BigDecimal budgetedCost = Amounts.round(lines.stream().filter(line -> kind(categories, line.getCategoryId()) == BudgetCategoryKind.COST)
                .map(BudgetLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add).add(helperCompensation(revivalId)));
        final BigDecimal actualIncome = Amounts.round(transactions.stream()
                .filter(transaction -> kind(categories, transaction.getCategoryId()) == BudgetCategoryKind.INCOME)
                .map(BudgetTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        final BigDecimal actualCost = Amounts.round(transactions.stream()
                .filter(transaction -> kind(categories, transaction.getCategoryId()) == BudgetCategoryKind.COST)
                .map(BudgetTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        final List<BudgetTransaction> unreimbursed = transactions.stream()
                .filter(transaction -> kind(categories, transaction.getCategoryId()) == BudgetCategoryKind.COST
                        && transaction.paidBy().isSet() && !transaction.isReimbursed())
                .toList();

        return new Totals(budgetedIncome, budgetedCost, budgetedIncome.subtract(budgetedCost), actualIncome, actualCost,
                actualIncome.subtract(actualCost),
                Amounts.round(unreimbursed.stream().map(BudgetTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)),
                unreimbursed.size(), (int) transactions.stream().filter(transaction -> !transaction.hasReceipt()).count());
    }

    /**
     * Every category with a line or a transaction, and every active category with a role, in category order, with the
     * reference figures for it (M5-FR-8).
     */
    @Transactional(readOnly = true)
    public List<CategorySummary> categories(final Long revivalId) {
        access.readable(callerService.current(), revivalId);
        return categoriesOf(revivalId);
    }

    @Transactional(readOnly = true)
    List<CategorySummary> categoriesOf(final Long revivalId) {
        final Map<String, BigDecimal> budgeted = new LinkedHashMap<>();
        final Map<String, BigDecimal> actual = new LinkedHashMap<>();

        lineRepository.findByRevivalId(revivalId).stream().filter(line -> !line.isRemoved())
                .forEach(line -> budgeted.merge(line.getCategoryId(), line.amount(), BigDecimal::add));
        transactionRepository.findByRevivalId(revivalId).stream().filter(transaction -> !transaction.isCancelled())
                .forEach(transaction -> actual.merge(transaction.getCategoryId(), transaction.getAmount(), BigDecimal::add));

        final BigDecimal helpers = helperCompensation(revivalId);
        final String helpersCategory = helpersCategoryId();

        if (helpers.signum() != 0 && helpersCategory != null) {
            budgeted.merge(helpersCategory, helpers, BigDecimal::add);
        }

        final List<ReferenceRevivalDto> references = referenceService.all();
        final List<CategorySummary> summaries = new ArrayList<>();

        for (final BudgetCategory category : categoryService.all()) {
            final boolean held = budgeted.containsKey(category.getId()) || actual.containsKey(category.getId());

            if (held || !category.isRetired() && category.getRole() != null) {
                summaries.add(new CategorySummary(BudgetCategoryDto.of(category), Amounts.round(budgeted.getOrDefault(category.getId(), BigDecimal.ZERO)),
                        Amounts.round(actual.getOrDefault(category.getId(), BigDecimal.ZERO)), referenceFigures(references, category.getId())));
            }
        }
        return summaries;
    }

    /**
     * For each event that is not cancelled, the lines its type proposes that have not been added for it (M5-FR-9).
     */
    @Transactional(readOnly = true)
    public List<Proposal> proposals(final Long revivalId) {
        access.readable(callerService.current(), revivalId);

        final List<EventDto> events = eventService.list(revivalId, false).stream().filter(event -> event.typeId() != null).toList();
        final Map<Long, List<EventTypeBudgetLine>> typeLines = typeLineRepository
                .findByEventTypeIdInOrderByPositionAscIdAsc(events.stream().map(EventDto::typeId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.groupingBy(EventTypeBudgetLine::getEventTypeId));
        final Set<String> added = lineRepository.findByRevivalId(revivalId).stream()
                .filter(line -> !line.isRemoved() && line.getEventId() != null && line.getEventTypeLineId() != null)
                .map(line -> line.getEventId() + "/" + line.getEventTypeLineId())
                .collect(Collectors.toSet());
        final @Nullable Integer participants = settingsRepository.findByRevivalId(revivalId).map(BudgetSettings::getParticipantsPlanned).orElse(null);
        final List<Proposal> proposals = new ArrayList<>();

        for (final EventDto event : events) {
            for (final EventTypeBudgetLine typeLine : typeLines.getOrDefault(event.typeId(), List.of())) {
                if (added.contains(event.id() + "/" + typeLine.getId())) {
                    continue;
                }

                final @Nullable BigDecimal quantity = typeLine.getQuantityRule() == BudgetLineQuantity.FIXED ? typeLine.getQuantity()
                        : participants == null ? null : BigDecimal.valueOf(participants);

                proposals.add(new Proposal(event, EventTypeBudgetLineDto.of(typeLine), quantity == null ? null : quantity.doubleValue(),
                        quantity == null ? null : Amounts.times(quantity, typeLine.getUnitAmount())));
            }
        }
        return proposals;
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    /**
     * The lines that are not removed, as the scenario calculator reads them, and the settings it starts from.
     */
    @Transactional(readOnly = true)
    Baseline baseline(final Long revivalId) {
        final Map<String, BudgetCategory> categories = categoryService.all().stream()
                .collect(Collectors.toMap(BudgetCategory::getId, Function.identity()));
        final @Nullable BudgetSettings settings = settingsRepository.findByRevivalId(revivalId).orElse(null);

        final List<ScenarioCalculator.Line> lines = new ArrayList<>(lineRepository.findByRevivalId(revivalId).stream()
                .filter(line -> !line.isRemoved() && categories.containsKey(line.getCategoryId()))
                .map(line -> new ScenarioCalculator.Line(categories.get(line.getCategoryId()).getKind(), categories.get(line.getCategoryId()).getRole(),
                        line.getQuantity(), line.amount()))
                .toList());
        final BigDecimal helpers = helperCompensation(revivalId);

        // The helpers' compensation from the staffing is a cost of the helper category, as if it were one more line
        if (helpers.signum() != 0) {
            lines.add(new ScenarioCalculator.Line(BudgetCategoryKind.COST, BudgetCategoryRole.HELPERS, BigDecimal.ONE, helpers));
        }
        return new Baseline(List.copyOf(lines),
                settings == null ? null : settings.getParticipantsPlanned(),
                settings == null ? null : settings.getParticipationFee());
    }

    /**
     * What the revival's helpers are paid for the shifts they hold (M6B-FR-5): a budgeted cost of the category with the
     * {@code HELPERS} role, computed from the staffing whenever the budget is read and never stored as a line. The
     * totals, the category summary, the scenario calculator and the Excel export all add it.
     */
    BigDecimal helperCompensation(final Long revivalId) {
        return helperCompensation.total(revivalId);
    }

    /**
     * The active category with the {@code HELPERS} role, which the helpers' compensation belongs to; null without one.
     */
    @Nullable String helpersCategoryId() {
        return categoryService.all().stream()
                .filter(category -> !category.isRetired() && category.getRole() == BudgetCategoryRole.HELPERS)
                .map(BudgetCategory::getId)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Integer> positions() {
        return categoryService.all().stream().collect(Collectors.toMap(BudgetCategory::getId, BudgetCategory::getPosition));
    }

    private BudgetLine changeable(final Caller caller, final Long id, final Long version) {
        final BudgetLine line = lineRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(BudgetLine.class, id));

        if (!access.canRead(caller, line.getRevivalId())) {
            throw new ResourceNotFoundException(BudgetLine.class, id);
        }
        access.writable(caller, line.getRevivalId());
        if (!Objects.equals(version, line.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return line;
    }

    /**
     * A line from a proposal exists once per event while it is not removed.
     */
    private void requireNotAdded(final Long revivalId, final Long eventId, final Long eventTypeLineId, final @Nullable Long except) {
        if (lineRepository.findByRevivalId(revivalId).stream().anyMatch(other -> !other.isRemoved() && !other.getId().equals(except)
                && eventId.equals(other.getEventId()) && eventTypeLineId.equals(other.getEventTypeLineId()))) {
            throw new BadRequestException("error.budget.line.proposalAdded");
        }
    }

    /**
     * The event type's line exists and belongs to the event's type.
     */
    private void validProposal(final Long eventId, final Long eventTypeLineId) {
        final EventTypeBudgetLine typeLine = typeLineRepository.findById(eventTypeLineId)
                .orElseThrow(() -> new BadRequestException("error.budget.line.proposalUnknown"));
        final @Nullable Long typeId = eventRepository.findById(eventId).map(Event::getTypeId).orElse(null);

        if (!typeLine.getEventTypeId().equals(typeId)) {
            throw new BadRequestException("error.budget.line.proposalUnknown");
        }
    }

    private @Nullable Long validEvent(final Long revivalId, final @Nullable Long eventId) {
        if (eventId == null) {
            return null;
        }
        if (eventRepository.findById(eventId).filter(event -> event.getRevivalId().equals(revivalId)).isEmpty()) {
            throw new BadRequestException("error.budget.line.event");
        }
        return eventId;
    }

    /**
     * A DECISION row of the same revival.
     */
    private @Nullable Long validDecision(final Long revivalId, final @Nullable Long logEntryId) {
        if (logEntryId == null) {
            return null;
        }

        final @Nullable LogEntry entry = logEntryRepository.findById(logEntryId).orElse(null);

        if (entry == null || !entry.getRevivalId().equals(revivalId) || entry.getType() != LogEntryType.DECISION) {
            throw new BadRequestException("error.budget.line.decision");
        }
        return logEntryId;
    }

    /**
     * The settings as a whole: a null value clears it.
     */
    public record SettingsChange(Long revivalId, Long version, @Nullable Integer participantsPlanned,
                                 @Nullable BigDecimal participationFee,
                                 @Nullable BigDecimal loanAmount, @Nullable LocalDate loanReceivedOn,
                                 @Nullable LocalDate loanRepaidOn) {
    }

    /**
     * A new line. Quantity defaults to 1. A line from a proposal names the event type's line and the event.
     */
    public record NewLine(Long revivalId, String categoryId, String description, @Nullable BigDecimal quantity,
                          BigDecimal unitAmount,
                          @Nullable Long eventId, @Nullable Long logEntryId, @Nullable String comment,
                          @Nullable Long eventTypeLineId) {
    }

    /**
     * Fields left null are unchanged; the {@code clear*} flags remove a value.
     */
    public record LineChange(Long id, Long version, @Nullable String categoryId, @Nullable String description,
                             @Nullable BigDecimal quantity,
                             @Nullable BigDecimal unitAmount, @Nullable Long eventId, boolean clearEvent,
                             @Nullable Long logEntryId,
                             boolean clearLogEntry, @Nullable String comment, boolean clearComment) {
    }

    record Baseline(List<ScenarioCalculator.Line> lines, @Nullable Integer participantsPlanned,
                    @Nullable BigDecimal participationFee) {
    }
}
