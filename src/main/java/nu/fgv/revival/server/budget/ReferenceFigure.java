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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * A reference revival's budgeted and actual amount for one category; at least one of them is set.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "reference_figure")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class ReferenceFigure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reference_revival_id", nullable = false, updatable = false)
    private ReferenceRevival referenceRevival;

    @Column(name = "category_id", nullable = false, updatable = false, length = 36)
    private String categoryId;

    @Column(name = "budgeted", precision = 12, scale = 2)
    private @Nullable BigDecimal budgeted;

    @Column(name = "actual", precision = 12, scale = 2)
    private @Nullable BigDecimal actual;

    public ReferenceFigure(final ReferenceRevival referenceRevival, final String categoryId) {
        this.referenceRevival = referenceRevival;
        this.categoryId = categoryId;
    }
}
