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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.staffing.HelperCompensation;
import nu.fgv.revival.server.util.Locales;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A deleted revival's budget kept as reference figures: totals per category, no people, no descriptions.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class BudgetSnapshotTest {

    @Mock
    private BudgetLineRepository lineRepository;
    @Mock
    private BudgetTransactionRepository transactionRepository;
    @Mock
    private BudgetSettingsRepository settingsRepository;
    @Mock
    private BudgetCategoryService categoryService;
    @Mock
    private ReferenceRevivalService referenceService;
    @Mock
    private RevivalTitles titles;
    @Mock
    private HelperCompensation helperCompensation;

    private static BigDecimal kr(final String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private static BudgetLine line(final String category, final String quantity, final String unitAmount, final boolean removed) {
        final BudgetLine line = new BudgetLine();

        line.setCategoryId(category);
        line.setDescription("Hemlig beskrivning");
        line.setQuantity(new BigDecimal(quantity).setScale(2));
        line.setUnitAmount(kr(unitAmount));
        line.setRemovedAt(removed ? Instant.EPOCH : null);
        return line;
    }

    private static BudgetTransaction transaction(final String category, final String amount, final boolean cancelled) {
        final BudgetTransaction transaction = new BudgetTransaction();

        transaction.setCategoryId(category);
        transaction.setAmount(kr(amount));
        transaction.setDate(LocalDate.of(2028, 1, 1));
        transaction.setPaidBy(Owner.named("Kim"));
        transaction.setCancelledAt(cancelled ? Instant.EPOCH : null);
        return transaction;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTotalsPerCategoryAreKeptWithTheTitleAndParticipants() {
        final Revival revival = TestCallers.revival(RevivalStatus.ARCHIVED);
        final BudgetSettings settings = new BudgetSettings(REVIVAL_ID);
        final ArgumentCaptor<Map<String, ReferenceRevivalService.FigureInput>> figures = ArgumentCaptor.forClass(Map.class);

        settings.setParticipantsPlanned(55);
        when(lineRepository.findByRevivalId(REVIVAL_ID)).thenReturn(List.of(line("FOOD", "60", "100", false), line("FOOD", "1", "500", false),
                line("VENUE", "1", "9999", true), line("SHOW_TICKETS", "400", "150", false)));
        when(transactionRepository.findByRevivalId(REVIVAL_ID)).thenReturn(List.of(transaction("FOOD", "6100.50", false),
                transaction("PARTY", "800", false), transaction("PARTY", "5000", true)));
        when(settingsRepository.findByRevivalId(REVIVAL_ID)).thenReturn(Optional.of(settings));
        when(categoryService.all()).thenReturn(TestBudgetCategories.seeded());
        when(titles.of(revival, Locales.SWEDISH)).thenReturn("Ali Baba 2028");
        when(referenceService.keep(eq("Ali Baba 2028"), anyInt(), any(), any())).thenReturn(new ReferenceRevival());

        assertThat(new BudgetSnapshot(lineRepository, transactionRepository, settingsRepository, categoryService, referenceService, titles,
                helperCompensation)
                .keep(revival)).isPresent();
        verify(referenceService).keep(eq("Ali Baba 2028"), eq(2028), eq(55), figures.capture());
        assertThat(figures.getValue()).containsOnlyKeys("SHOW_TICKETS", "FOOD", "PARTY");
        assertThat(figures.getValue().get("FOOD")).isEqualTo(new ReferenceRevivalService.FigureInput("FOOD", kr("6500"), kr("6100.50")));
        assertThat(figures.getValue().get("SHOW_TICKETS")).isEqualTo(new ReferenceRevivalService.FigureInput("SHOW_TICKETS", kr("60000"), null));
        assertThat(figures.getValue().get("PARTY")).isEqualTo(new ReferenceRevivalService.FigureInput("PARTY", null, kr("800")));
    }
}
