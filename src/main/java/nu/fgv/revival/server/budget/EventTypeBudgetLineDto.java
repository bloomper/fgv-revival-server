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

import java.math.BigDecimal;
import java.util.Locale;

/**
 * GraphQL's {@code EventTypeBudgetLine}; its category is resolved from the id.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
public record EventTypeBudgetLineDto(Long id, Long eventTypeId, String categoryId, GuideVersionView.Text description,
                                     BudgetLineQuantity quantityRule, @Nullable Double quantity, BigDecimal unitAmount, Long version) {

    static EventTypeBudgetLineDto of(final EventTypeBudgetLine line) {
        return new EventTypeBudgetLineDto(line.getId(), line.getEventTypeId(), line.getCategoryId(),
                GuideVersionView.Text.plain(line.getDescriptionSv(), line.getDescriptionEn()), line.getQuantityRule(),
                line.getQuantity() == null ? null : line.getQuantity().doubleValue(), line.getUnitAmount(),
                line.getVersion() == null ? 0L : line.getVersion());
    }

    /**
     * The description in the language, Swedish when there is no English one.
     */
    public String description(final Locale locale) {
        return "en".equals(locale.getLanguage()) && description.en() != null ? description.en() : description.sv();
    }
}
