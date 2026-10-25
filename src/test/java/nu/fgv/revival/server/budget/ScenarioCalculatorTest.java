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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static nu.fgv.revival.server.budget.BudgetCategoryKind.COST;
import static nu.fgv.revival.server.budget.BudgetCategoryKind.INCOME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scenario calculator: the baseline from the lines, each override path, break-even and the party
 * subsidy.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ScenarioCalculatorTest {

    /**
     * Show tickets 400 × 150 = 60000 and 50 × 100 = 5000 (450 tickets, 144.44 each), party tickets 100 × 200 = 20000,
     * fees 60 × 500 = 30000, sponsorship 10000; venue 30000, party 25000, food 12000 and helpers 4800.
     * Income 125000, cost 71800, result 53200.
     */
    private static final List<ScenarioCalculator.Line> LINES = List.of(
            line(INCOME, BudgetCategoryRole.SHOW_TICKETS, "400", "60000"),
            line(INCOME, BudgetCategoryRole.SHOW_TICKETS, "50", "5000"),
            line(INCOME, BudgetCategoryRole.PARTY_TICKETS, "100", "20000"),
            line(INCOME, BudgetCategoryRole.FEES, "60", "30000"),
            line(INCOME, null, "1", "10000"),
            line(COST, BudgetCategoryRole.VENUE, "3", "30000"),
            line(COST, BudgetCategoryRole.PARTY, "1", "25000"),
            line(COST, null, "60", "12000"),
            line(COST, BudgetCategoryRole.HELPERS, "4", "4800"));

    private static ScenarioCalculator.Line line(final BudgetCategoryKind kind, final @Nullable BudgetCategoryRole role, final String quantity,
                                                final String amount) {
        return new ScenarioCalculator.Line(kind, role, new BigDecimal(quantity), new BigDecimal(amount).setScale(2));
    }

    private static BigDecimal kr(final String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private static ScenarioResultDto calculate(final ScenarioParameters input) {
        return ScenarioCalculator.calculate(LINES, 60, kr("500"), input);
    }

    @Test
    void theBaselineComesFromTheLinesAndSettings() {
        final ScenarioResultDto result = calculate(ScenarioParameters.none());

        assertThat(result.baseline()).isEqualTo(new ScenarioParameters(60, kr("500"), new BigDecimal("144.44"), 450, kr("200"), 100,
                kr("30000"), kr("25000")));
        assertThat(result.used()).isEqualTo(result.baseline());
        assertThat(result.income()).isEqualTo(kr("125000"));
        assertThat(result.cost()).isEqualTo(kr("71800"));
        assertThat(result.result()).isEqualTo(kr("53200"));
        assertThat(result.partySubsidy()).isEqualTo(kr("5000"));
    }

    @Test
    void breakEvenIsTheShowTicketsAtThePriceThatCoverWhatOtherIncomeDoesNot() {
        // Other income 60000 against cost 71800 leaves 11800, at 144.44 a ticket: 81.69…, so 82
        assertThat(calculate(ScenarioParameters.none()).breakEvenShowTickets()).isEqualTo(82);
    }

    @Test
    void showTicketsAtAnotherPriceReplaceTheirLines() {
        final ScenarioResultDto result = calculate(new ScenarioParameters(null, null, kr("200"), null, null, null, null, null));

        // 450 × 200 = 90000 instead of 65000
        assertThat(result.used().showTicketPrice()).isEqualTo(kr("200"));
        assertThat(result.used().showTickets()).isEqualTo(450);
        assertThat(result.income()).isEqualTo(kr("150000"));
        assertThat(result.result()).isEqualTo(kr("78200"));
        assertThat(result.breakEvenShowTickets()).isEqualTo(59);
    }

    @Test
    void anotherNumberOfShowTicketsKeepsThePrice() {
        final ScenarioResultDto result = calculate(new ScenarioParameters(null, null, null, 300, null, null, null, null));

        // 300 × 144.44 = 43332
        assertThat(result.income()).isEqualTo(kr("103332"));
    }

    @Test
    void partyTicketsAndPartyCostGiveTheSubsidy() {
        final ScenarioResultDto result = calculate(new ScenarioParameters(null, null, null, null, kr("150"), 120, null, kr("40000")));

        // 120 × 150 = 18000 against a party of 40000
        assertThat(result.income()).isEqualTo(kr("123000"));
        assertThat(result.cost()).isEqualTo(kr("86800"));
        assertThat(result.partySubsidy()).isEqualTo(kr("22000"));
    }

    @Test
    void participantsOrFeeReplaceTheFeeLines() {
        // 80 × 500 = 40000 instead of 30000
        assertThat(calculate(new ScenarioParameters(80, null, null, null, null, null, null, null)).income()).isEqualTo(kr("135000"));
        // 60 × 400 = 24000
        assertThat(calculate(new ScenarioParameters(null, kr("400"), null, null, null, null, null, null)).income()).isEqualTo(kr("119000"));
    }

    @Test
    void aFeeWithoutParticipantsKeepsTheFeeLines() {
        final ScenarioResultDto result = ScenarioCalculator.calculate(LINES, null, null,
                new ScenarioParameters(null, kr("400"), null, null, null, null, null, null));

        assertThat(result.used().participants()).isNull();
        assertThat(result.income()).isEqualTo(kr("125000"));
    }

    @Test
    void anotherVenueChangesTheCost() {
        final ScenarioResultDto result = calculate(new ScenarioParameters(null, null, null, null, null, null, kr("10000"), null));

        assertThat(result.cost()).isEqualTo(kr("51800"));
        assertThat(result.result()).isEqualTo(kr("73200"));
        assertThat(result.breakEvenShowTickets()).isZero();
    }

    @Test
    void withoutAShowTicketPriceThereIsNoBreakEven() {
        final ScenarioResultDto result = ScenarioCalculator.calculate(List.of(line(COST, BudgetCategoryRole.VENUE, "1", "1000")), null, null,
                ScenarioParameters.none());

        assertThat(result.baseline().showTickets()).isNull();
        assertThat(result.baseline().showTicketPrice()).isNull();
        assertThat(result.breakEvenShowTickets()).isNull();
        assertThat(result.result()).isEqualTo(kr("-1000"));
        assertThat(result.partySubsidy()).isEqualTo(kr("0"));
    }

    @Test
    void aPriceAloneGivesBreakEvenEvenWithoutTicketLines() {
        final ScenarioResultDto result = ScenarioCalculator.calculate(List.of(line(COST, BudgetCategoryRole.VENUE, "1", "1000")), null, null,
                new ScenarioParameters(null, null, kr("150"), null, null, null, null, null));

        // No ticket count, so the income stays the lines' (none), but break-even needs only the price
        assertThat(result.income()).isEqualTo(kr("0"));
        assertThat(result.breakEvenShowTickets()).isEqualTo(7);
    }

    @Test
    void aFreeShowHasNoBreakEven() {
        assertThat(calculate(new ScenarioParameters(null, null, kr("0"), null, null, null, null, null)).breakEvenShowTickets()).isNull();
    }

    @Test
    void ticketLinesWithoutQuantityHaveNoPrice() {
        final ScenarioResultDto result = ScenarioCalculator.calculate(List.of(line(INCOME, BudgetCategoryRole.SHOW_TICKETS, "0", "0")), null,
                null, ScenarioParameters.none());

        assertThat(result.baseline().showTickets()).isZero();
        assertThat(result.baseline().showTicketPrice()).isNull();
    }

    @Test
    void anEmptyBudgetIsZero() {
        final ScenarioResultDto result = ScenarioCalculator.calculate(List.of(), null, null, ScenarioParameters.none());

        assertThat(result.income()).isEqualTo(kr("0"));
        assertThat(result.cost()).isEqualTo(kr("0"));
        assertThat(result.result()).isEqualTo(kr("0"));
        assertThat(result.baseline().venueCost()).isEqualTo(kr("0"));
    }
}
