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
import nu.fgv.revival.server.budget.BudgetDtos.Transaction;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A revival's transactions and receipts: who paid, reimbursement, cancellation and the receipt's file.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 11, 1);
    private static final long MEMBER = 20L;
    private final List<BudgetCategory> categories = new ArrayList<>(TestBudgetCategories.seeded());
    private final List<BudgetTransaction> transactions = new ArrayList<>();
    @Mock
    private BudgetTransactionRepository repository;
    @Mock
    private ReceiptContentRepository receiptRepository;
    @Mock
    private BudgetLineRepository lineRepository;
    @Mock
    private BudgetCategoryRepository categoryRepository;
    @Mock
    private OwnerResolver ownerResolver;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private CallerService callerService;
    private TransactionService service;
    private Revival revival;

    private static Owner owner(final @Nullable OwnerInputDto input) {
        if (input == null || input.personId() == null && input.name() == null) {
            return Owner.none();
        }
        return input.personId() != null ? Owner.member(input.personId()) : Owner.named(input.name().strip());
    }

    private static BigDecimal kr(final String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private static TransactionService.TransactionChange change(final Long id, final Long version) {
        return new TransactionService.TransactionChange(id, version, null, null, null, null, null, false, null, false);
    }

    @BeforeEach
    void setUp() {
        final AccessPolicy accessPolicy = new AccessPolicy();
        final BudgetCategoryService categoryService = new BudgetCategoryService(categoryRepository, callerService, accessPolicy, TestClocks.at(TODAY));

        service = new TransactionService(repository, receiptRepository, lineRepository, categoryService, ownerResolver, callerService,
                new BudgetAccess(revivalRepository, accessPolicy), TestClocks.at(TODAY));
        revival = TestCallers.revival(RevivalStatus.ORGANISING);
        lenient().when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenAnswer(invocation -> Optional.of(revival));
        lenient().when(categoryRepository.findAllById(any())).thenAnswer(invocation -> {
            final Iterable<String> ids = invocation.getArgument(0);
            final List<BudgetCategory> found = new ArrayList<>();

            ids.forEach(id -> categories.stream().filter(category -> category.getId().equals(id)).forEach(found::add));
            return found;
        });
        lenient().when(repository.findByRevivalId(REVIVAL_ID)).thenAnswer(invocation -> List.copyOf(transactions));
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                transactions.stream().filter(transaction -> transaction.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            final BudgetTransaction transaction = invocation.getArgument(0);

            if (transaction.getId() == null) {
                transaction.setId(100L + transactions.size());
                transaction.setVersion(0L);
                transactions.add(transaction);
            } else {
                transaction.setVersion(transaction.getVersion() + 1);
            }
            return transaction;
        });
        lenient().when(ownerResolver.validate(anyLong(), any())).thenAnswer(invocation -> owner(invocation.getArgument(1)));
        lenient().when(ownerResolver.change(anyLong(), any(), any())).thenAnswer(invocation -> owner(invocation.getArgument(2)));
    }

    private Transaction create(final String category, final @Nullable OwnerInputDto paidBy) {
        return service.create(new TransactionService.NewTransaction(REVIVAL_ID, TODAY, kr("349.50"), category, " Mat till rephelgen ", null, paidBy));
    }

    @Test
    void aCostCanBePaidByAMemberOrSomeoneElse() {
        final Transaction byMember = create("FOOD", new OwnerInputDto(MEMBER, null));
        final Transaction byName = create("FOOD", new OwnerInputDto(null, " Kim "));

        assertThat(byMember.description()).isEqualTo("Mat till rephelgen");
        assertThat(byMember.payer()).isEqualTo(Owner.member(MEMBER));
        assertThat(byName.payer()).isEqualTo(Owner.named("Kim"));
        assertThat(byMember.reimbursed()).isFalse();
        assertThat(byMember.receipt()).isNull();
    }

    @Test
    void incomeIsNeverPaidBySomeone() {
        assertThatThrownBy(() -> create("SHOW_TICKETS", new OwnerInputDto(null, "Kim")))
                .isInstanceOf(BadRequestException.class).hasFieldOrPropertyWithValue("detailMessageCode", "error.transaction.paidBy.income");

        final Transaction cost = create("FOOD", new OwnerInputDto(null, "Kim"));

        assertThatThrownBy(() -> service.update(new TransactionService.TransactionChange(cost.id(), 0L, null, null, "SPONSORSHIP", null, null, false,
                null, false))).hasFieldOrPropertyWithValue("detailMessageCode", "error.transaction.paidBy.income");
        assertThat(service.update(new TransactionService.TransactionChange(cost.id(), 0L, null, null, "SPONSORSHIP", null, null, false, null, true))
                .categoryId()).isEqualTo("SPONSORSHIP");
    }

    @Test
    void amountsArePositive() {
        assertThatThrownBy(() -> service.create(new TransactionService.NewTransaction(REVIVAL_ID, TODAY, kr("0"), "FOOD", "Mat", null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.amount");
    }

    @Test
    void aBudgetLineMustBeOneOfTheRevivalsNotRemoved() {
        final BudgetLine line = new BudgetLine();

        line.setId(7L);
        line.setRevivalId(2L);
        when(lineRepository.findById(7L)).thenReturn(Optional.of(line));

        assertThatThrownBy(() -> service.create(new TransactionService.NewTransaction(REVIVAL_ID, TODAY, kr("1"), "FOOD", "Mat", 7L, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.transaction.budgetLine");
    }

    @Test
    void onlyACostSomeonePaidIsReimbursed() {
        final Transaction paid = create("FOOD", new OwnerInputDto(MEMBER, null));
        final Transaction unpaid = create("FOOD", null);

        final Transaction reimbursed = service.setReimbursed(paid.id(), 0L, true);

        assertThat(reimbursed.reimbursed()).isTrue();
        assertThat(reimbursed.reimbursedAt()).isNotNull();
        assertThat(service.setReimbursed(paid.id(), 1L, false).reimbursed()).isFalse();
        assertThatThrownBy(() -> service.setReimbursed(unpaid.id(), 0L, true))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.transaction.reimbursed.noPayer");
    }

    @Test
    void clearingWhoPaidClearsTheReimbursement() {
        final Transaction paid = create("FOOD", new OwnerInputDto(MEMBER, null));

        service.setReimbursed(paid.id(), 0L, true);

        final Transaction cleared = service.update(new TransactionService.TransactionChange(paid.id(), 1L, null, null, null, null, null, false, null,
                true));

        assertThat(cleared.payer().isSet()).isFalse();
        assertThat(cleared.reimbursed()).isFalse();
    }

    @Test
    void aCancelledTransactionStaysButCannotBeChanged() {
        final Transaction created = create("FOOD", null);
        final Transaction cancelled = service.cancel(created.id(), 0L);

        assertThat(cancelled.cancelled()).isTrue();
        assertThat(service.list(REVIVAL_ID, false)).isEmpty();
        assertThat(service.list(REVIVAL_ID, true)).hasSize(1);
        assertThatThrownBy(() -> service.update(change(created.id(), 1L))).hasFieldOrPropertyWithValue("detailMessageCode", "error.transaction.cancelled");
        assertThatThrownBy(() -> service.cancel(created.id(), 1L)).hasFieldOrPropertyWithValue("detailMessageCode", "error.transaction.cancelled");
        assertThatThrownBy(() -> service.uploadReceipt(created.id(), "kvitto.pdf", ReceiptsTest.PDF))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.transaction.cancelled");
    }

    @Test
    void aStaleVersionIsAConflict() {
        final Transaction created = create("FOOD", null);

        assertThatThrownBy(() -> service.update(change(created.id(), 3L))).isInstanceOf(ConflictException.class);
    }

    @Test
    void theNewestTransactionsComeFirst() {
        create("FOOD", null);
        service.create(new TransactionService.NewTransaction(REVIVAL_ID, TODAY.minusDays(3), kr("1"), "FOOD", "Äldre", null, null));
        service.create(new TransactionService.NewTransaction(REVIVAL_ID, TODAY.plusDays(1), kr("1"), "FOOD", "Nyare", null, null));

        assertThat(service.list(REVIVAL_ID, false)).extracting(Transaction::description).containsExactly("Nyare", "Mat till rephelgen", "Äldre");
    }

    @Test
    void readersOnlyRead() {
        when(callerService.current()).thenReturn(TestCallers.observer());

        assertThat(service.list(REVIVAL_ID, false)).isEmpty();
        assertThatThrownBy(() -> create("FOOD", null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aReceiptIsStoredWithItsSniffedTypeAndReplacedByTheNext() {
        final Transaction created = create("FOOD", null);
        final BudgetDtos.Receipt receipt = service.uploadReceipt(created.id(), "../kvitto.pdf", ReceiptsTest.PDF);

        assertThat(receipt.filename()).isEqualTo("kvitto.pdf");
        assertThat(receipt.contentType()).isEqualTo(Receipts.PDF);
        assertThat(receipt.size()).isEqualTo(ReceiptsTest.PDF.length);
        verify(receiptRepository).save(any(ReceiptContent.class));

        final ReceiptContent stored = new ReceiptContent(created.id(), ReceiptsTest.PDF);

        when(receiptRepository.findById(created.id())).thenReturn(Optional.of(stored));
        assertThat(service.uploadReceipt(created.id(), "bild.png", ReceiptsTest.PNG).contentType()).isEqualTo(Receipts.PNG);
        assertThat(stored.getContent()).isEqualTo(ReceiptsTest.PNG);

        final TransactionService.ReceiptFile file = service.receipt(created.id());

        assertThat(file.filename()).isEqualTo("bild.png");
        assertThat(file.content()).isEqualTo(ReceiptsTest.PNG);
    }

    @Test
    void aFileThatIsNoReceiptIsRefused() {
        final Transaction created = create("FOOD", null);

        assertThatThrownBy(() -> service.uploadReceipt(created.id(), "kvitto.pdf", "<script>".getBytes(StandardCharsets.UTF_8)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.receipt.type");
        verify(receiptRepository, never()).save(any());
    }

    @Test
    void removingAReceiptDeletesItsFile() {
        final Transaction created = create("FOOD", null);
        final ReceiptContent stored = new ReceiptContent(created.id(), ReceiptsTest.PDF);

        service.uploadReceipt(created.id(), "kvitto.pdf", ReceiptsTest.PDF);
        when(receiptRepository.findById(created.id())).thenReturn(Optional.of(stored));
        service.removeReceipt(created.id());

        verify(receiptRepository).delete(stored);
        assertThat(service.list(REVIVAL_ID, false).getFirst().receipt()).isNull();
        assertThatThrownBy(() -> service.removeReceipt(created.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.receipt(created.id())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void readersDownloadButDoNotUpload() {
        final Transaction created = create("FOOD", null);

        service.uploadReceipt(created.id(), "kvitto.pdf", ReceiptsTest.PDF);
        when(receiptRepository.findById(created.id())).thenReturn(Optional.of(new ReceiptContent(created.id(), ReceiptsTest.PDF)));
        when(callerService.current()).thenReturn(TestCallers.observer());

        assertThat(service.receipt(created.id()).contentType()).isEqualTo(Receipts.PDF);
        assertThatThrownBy(() -> service.uploadReceipt(created.id(), "kvitto.pdf", ReceiptsTest.PDF)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.removeReceipt(created.id())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aReceiptOfARevivalTheCallerCannotSeeDoesNotExist() {
        final Transaction created = create("FOOD", null);

        when(callerService.current()).thenReturn(TestCallers.nobody());

        assertThatThrownBy(() -> service.receipt(created.id())).isInstanceOf(ResourceNotFoundException.class);
    }
}
