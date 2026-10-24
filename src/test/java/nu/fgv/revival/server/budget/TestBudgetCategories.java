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

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Some of the budget categories the database starts with (migration V5_0), for tests without one.
 *
 * @author Anders Jacobsson
 */
final class TestBudgetCategories {

    private TestBudgetCategories() {
    }

    static List<BudgetCategory> seeded() {
        final List<BudgetCategory> categories = new ArrayList<>();

        categories.add(category("SHOW_TICKETS", BudgetCategoryKind.INCOME, BudgetCategoryRole.SHOW_TICKETS, "Biljetter föreställning", "Show tickets", 0));
        categories.add(category("PARTY_TICKETS", BudgetCategoryKind.INCOME, BudgetCategoryRole.PARTY_TICKETS, "Biljetter kalas", "Party tickets", 1));
        categories.add(category("FEES", BudgetCategoryKind.INCOME, BudgetCategoryRole.FEES, "Spexpengar", "Participation fees", 2));
        categories.add(category("SPONSORSHIP", BudgetCategoryKind.INCOME, null, "Sponsring", "Sponsorship", 3));
        categories.add(category("VENUE", BudgetCategoryKind.COST, BudgetCategoryRole.VENUE, "Lokaler", "Venues", 4));
        categories.add(category("FOOD", BudgetCategoryKind.COST, null, "Mat och dryck", "Food and drink", 5));
        categories.add(category("PARTY", BudgetCategoryKind.COST, BudgetCategoryRole.PARTY, "Kalas", "Party", 6));
        categories.add(category("HELPERS", BudgetCategoryKind.COST, BudgetCategoryRole.HELPERS, "Springare", "External helpers", 7));
        return categories;
    }

    static BudgetCategory category(final String id, final BudgetCategoryKind kind, final @Nullable BudgetCategoryRole role, final String sv,
                                   final @Nullable String en, final int position) {
        final BudgetCategory category = new BudgetCategory();

        category.setId(id);
        category.setKind(kind);
        category.setRole(role);
        category.setNameSv(sv);
        category.setNameEn(en);
        category.setPosition(position);
        category.setVersion(0L);
        return category;
    }
}
