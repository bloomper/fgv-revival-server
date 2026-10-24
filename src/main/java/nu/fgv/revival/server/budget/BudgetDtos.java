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

import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.member.Owner;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The GraphQL types of a revival's budget (budget.graphqls). Categories, events, decisions and payers are resolved
 * from the ids the records hold.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
public final class BudgetDtos {

    private BudgetDtos() {
    }

    /**
     * GraphQL's {@code Budget}: only handed out to callers who read the revival's budget; its fields are resolved by
     * the services, which check again.
     */
    public record Budget(Long revivalId) {
    }

    public record Settings(@Nullable Integer participantsPlanned, @Nullable BigDecimal participationFee,
                           @Nullable BigDecimal loanAmount,
                           @Nullable LocalDate loanReceivedOn, @Nullable LocalDate loanRepaidOn, Long version) {

        static Settings of(final @Nullable BudgetSettings settings) {
            return settings == null ? new Settings(null, null, null, null, null, 0L)
                    : new Settings(settings.getParticipantsPlanned(), settings.getParticipationFee(), settings.getLoanAmount(),
                    settings.getLoanReceivedOn(), settings.getLoanRepaidOn(), settings.getVersion() == null ? 0L : settings.getVersion());
        }
    }

    public record Line(Long id, Long revivalId, String categoryId, String description, double quantity,
                       BigDecimal unitAmount, BigDecimal amount,
                       @Nullable Long eventId, @Nullable Long logEntryId, @Nullable Long eventTypeLineId,
                       @Nullable String comment, boolean removed,
                       Long version) {

        static Line of(final BudgetLine line) {
            return new Line(line.getId(), line.getRevivalId(), line.getCategoryId(), line.getDescription(), line.getQuantity().doubleValue(),
                    line.getUnitAmount(), line.amount(), line.getEventId(), line.getLogEntryId(), line.getEventTypeLineId(), line.getComment(),
                    line.isRemoved(), line.getVersion() == null ? 0L : line.getVersion());
        }
    }

    /**
     * GraphQL's {@code Transaction}. {@code payer} is resolved to {@code paidBy}.
     */
    public record Transaction(Long id, Long revivalId, LocalDate date, BigDecimal amount, String categoryId,
                              String description,
                              @Nullable Long budgetLineId, Owner payer, boolean reimbursed,
                              @Nullable Instant reimbursedAt,
                              @Nullable Receipt receipt, boolean cancelled, Long version) {

        static Transaction of(final BudgetTransaction transaction) {
            return new Transaction(transaction.getId(), transaction.getRevivalId(), transaction.getDate(), transaction.getAmount(),
                    transaction.getCategoryId(), transaction.getDescription(), transaction.getBudgetLineId(), transaction.paidBy(),
                    transaction.isReimbursed(), transaction.getReimbursedAt(), Receipt.of(transaction), transaction.isCancelled(),
                    transaction.getVersion() == null ? 0L : transaction.getVersion());
        }
    }

    /**
     * A receipt's metadata; the file is fetched over HTTP.
     */
    public record Receipt(String filename, String contentType, int size, Instant uploadedAt) {

        static @Nullable Receipt of(final BudgetTransaction transaction) {
            return transaction.getReceiptFilename() == null || transaction.getReceiptContentType() == null || transaction.getReceiptSize() == null
                    || transaction.getReceiptUploadedAt() == null ? null
                    : new Receipt(transaction.getReceiptFilename(), transaction.getReceiptContentType(), transaction.getReceiptSize(),
                    transaction.getReceiptUploadedAt());
        }
    }

    public record Totals(BigDecimal budgetedIncome, BigDecimal budgetedCost, BigDecimal budgetedResult,
                         BigDecimal actualIncome,
                         BigDecimal actualCost, BigDecimal actualResult, BigDecimal unreimbursed, int unreimbursedCount,
                         int missingReceipts) {
    }

    public record CategorySummary(BudgetCategoryDto category, BigDecimal budgeted, BigDecimal actual,
                                  List<ReferenceFigureView> references) {
    }

    public record ReferenceFigureView(String label, Integer year, @Nullable Integer participants,
                                      @Nullable BigDecimal budgeted,
                                      @Nullable BigDecimal actual, @Nullable BigDecimal budgetedPerParticipant,
                                      @Nullable BigDecimal actualPerParticipant) {
    }

    public record Proposal(EventDto event, EventTypeBudgetLineDto line, @Nullable Double quantity,
                           @Nullable BigDecimal amount) {
    }

    public record Scenario(Long id, Long revivalId, String name, ScenarioParameters parameters,
                           ScenarioResultDto result, Long version) {
    }
}
