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

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Money that came in or went out (product spec 5.10, M5-FR-5): income or cost by its category's kind. A cost may have
 * been paid by someone out of their own pocket, who is then reimbursed. Cancelled rather than deleted; a cancelled
 * transaction stays readable and cannot be changed. The receipt's file is kept apart, in {@link ReceiptContent}.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Entity
@Table(name = "budget_transaction")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class BudgetTransaction extends AbstractAuditable {

    public static final int FILENAME_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "category_id", nullable = false, length = 36)
    private String categoryId;

    @Column(name = "description", nullable = false, length = BudgetLine.DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(name = "budget_line_id")
    private @Nullable Long budgetLineId;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "personId", column = @Column(name = "paid_by_person_id")),
            @AttributeOverride(name = "name", column = @Column(name = "paid_by_name", length = Owner.NAME_MAX_LENGTH))
    })
    private @Nullable Owner paidBy;

    @Column(name = "reimbursed_at")
    private @Nullable Instant reimbursedAt;

    @Column(name = "reimbursed_by")
    private @Nullable Long reimbursedBy;

    @Column(name = "receipt_filename", length = FILENAME_MAX_LENGTH)
    private @Nullable String receiptFilename;

    @Column(name = "receipt_content_type", length = 64)
    private @Nullable String receiptContentType;

    @Column(name = "receipt_size")
    private @Nullable Integer receiptSize;

    @Column(name = "receipt_uploaded_at")
    private @Nullable Instant receiptUploadedAt;

    @Column(name = "cancelled_at")
    private @Nullable Instant cancelledAt;

    @Column(name = "cancelled_by")
    private @Nullable Long cancelledBy;

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    public boolean isReimbursed() {
        return reimbursedAt != null;
    }

    public boolean hasReceipt() {
        return receiptFilename != null;
    }

    /**
     * Who paid, never null: Hibernate reads an embedded owner without columns as null.
     */
    public Owner paidBy() {
        return paidBy == null ? Owner.none() : paidBy;
    }
}
