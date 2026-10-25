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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.catalog.CatalogEntry;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

/**
 * A budget category, from the list the super admin keeps. Its kind is fixed when it is created, so lines and
 * transactions in it keep counting as income or cost.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "budget_category")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class BudgetCategory extends CatalogEntry {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private BudgetCategoryKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16)
    private @Nullable BudgetCategoryRole role;
}
