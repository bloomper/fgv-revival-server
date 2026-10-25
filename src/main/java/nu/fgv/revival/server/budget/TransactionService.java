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
import nu.fgv.revival.server.budget.BudgetDtos.Receipt;
import nu.fgv.revival.server.budget.BudgetDtos.Transaction;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A revival's transactions and their receipts: who reads the budget reads them, who writes
 * it records them. A cost may name who paid it out of their own pocket, as an owner is named, and be marked reimbursed.
 * Transactions are cancelled, never deleted, and a cancelled one cannot be changed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class TransactionService {

    static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final Comparator<BudgetTransaction> NEWEST_FIRST = Comparator.comparing(BudgetTransaction::getDate).reversed()
            .thenComparing(BudgetTransaction::getId, Comparator.reverseOrder());

    private final BudgetTransactionRepository repository;
    private final ReceiptContentRepository receiptRepository;
    private final BudgetLineRepository lineRepository;
    private final BudgetCategoryService categoryService;
    private final OwnerResolver ownerResolver;
    private final CallerService callerService;
    private final BudgetAccess access;
    private final Clock clock;

    public TransactionService(final BudgetTransactionRepository repository,
                              final ReceiptContentRepository receiptRepository,
                              final BudgetLineRepository lineRepository,
                              final BudgetCategoryService categoryService,
                              final OwnerResolver ownerResolver,
                              final CallerService callerService,
                              final BudgetAccess access,
                              final Clock clock) {
        this.repository = repository;
        this.receiptRepository = receiptRepository;
        this.lineRepository = lineRepository;
        this.categoryService = categoryService;
        this.ownerResolver = ownerResolver;
        this.callerService = callerService;
        this.access = access;
        this.clock = clock;
    }

    private static BigDecimal validAmount(final BigDecimal amount) {
        return Objects.requireNonNull(BudgetService.validAmount(amount, MIN_AMOUNT));
    }

    /**
     * Newest date first.
     */
    @Transactional(readOnly = true)
    public List<Transaction> list(final Long revivalId, final boolean includeCancelled) {
        access.readable(callerService.current(), revivalId);
        return transactionsOf(revivalId, includeCancelled);
    }

    /**
     * As {@link #list}, without a caller check, for MCP and the export, which have checked.
     */
    @Transactional(readOnly = true)
    List<Transaction> transactionsOf(final Long revivalId, final boolean includeCancelled) {
        return repository.findByRevivalId(revivalId).stream()
                .filter(transaction -> includeCancelled || !transaction.isCancelled())
                .sorted(NEWEST_FIRST)
                .map(Transaction::of)
                .toList();
    }

    @Transactional
    public Transaction create(final NewTransaction newTransaction) {
        access.writable(callerService.current(), newTransaction.revivalId());
        categoryService.requireAssignable(List.of(newTransaction.categoryId()), List.of());

        final BudgetTransaction transaction = new BudgetTransaction();

        transaction.setRevivalId(newTransaction.revivalId());
        transaction.setDate(Objects.requireNonNull(newTransaction.date()));
        transaction.setAmount(validAmount(newTransaction.amount()));
        transaction.setCategoryId(newTransaction.categoryId());
        transaction.setDescription(BudgetService.validDescription(newTransaction.description()));
        transaction.setBudgetLineId(validLine(newTransaction.revivalId(), newTransaction.budgetLineId(), null));
        transaction.setPaidBy(ownerResolver.validate(newTransaction.revivalId(), newTransaction.paidBy()));
        requirePayerForCost(transaction);
        AuditContext.operation("transactionCreate");
        return Transaction.of(repository.saveAndFlush(transaction));
    }

    /**
     * Clearing who paid also clears the reimbursement.
     */
    @Transactional
    public Transaction update(final TransactionChange change) {
        final BudgetTransaction transaction = changeable(callerService.current(), change.id(), change.version());

        AuditContext.operation("transactionUpdate");
        if (change.date() != null) {
            transaction.setDate(change.date());
        }
        if (change.amount() != null) {
            transaction.setAmount(validAmount(change.amount()));
        }
        if (change.categoryId() != null) {
            categoryService.requireAssignable(List.of(change.categoryId()), List.of(transaction.getCategoryId()));
            transaction.setCategoryId(change.categoryId());
        }
        if (change.description() != null) {
            transaction.setDescription(BudgetService.validDescription(change.description()));
        }
        if (change.clearBudgetLine()) {
            transaction.setBudgetLineId(null);
        } else if (change.budgetLineId() != null) {
            transaction.setBudgetLineId(validLine(transaction.getRevivalId(), change.budgetLineId(), transaction.getBudgetLineId()));
        }
        if (change.clearPaidBy()) {
            transaction.setPaidBy(Owner.none());
        } else if (change.paidBy() != null) {
            transaction.setPaidBy(ownerResolver.change(transaction.getRevivalId(), transaction.paidBy(), change.paidBy()));
        }
        if (!transaction.paidBy().isSet()) {
            transaction.setReimbursedAt(null);
            transaction.setReimbursedBy(null);
        }
        requirePayerForCost(transaction);
        return Transaction.of(repository.saveAndFlush(transaction));
    }

    /**
     * The transaction stays readable and stops counting in the totals.
     */
    @Transactional
    public Transaction cancel(final Long id, final Long version) {
        final Caller caller = callerService.current();
        final BudgetTransaction transaction = changeable(caller, id, version);

        AuditContext.operation("transactionCancel");
        transaction.setCancelledAt(clock.instant());
        transaction.setCancelledBy(caller.person().getId());
        return Transaction.of(repository.saveAndFlush(transaction));
    }

    /**
     * Only a cost someone paid can be reimbursed.
     */
    @Transactional
    public Transaction setReimbursed(final Long id, final Long version, final boolean reimbursed) {
        final Caller caller = callerService.current();
        final BudgetTransaction transaction = changeable(caller, id, version);

        if (!transaction.paidBy().isSet()) {
            throw new BadRequestException("error.transaction.reimbursed.noPayer");
        }
        AuditContext.operation("transactionSetReimbursed");
        if (reimbursed && !transaction.isReimbursed()) {
            transaction.setReimbursedAt(clock.instant());
            transaction.setReimbursedBy(caller.person().getId());
        } else if (!reimbursed) {
            transaction.setReimbursedAt(null);
            transaction.setReimbursedBy(null);
        }
        return Transaction.of(repository.saveAndFlush(transaction));
    }

    /**
     * Stores the file as the transaction's receipt, replacing any earlier one. Its type is read from its content.
     */
    @Transactional
    public Receipt uploadReceipt(final Long id, final @Nullable String filename, final byte[] content) {
        final BudgetTransaction transaction = writableUnversioned(callerService.current(), id);
        final String type = Receipts.type(content);

        AuditContext.operation("transactionReceiptUpload");
        receiptRepository.findById(id).ifPresentOrElse(receipt -> {
            receipt.setContent(content);
            receiptRepository.save(receipt);
        }, () -> receiptRepository.save(new ReceiptContent(transaction.getId(), content)));
        transaction.setReceiptFilename(Receipts.filename(filename, type));
        transaction.setReceiptContentType(type);
        transaction.setReceiptSize(content.length);
        transaction.setReceiptUploadedAt(clock.instant());
        return Objects.requireNonNull(Transaction.of(repository.saveAndFlush(transaction)).receipt());
    }

    @Transactional(readOnly = true)
    public ReceiptFile receipt(final Long id) {
        final BudgetTransaction transaction = readable(callerService.current(), id);
        final @Nullable ReceiptContent content = receiptRepository.findById(id).orElse(null);

        if (!transaction.hasReceipt() || content == null) {
            throw new ResourceNotFoundException("error.receipt.none");
        }
        return new ReceiptFile(Objects.requireNonNull(transaction.getReceiptFilename()), Objects.requireNonNull(transaction.getReceiptContentType()),
                content.getContent());
    }

    /**
     * Clears the receipt and deletes its file.
     */
    @Transactional
    public void removeReceipt(final Long id) {
        final BudgetTransaction transaction = writableUnversioned(callerService.current(), id);

        if (!transaction.hasReceipt()) {
            throw new ResourceNotFoundException("error.receipt.none");
        }
        AuditContext.operation("transactionReceiptRemove");
        receiptRepository.findById(id).ifPresent(receiptRepository::delete);
        receiptRepository.flush();
        transaction.setReceiptFilename(null);
        transaction.setReceiptContentType(null);
        transaction.setReceiptSize(null);
        transaction.setReceiptUploadedAt(null);
        repository.saveAndFlush(transaction);
    }

    private BudgetTransaction readable(final Caller caller, final Long id) {
        final BudgetTransaction transaction = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(BudgetTransaction.class, id));

        if (!access.canRead(caller, transaction.getRevivalId())) {
            throw new ResourceNotFoundException(BudgetTransaction.class, id);
        }
        return transaction;
    }

    private BudgetTransaction writableUnversioned(final Caller caller, final Long id) {
        final BudgetTransaction transaction = readable(caller, id);

        access.writable(caller, transaction.getRevivalId());
        if (transaction.isCancelled()) {
            throw new BadRequestException("error.transaction.cancelled");
        }
        return transaction;
    }

    private BudgetTransaction changeable(final Caller caller, final Long id, final Long version) {
        final BudgetTransaction transaction = writableUnversioned(caller, id);

        if (!Objects.equals(version, transaction.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return transaction;
    }

    /**
     * Someone paid out of their own pocket only for a cost.
     */
    private void requirePayerForCost(final BudgetTransaction transaction) {
        final @Nullable BudgetCategory category = categoryService.byId(List.of(transaction.getCategoryId())).get(transaction.getCategoryId());

        if (transaction.paidBy().isSet() && category != null && category.getKind() != BudgetCategoryKind.COST) {
            throw new BadRequestException("error.transaction.paidBy.income");
        }
    }

    /**
     * A line of the same revival that is not removed, unless the transaction already has it.
     */
    private @Nullable Long validLine(final Long revivalId, final @Nullable Long lineId, final @Nullable Long held) {
        if (lineId == null || lineId.equals(held)) {
            return lineId;
        }
        if (lineRepository.findById(lineId).filter(line -> line.getRevivalId().equals(revivalId) && !line.isRemoved()).isEmpty()) {
            throw new BadRequestException("error.transaction.budgetLine");
        }
        return lineId;
    }

    public record NewTransaction(Long revivalId, LocalDate date, BigDecimal amount, String categoryId,
                                 String description,
                                 @Nullable Long budgetLineId, @Nullable OwnerInputDto paidBy) {
    }

    /**
     * Fields left null are unchanged; the {@code clear*} flags remove a value.
     */
    public record TransactionChange(Long id, Long version, @Nullable LocalDate date, @Nullable BigDecimal amount,
                                    @Nullable String categoryId,
                                    @Nullable String description, @Nullable Long budgetLineId, boolean clearBudgetLine,
                                    @Nullable OwnerInputDto paidBy, boolean clearPaidBy) {
    }

    /**
     * A receipt's file to download.
     */
    public record ReceiptFile(String filename, String contentType, byte[] content) {
    }
}
