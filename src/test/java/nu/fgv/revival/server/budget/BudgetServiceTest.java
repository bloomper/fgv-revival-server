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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.budget.BudgetDtos.CategorySummary;
import nu.fgv.revival.server.budget.BudgetDtos.Line;
import nu.fgv.revival.server.budget.BudgetDtos.Proposal;
import nu.fgv.revival.server.budget.BudgetDtos.Totals;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryRepository;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.event.EventService;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalCreated;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.settings.ApplicationSettingsService;
import nu.fgv.revival.server.staffing.HelperCompensation;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A revival's budget: who reads and writes it, the rules for lines, the totals, the categories
 * with reference figures, and the proposals from events.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 11, 1);
    private static final long OTHER_REVIVAL = 2L;
    private static final long REHEARSAL_TYPE = 30L;
    private final List<BudgetCategory> categories = new ArrayList<>(TestBudgetCategories.seeded());
    private final List<BudgetLine> lines = new ArrayList<>();
    private final List<BudgetTransaction> transactions = new ArrayList<>();
    @Mock
    private BudgetSettingsRepository settingsRepository;
    @Mock
    private BudgetLineRepository lineRepository;
    @Mock
    private BudgetTransactionRepository transactionRepository;
    @Mock
    private EventTypeBudgetLineRepository typeLineRepository;
    @Mock
    private BudgetCategoryRepository categoryRepository;
    @Mock
    private ReferenceRevivalService referenceService;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private EventService eventService;
    @Mock
    private LogEntryRepository logEntryRepository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private HelperCompensation helperCompensation;
    @Mock
    private ApplicationSettingsService applicationSettings;
    private BudgetService service;
    private BigDecimal helpers = BigDecimal.ZERO;
    private BudgetSettings settings;
    private Revival revival;

    private static Caller steeringGroupWithout() {
        return new Caller(TestCallers.steeringGroup().person(), Set.of(),
                Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.STEERING_GROUP, Set.of("CATERING"))));
    }

    private static BigDecimal kr(final String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private static BudgetService.NewLine newLine(final String category, final @Nullable Long eventId, final @Nullable Long logEntryId,
                                                 final @Nullable Long typeLineId) {
        return new BudgetService.NewLine(REVIVAL_ID, category, "  Mat  ", null, kr("120"), eventId, logEntryId, " ", typeLineId);
    }

    private static EventDto eventDto(final long id, final @Nullable Long typeId) {
        return new EventDto(id, REVIVAL_ID, typeId, "Rephelg 1", LocalDate.of(2027, 12, 11), LocalDate.of(2027, 12, 12), null, null, null, null,
                true, -61, false, 0L);
    }

    @BeforeEach
    void setUp() {
        final AccessPolicy accessPolicy = new AccessPolicy();
        final BudgetCategoryService categoryService = new BudgetCategoryService(categoryRepository, callerService, accessPolicy, TestClocks.at(TODAY));

        service = new BudgetService(settingsRepository, lineRepository, transactionRepository, typeLineRepository, categoryService, referenceService,
                eventRepository, eventService, logEntryRepository, callerService, new BudgetAccess(revivalRepository, accessPolicy), helperCompensation,
                applicationSettings, TestClocks.at(TODAY));
        lenient().when(helperCompensation.total(REVIVAL_ID)).thenAnswer(invocation -> helpers);
        revival = TestCallers.revival(RevivalStatus.ORGANISING);
        settings = new BudgetSettings(REVIVAL_ID);
        settings.setVersion(0L);
        lenient().when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenAnswer(invocation -> Optional.of(revival));
        lenient().when(categoryRepository.findAllByOrderByPositionAscIdAsc()).thenAnswer(invocation -> List.copyOf(categories));
        lenient().when(categoryRepository.findAllById(any())).thenAnswer(invocation -> {
            final Iterable<String> ids = invocation.getArgument(0);
            final List<BudgetCategory> found = new ArrayList<>();

            ids.forEach(id -> categories.stream().filter(category -> category.getId().equals(id)).forEach(found::add));
            return found;
        });
        lenient().when(settingsRepository.findByRevivalId(REVIVAL_ID)).thenAnswer(invocation -> Optional.ofNullable(settings));
        lenient().when(settingsRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(lineRepository.findByRevivalId(REVIVAL_ID)).thenAnswer(invocation -> List.copyOf(lines));
        lenient().when(lineRepository.findById(any())).thenAnswer(invocation ->
                lines.stream().filter(line -> line.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(lineRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            final BudgetLine line = invocation.getArgument(0);

            if (line.getId() == null) {
                line.setId(100L + lines.size());
                line.setVersion(0L);
                lines.add(line);
            } else {
                line.setVersion(line.getVersion() + 1);
            }
            return line;
        });
        lenient().when(transactionRepository.findByRevivalId(REVIVAL_ID)).thenAnswer(invocation -> List.copyOf(transactions));
        lenient().when(referenceService.all()).thenReturn(List.of());
    }

    private BudgetLine line(final String category, final String quantity, final String unitAmount) {
        final BudgetLine line = new BudgetLine();

        line.setId(10L + lines.size());
        line.setRevivalId(REVIVAL_ID);
        line.setCategoryId(category);
        line.setDescription("Rad " + lines.size());
        line.setQuantity(new BigDecimal(quantity).setScale(2));
        line.setUnitAmount(kr(unitAmount));
        line.setVersion(0L);
        lines.add(line);
        return line;
    }

    private BudgetTransaction transaction(final String category, final String amount, final Owner paidBy) {
        final BudgetTransaction transaction = new BudgetTransaction();

        transaction.setId(50L + transactions.size());
        transaction.setRevivalId(REVIVAL_ID);
        transaction.setDate(TODAY);
        transaction.setCategoryId(category);
        transaction.setDescription("Transaktion");
        transaction.setAmount(kr(amount));
        transaction.setPaidBy(paidBy);
        transaction.setVersion(0L);
        transactions.add(transaction);
        return transaction;
    }

    private Event event(final long id, final long revivalId, final @Nullable Long typeId) {
        final Event event = new Event();

        event.setId(id);
        event.setRevivalId(revivalId);
        event.setTypeId(typeId);
        when(eventRepository.findById(id)).thenReturn(Optional.of(event));
        return event;
    }

    private EventTypeBudgetLine typeLine(final long id, final BudgetLineQuantity rule, final @Nullable String quantity, final String unitAmount) {
        final EventTypeBudgetLine line = new EventTypeBudgetLine();

        line.setId(id);
        line.setEventTypeId(REHEARSAL_TYPE);
        line.setCategoryId("FOOD");
        line.setDescriptionSv("Mat");
        line.setDescriptionEn("Food");
        line.setQuantityRule(rule);
        line.setQuantity(quantity == null ? null : new BigDecimal(quantity).setScale(2));
        line.setUnitAmount(kr(unitAmount));
        line.setVersion(0L);
        return line;
    }

    @Test
    void everyRevivalGetsItsSettingsWhenItIsCreatedWithTheDefaultFee() {
        settings = null;
        when(applicationSettings.defaultParticipationFee()).thenReturn(new BigDecimal("900.00"));

        service.revivalCreated(new RevivalCreated(REVIVAL_ID));

        final ArgumentCaptor<BudgetSettings> saved = ArgumentCaptor.forClass(BudgetSettings.class);

        verify(settingsRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getParticipationFee()).isEqualByComparingTo("900");
    }

    @Test
    void observersReadButDoNotWrite() {
        when(callerService.current()).thenReturn(TestCallers.observer());

        assertThat(service.budget(REVIVAL_ID)).isNotNull();
        assertThat(service.settings(REVIVAL_ID).version()).isZero();
        assertThatThrownBy(() -> service.createLine(newLine("FOOD", null, null, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.updateSettings(new BudgetService.SettingsChange(REVIVAL_ID, 0L, 60, null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSteeringGroupMemberWithoutFinanceOnlyReads() {
        when(callerService.current()).thenReturn(steeringGroupWithout());

        assertThat(service.lines(REVIVAL_ID, false)).isEmpty();
        assertThatThrownBy(() -> service.createLine(newLine("FOOD", null, null, null))).isInstanceOf(AccessDeniedException.class);
        verify(lineRepository, never()).saveAndFlush(any());
    }

    @Test
    void anArchivedRevivalsBudgetIsReadOnly() {
        revival.setStatus(RevivalStatus.ARCHIVED);
        when(callerService.current()).thenReturn(TestCallers.superAdmin());

        assertThat(service.totals(REVIVAL_ID).budgetedResult()).isEqualTo(kr("0"));
        assertThatThrownBy(() -> service.createLine(newLine("FOOD", null, null, null))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void callersWhoCannotSeeTheRevivalGetNoBudget() {
        when(callerService.current()).thenReturn(TestCallers.nobody());

        assertThat(service.budget(REVIVAL_ID)).isNull();
        assertThatThrownBy(() -> service.totals(REVIVAL_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theSettingsAreReplacedWithTheVersionTheCallerSaw() {
        final BudgetDtos.Settings updated = service.updateSettings(new BudgetService.SettingsChange(REVIVAL_ID, 0L, 60, kr("500"), kr("10000"),
                LocalDate.of(2027, 9, 1), null));

        assertThat(updated.participantsPlanned()).isEqualTo(60);
        assertThat(updated.participationFee()).isEqualTo(kr("500"));
        assertThat(updated.loanAmount()).isEqualTo(kr("10000"));
        assertThatThrownBy(() -> service.updateSettings(new BudgetService.SettingsChange(REVIVAL_ID, 3L, 60, null, null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void settingsAreValidated() {
        assertThatThrownBy(() -> service.updateSettings(new BudgetService.SettingsChange(REVIVAL_ID, 0L, 2001, null, null, null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.participants");
        assertThatThrownBy(() -> service.updateSettings(new BudgetService.SettingsChange(REVIVAL_ID, 0L, null, kr("-1"), null, null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.amount");
        assertThatThrownBy(() -> service.updateSettings(new BudgetService.SettingsChange(REVIVAL_ID, 0L, null, null, kr("10000"),
                LocalDate.of(2027, 9, 1), LocalDate.of(2027, 8, 1)))).hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.loan.repaidBeforeReceived");
    }

    @Test
    void aRevivalWithoutSettingsGetsThemOnItsFirstUpdate() {
        settings = null;

        assertThat(service.settings(REVIVAL_ID).version()).isZero();
        assertThat(service.updateSettings(new BudgetService.SettingsChange(REVIVAL_ID, 0L, 40, null, null, null, null)).participantsPlanned())
                .isEqualTo(40);
    }

    @Test
    void aLineIsTrimmedAndCountsOnceByDefault() {
        final Line created = service.createLine(newLine("FOOD", null, null, null));

        assertThat(created.description()).isEqualTo("Mat");
        assertThat(created.quantity()).isEqualTo(1.0);
        assertThat(created.amount()).isEqualTo(kr("120"));
        assertThat(created.comment()).isNull();
    }

    @Test
    void theAmountIsQuantityTimesUnitRoundedToÖre() {
        final Line created = service.createLine(new BudgetService.NewLine(REVIVAL_ID, "FOOD", "Fika", new BigDecimal("2.5"), kr("33.33"), null, null,
                null, null));

        assertThat(created.amount()).isEqualTo(kr("83.33"));
    }

    @Test
    void lineInputIsValidated() {
        assertThatThrownBy(() -> service.createLine(new BudgetService.NewLine(REVIVAL_ID, "FOOD", " ", null, kr("1"), null, null, null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.description");
        assertThatThrownBy(() -> service.createLine(new BudgetService.NewLine(REVIVAL_ID, "FOOD", "Mat", new BigDecimal("0"), kr("1"), null, null,
                null, null))).hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.quantity");
        assertThatThrownBy(() -> service.createLine(new BudgetService.NewLine(REVIVAL_ID, "FOOD", "Mat", new BigDecimal("1.005"), kr("1"), null, null,
                null, null))).hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.quantity");
        assertThatThrownBy(() -> service.createLine(new BudgetService.NewLine(REVIVAL_ID, "FOOD", "Mat", null, kr("10000000.01"), null, null,
                null, null))).hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.amount");
        assertThatThrownBy(() -> service.createLine(new BudgetService.NewLine(REVIVAL_ID, "FOOD", "Mat", null, kr("1"), null, null,
                "x".repeat(1001), null))).hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.comment.tooLong");
        assertThatThrownBy(() -> service.createLine(newLine("NOPE", null, null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.catalog.unknown");
    }

    @Test
    void aRetiredCategoryIsNotOfferedForNewLinesButStaysOnOld() {
        final BudgetLine old = line("FOOD", "1", "100");

        categories.stream().filter(category -> category.getId().equals("FOOD")).forEach(category -> category.setRetiredAt(Instant.EPOCH));

        assertThatThrownBy(() -> service.createLine(newLine("FOOD", null, null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.catalog.retired");
        assertThat(service.updateLine(new BudgetService.LineChange(old.getId(), 0L, "FOOD", "Mat", null, null, null, false, null, false, null,
                false)).categoryId()).isEqualTo("FOOD");
    }

    @Test
    void aDecisionMustBeADecisionOfTheRevival() {
        final LogEntry decision = new LogEntry();
        final LogEntry todo = new LogEntry();
        final LogEntry elsewhere = new LogEntry();

        decision.setRevivalId(REVIVAL_ID);
        decision.setType(LogEntryType.DECISION);
        todo.setRevivalId(REVIVAL_ID);
        todo.setType(LogEntryType.TODO);
        elsewhere.setRevivalId(OTHER_REVIVAL);
        elsewhere.setType(LogEntryType.DECISION);
        when(logEntryRepository.findById(1L)).thenReturn(Optional.of(decision));
        when(logEntryRepository.findById(2L)).thenReturn(Optional.of(todo));
        when(logEntryRepository.findById(3L)).thenReturn(Optional.of(elsewhere));

        assertThat(service.createLine(newLine("HELPERS", null, 1L, null)).logEntryId()).isEqualTo(1L);
        assertThatThrownBy(() -> service.createLine(newLine("HELPERS", null, 2L, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.decision");
        assertThatThrownBy(() -> service.createLine(newLine("HELPERS", null, 3L, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.decision");
    }

    @Test
    void anEventMustBeOneOfTheRevivals() {
        event(5L, REVIVAL_ID, null);
        event(6L, OTHER_REVIVAL, null);

        assertThat(service.createLine(newLine("FOOD", 5L, null, null)).eventId()).isEqualTo(5L);
        assertThatThrownBy(() -> service.createLine(newLine("FOOD", 6L, null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.event");
    }

    @Test
    void aProposalIsAddedOncePerEventWhileItIsNotRemoved() {
        event(5L, REVIVAL_ID, REHEARSAL_TYPE);
        when(typeLineRepository.findById(7L)).thenReturn(Optional.of(typeLine(7L, BudgetLineQuantity.PER_PARTICIPANT, null, "150")));

        assertThatThrownBy(() -> service.createLine(newLine("FOOD", null, null, 7L)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.proposalWithoutEvent");

        final Line added = service.createLine(newLine("FOOD", 5L, null, 7L));

        assertThat(added.eventTypeLineId()).isEqualTo(7L);
        assertThatThrownBy(() -> service.createLine(newLine("FOOD", 5L, null, 7L)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.proposalAdded");

        service.removeLine(added.id(), 0L);
        final Line again = service.createLine(newLine("FOOD", 5L, null, 7L));

        assertThatThrownBy(() -> service.restoreLine(added.id(), 1L))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.proposalAdded");
        assertThat(again.eventTypeLineId()).isEqualTo(7L);
    }

    @Test
    void aProposalMustBeForTheEventsType() {
        event(5L, REVIVAL_ID, 99L);
        when(typeLineRepository.findById(7L)).thenReturn(Optional.of(typeLine(7L, BudgetLineQuantity.FIXED, "1", "150")));

        assertThatThrownBy(() -> service.createLine(newLine("FOOD", 5L, null, 7L)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.proposalUnknown");
    }

    @Test
    void movingALineOffItsEventMakesItAnOrdinaryLine() {
        final BudgetLine line = line("FOOD", "60", "150");

        line.setEventId(5L);
        line.setEventTypeLineId(7L);

        final Line updated = service.updateLine(new BudgetService.LineChange(line.getId(), 0L, null, null, null, null, null, true, null, false, null,
                false));

        assertThat(updated.eventId()).isNull();
        assertThat(updated.eventTypeLineId()).isNull();
    }

    @Test
    void removedLinesAreKeptAndCannotBeChanged() {
        final BudgetLine line = line("FOOD", "1", "100");

        final Line removed = service.removeLine(line.getId(), 0L);

        assertThat(removed.removed()).isTrue();
        assertThat(line.getRemovedBy()).isEqualTo(TestCallers.PERSON_ID);
        assertThatThrownBy(() -> service.removeLine(line.getId(), 1L)).hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.removed");
        assertThatThrownBy(() -> service.updateLine(new BudgetService.LineChange(line.getId(), 1L, null, "Ny", null, null, null, false, null, false,
                null, false))).hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.removed");
        assertThat(service.lines(REVIVAL_ID, false)).isEmpty();
        assertThat(service.lines(REVIVAL_ID, true)).hasSize(1);
        assertThat(service.restoreLine(line.getId(), 1L).removed()).isFalse();
        assertThatThrownBy(() -> service.restoreLine(line.getId(), 2L)).hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.line.notRemoved");
    }

    @Test
    void aStaleLineVersionIsAConflict() {
        final BudgetLine line = line("FOOD", "1", "100");

        assertThatThrownBy(() -> service.removeLine(line.getId(), 4L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void linesAreInCategoryOrder() {
        line("FOOD", "1", "100");
        line("SHOW_TICKETS", "100", "150");
        line("VENUE", "1", "1000");

        assertThat(service.lines(REVIVAL_ID, false)).extracting(Line::categoryId).containsExactly("SHOW_TICKETS", "VENUE", "FOOD");
    }

    @Test
    void totalsCountLinesNotRemovedAndTransactionsNotCancelled() {
        line("SHOW_TICKETS", "100", "150");
        line("VENUE", "1", "4000");
        line("FOOD", "60", "100").setRemovedAt(Instant.EPOCH);
        transaction("SHOW_TICKETS", "9000", Owner.none()).setReceiptFilename("kvitto.pdf");
        transaction("FOOD", "1200", Owner.member(TestCallers.PERSON_ID));
        transaction("VENUE", "500", Owner.named("Kim")).setReimbursedAt(Instant.EPOCH);
        transaction("VENUE", "7000", Owner.named("Kim")).setCancelledAt(Instant.EPOCH);

        final Totals totals = service.totals(REVIVAL_ID);

        assertThat(totals.budgetedIncome()).isEqualTo(kr("15000"));
        assertThat(totals.budgetedCost()).isEqualTo(kr("4000"));
        assertThat(totals.budgetedResult()).isEqualTo(kr("11000"));
        assertThat(totals.actualIncome()).isEqualTo(kr("9000"));
        assertThat(totals.actualCost()).isEqualTo(kr("1700"));
        assertThat(totals.actualResult()).isEqualTo(kr("7300"));
        assertThat(totals.unreimbursed()).isEqualTo(kr("1200"));
        assertThat(totals.unreimbursedCount()).isEqualTo(1);
        assertThat(totals.missingReceipts()).isEqualTo(2);
    }

    @Test
    void categoriesWithLinesTransactionsOrARoleAreShownWithReferenceFigures() {
        line("FOOD", "60", "100");
        transaction("SPONSORSHIP", "5000", Owner.none());
        when(referenceService.all()).thenReturn(List.of(
                new ReferenceRevivalDto(1L, "Nobel 2026", 2026, 50, null, false,
                        List.of(new ReferenceRevivalDto.Figure("FOOD", kr("6000"), kr("6421"))), 0L),
                new ReferenceRevivalDto(2L, "Snorre 2019", 2019, null, null, false,
                        List.of(new ReferenceRevivalDto.Figure("FOOD", null, kr("4000"))), 0L)));

        final List<CategorySummary> summaries = service.categories(REVIVAL_ID);

        assertThat(summaries).extracting(summary -> summary.category().id())
                .containsExactly("SHOW_TICKETS", "PARTY_TICKETS", "FEES", "SPONSORSHIP", "VENUE", "FOOD", "PARTY", "HELPERS");

        final CategorySummary food = summaries.get(5);

        assertThat(food.budgeted()).isEqualTo(kr("6000"));
        assertThat(food.actual()).isEqualTo(kr("0"));
        assertThat(food.references()).hasSize(2);
        assertThat(food.references().getFirst().label()).isEqualTo("Nobel 2026");
        assertThat(food.references().getFirst().budgetedPerParticipant()).isEqualTo(kr("120"));
        assertThat(food.references().getFirst().actualPerParticipant()).isEqualTo(kr("128"));
        assertThat(food.references().get(1).actualPerParticipant()).isNull();
        assertThat(summaries.get(3).actual()).isEqualTo(kr("5000"));
    }

    @Test
    void eventsProposeTheLinesOfTheirTypesNotYetAdded() {
        final EventTypeBudgetLine perParticipant = typeLine(7L, BudgetLineQuantity.PER_PARTICIPANT, null, "150");
        final EventTypeBudgetLine fixed = typeLine(8L, BudgetLineQuantity.FIXED, "2", "1500");

        when(eventService.list(anyLong(), anyBoolean())).thenReturn(List.of(eventDto(5L, REHEARSAL_TYPE), eventDto(6L, null),
                eventDto(9L, REHEARSAL_TYPE)));
        when(typeLineRepository.findByEventTypeIdInOrderByPositionAscIdAsc(any())).thenReturn(List.of(perParticipant, fixed));
        line("FOOD", "1", "1500").setEventId(9L);
        lines.getLast().setEventTypeLineId(8L);

        List<Proposal> proposals = service.proposals(REVIVAL_ID);

        assertThat(proposals).extracting(proposal -> proposal.event().id() + "/" + proposal.line().id()).containsExactly("5/7", "5/8", "9/7");
        assertThat(proposals.getFirst().quantity()).isNull();
        assertThat(proposals.getFirst().amount()).isNull();
        assertThat(proposals.get(1).quantity()).isEqualTo(2.0);
        assertThat(proposals.get(1).amount()).isEqualTo(kr("3000"));

        settings.setParticipantsPlanned(60);
        proposals = service.proposals(REVIVAL_ID);

        assertThat(proposals.getFirst().quantity()).isEqualTo(60.0);
        assertThat(proposals.getFirst().amount()).isEqualTo(kr("9000"));
    }

    @Test
    void theScenarioBaselineIsTheLinesNotRemovedWithTheirRoles() {
        line("SHOW_TICKETS", "100", "150");
        line("VENUE", "1", "4000").setRemovedAt(Instant.EPOCH);
        settings.setParticipantsPlanned(50);

        final BudgetService.Baseline baseline = service.baseline(REVIVAL_ID);

        assertThat(baseline.lines()).containsExactly(new ScenarioCalculator.Line(BudgetCategoryKind.INCOME, BudgetCategoryRole.SHOW_TICKETS,
                new BigDecimal("100.00"), kr("15000")));
        assertThat(baseline.participantsPlanned()).isEqualTo(50);
    }

    /**
     * What helpers are paid for the shifts they hold is computed from the staffing and counted as a budgeted
     * cost of the helper category everywhere the budget is summed, never stored as a line.
     */
    @Test
    void theHelpersCompensationFromTheStaffingIsABudgetedCostOfTheHelperCategory() {
        line("SHOW_TICKETS", "100", "150");
        line("HELPERS", "1", "500");
        helpers = kr("900");

        final Totals totals = service.totals(REVIVAL_ID);

        assertThat(totals.budgetedCost()).isEqualTo(kr("1400"));
        assertThat(totals.budgetedResult()).isEqualTo(kr("13600"));
        assertThat(service.categories(REVIVAL_ID)).filteredOn(summary -> summary.category().id().equals("HELPERS"))
                .singleElement().satisfies(summary -> assertThat(summary.budgeted()).isEqualTo(kr("1400")));
        assertThat(service.lines(REVIVAL_ID, false)).hasSize(2);

        final BudgetService.Baseline baseline = service.baseline(REVIVAL_ID);

        assertThat(baseline.lines()).contains(new ScenarioCalculator.Line(BudgetCategoryKind.COST, BudgetCategoryRole.HELPERS, BigDecimal.ONE,
                kr("900")));
        assertThat(ScenarioCalculator.calculate(baseline.lines(), null, null, new ScenarioParameters(null, null, null, null, null, null, null, null))
                .cost()).isEqualTo(kr("1400"));
    }
}
