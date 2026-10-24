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

import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.staffing.HelperCompensation;
import nu.fgv.revival.server.util.Amounts;
import nu.fgv.revival.server.util.Locales;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps a revival's budget as an anonymous reference before the revival is deleted or anonymized (product spec 5.10,
 * section 7, M5-FR-14): its title and year, planned participants, and per category the budgeted and actual totals, the
 * helpers' compensation from the staffing in the helper category (M6B-FR-5). No people and no descriptions.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Component
public class BudgetSnapshot {

    private final BudgetLineRepository lineRepository;
    private final BudgetTransactionRepository transactionRepository;
    private final BudgetSettingsRepository settingsRepository;
    private final BudgetCategoryService categoryService;
    private final ReferenceRevivalService referenceService;
    private final RevivalTitles titles;
    private final HelperCompensation helperCompensation;

    public BudgetSnapshot(final BudgetLineRepository lineRepository,
                          final BudgetTransactionRepository transactionRepository,
                          final BudgetSettingsRepository settingsRepository,
                          final BudgetCategoryService categoryService,
                          final ReferenceRevivalService referenceService,
                          final RevivalTitles titles,
                          final HelperCompensation helperCompensation) {
        this.lineRepository = lineRepository;
        this.transactionRepository = transactionRepository;
        this.settingsRepository = settingsRepository;
        this.categoryService = categoryService;
        this.referenceService = referenceService;
        this.titles = titles;
        this.helperCompensation = helperCompensation;
    }

    /**
     * The automatic reference made from the revival, or empty when its budget has no lines or transactions. No caller
     * check: the deletion or anonymization has checked.
     */
    @Transactional
    public Optional<ReferenceRevival> keep(final Revival revival) {
        return keep(revival, settingsRepository.findByRevivalId(revival.getId()).map(BudgetSettings::getParticipantsPlanned).orElse(null));
    }

    /**
     * As {@link #keep(Revival)}, with the number of participants the revival actually had, as anonymization knows it
     * (M7-FR-10).
     */
    @Transactional
    public Optional<ReferenceRevival> keep(final Revival revival, final @Nullable Integer participants) {
        final Map<String, BigDecimal> budgeted = new LinkedHashMap<>();
        final Map<String, BigDecimal> actual = new LinkedHashMap<>();

        lineRepository.findByRevivalId(revival.getId()).stream().filter(line -> !line.isRemoved())
                .forEach(line -> budgeted.merge(line.getCategoryId(), line.amount(), BigDecimal::add));
        transactionRepository.findByRevivalId(revival.getId()).stream().filter(transaction -> !transaction.isCancelled())
                .forEach(transaction -> actual.merge(transaction.getCategoryId(), transaction.getAmount(), BigDecimal::add));

        final BigDecimal helpers = helperCompensation.total(revival.getId());

        if (helpers != null && helpers.signum() != 0) {
            categoryService.all().stream()
                    .filter(category -> !category.isRetired() && category.getRole() == BudgetCategoryRole.HELPERS)
                    .findFirst()
                    .ifPresent(category -> budgeted.merge(category.getId(), helpers, BigDecimal::add));
        }

        final Map<String, ReferenceRevivalService.FigureInput> figures = new LinkedHashMap<>();

        for (final BudgetCategory category : categoryService.all()) {
            final @Nullable BigDecimal categoryBudgeted = budgeted.get(category.getId());
            final @Nullable BigDecimal categoryActual = actual.get(category.getId());

            if (categoryBudgeted != null || categoryActual != null) {
                figures.put(category.getId(), new ReferenceRevivalService.FigureInput(category.getId(),
                        categoryBudgeted == null ? null : Amounts.round(categoryBudgeted), categoryActual == null ? null : Amounts.round(categoryActual)));
            }
        }

        return Optional.ofNullable(referenceService.keep(titles.of(revival, Locales.SWEDISH), revival.getYear(), participants, figures));
    }
}
