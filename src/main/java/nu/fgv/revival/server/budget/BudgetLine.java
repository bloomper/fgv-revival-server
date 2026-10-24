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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import nu.fgv.revival.server.util.Amounts;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A line in a revival's budget (M5-FR-4): what is planned in a category, optionally for an event or after a decision.
 * Removed rather than deleted; a removed line stays in the history but not in the totals.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Entity
@Table(name = "budget_line")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class BudgetLine extends AbstractAuditable {

    public static final int DESCRIPTION_MAX_LENGTH = 255;
    public static final int COMMENT_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "category_id", nullable = false, length = 36)
    private String categoryId;

    @Column(name = "description", nullable = false, length = DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(name = "quantity", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;

    @Column(name = "unit_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitAmount;

    @Column(name = "event_id")
    private @Nullable Long eventId;

    @Column(name = "log_entry_id")
    private @Nullable Long logEntryId;

    /**
     * The event type's line it was proposed from, as a plain id: the template line may be removed later.
     */
    @Column(name = "event_type_line_id")
    private @Nullable Long eventTypeLineId;

    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private @Nullable String comment;

    @Column(name = "removed_at")
    private @Nullable Instant removedAt;

    @Column(name = "removed_by")
    private @Nullable Long removedBy;

    public boolean isRemoved() {
        return removedAt != null;
    }

    /**
     * {@code quantity × unitAmount}, rounded to öre.
     */
    public BigDecimal amount() {
        return Amounts.times(quantity, unitAmount);
    }
}
