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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * A line an event type proposes for the budget of a revival with an event of the type (M5-FR-3), e.g. food per
 * participant for a rehearsal weekend. Kept by the super admin; removing one deletes it.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Entity
@Table(name = "event_type_budget_line")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class EventTypeBudgetLine extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type_id", nullable = false, updatable = false)
    private Long eventTypeId;

    @Column(name = "category_id", nullable = false, length = 36)
    private String categoryId;

    @Column(name = "description_sv", nullable = false, length = BudgetLine.DESCRIPTION_MAX_LENGTH)
    private String descriptionSv;

    @Column(name = "description_en", length = BudgetLine.DESCRIPTION_MAX_LENGTH)
    private @Nullable String descriptionEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "quantity_rule", nullable = false, length = 16)
    private BudgetLineQuantity quantityRule;

    @Column(name = "quantity", precision = 10, scale = 2)
    private @Nullable BigDecimal quantity;

    @Column(name = "unit_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitAmount;

    @Column(name = "position", nullable = false)
    private int position;
}
