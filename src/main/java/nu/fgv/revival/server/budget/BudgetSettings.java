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
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A revival's budget settings: planned participants and fee, and the loan from FGV/JK (product spec 5.10). One per
 * revival, made when the revival is created.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Entity
@Table(name = "budget_settings")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class BudgetSettings extends AbstractAuditable {

    public static final int MAX_PARTICIPANTS = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "participants_planned")
    private @Nullable Integer participantsPlanned;

    @Column(name = "participation_fee", precision = 12, scale = 2)
    private @Nullable BigDecimal participationFee;

    @Column(name = "loan_amount", precision = 12, scale = 2)
    private @Nullable BigDecimal loanAmount;

    @Column(name = "loan_received_on")
    private @Nullable LocalDate loanReceivedOn;

    @Column(name = "loan_repaid_on")
    private @Nullable LocalDate loanRepaidOn;

    public BudgetSettings(final Long revivalId) {
        this.revivalId = revivalId;
    }
}
