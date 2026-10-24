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

import nu.fgv.revival.server.util.catalog.CatalogRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * @author Anders Jacobsson
 * @since 0.6
 */
public interface BudgetCategoryRepository extends CatalogRepository<BudgetCategory> {

    @Query(value = """
            SELECT category_id FROM budget_line
            UNION SELECT category_id FROM budget_transaction
            UNION SELECT category_id FROM reference_figure
            UNION SELECT category_id FROM event_type_budget_line""", nativeQuery = true)
    List<String> findUsedIds();
}
