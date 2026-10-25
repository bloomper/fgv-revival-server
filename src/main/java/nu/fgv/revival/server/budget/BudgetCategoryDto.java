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

import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * GraphQL's {@code BudgetCategory}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record BudgetCategoryDto(String id, BudgetCategoryKind kind, @Nullable BudgetCategoryRole role,
                                GuideVersionView.Text name, int position,
                                boolean retired, Long version) {

    public static BudgetCategoryDto of(final BudgetCategory category) {
        return new BudgetCategoryDto(category.getId(), category.getKind(), category.getRole(),
                GuideVersionView.Text.plain(category.getNameSv(), category.getNameEn()), category.getPosition(), category.isRetired(),
                category.getVersion() == null ? 0L : category.getVersion());
    }

    /**
     * The name in the language, Swedish when there is no English one.
     */
    public String name(final Locale locale) {
        return "en".equals(locale.getLanguage()) && name.en() != null ? name.en() : name.sv();
    }
}
