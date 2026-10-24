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

import nu.fgv.revival.server.util.Amounts;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The scenario calculator (product spec 5.10, M5-FR-10): what the result would be with other ticket sales, fee,
 * venue or party, from a budget's lines and settings. Pure, so every rule is tested on its own.
 * <ul>
 *     <li>The baseline comes from the lines that are not removed: the tickets are the quantity of the ticket lines and
 *     the price their amount per ticket; the venue and party costs are the sums of their lines; participants and fee
 *     are the settings.</li>
 *     <li>A role's income or cost follows its lines unless one of its parameters is given: then it is tickets × price,
 *     participants × fee, or the given cost. A parameter left null keeps its baseline value.</li>
 *     <li>Break-even is the show tickets at the used price that bring the result to zero; party subsidy is the party's
 *     cost minus its ticket income.</li>
 * </ul>
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
public final class ScenarioCalculator {

    private static final Set<BudgetCategoryRole> OWN_INCOME = EnumSet.of(BudgetCategoryRole.SHOW_TICKETS, BudgetCategoryRole.PARTY_TICKETS,
            BudgetCategoryRole.FEES);
    private static final Set<BudgetCategoryRole> OWN_COST = EnumSet.of(BudgetCategoryRole.VENUE, BudgetCategoryRole.PARTY);

    private ScenarioCalculator() {
    }

    /**
     * A budget line that is not removed, with its category's kind and role.
     */
    public record Line(BudgetCategoryKind kind, @Nullable BudgetCategoryRole role, BigDecimal quantity, BigDecimal amount) {
    }

    public static ScenarioResultDto calculate(final List<Line> lines, final @Nullable Integer participantsPlanned,
                                              final @Nullable BigDecimal participationFee, final ScenarioParameters input) {
        final Tickets show = tickets(lines, BudgetCategoryRole.SHOW_TICKETS);
        final Tickets party = tickets(lines, BudgetCategoryRole.PARTY_TICKETS);
        final BigDecimal feeLines = sum(lines, line -> line.role() == BudgetCategoryRole.FEES);
        final BigDecimal venueLines = sum(lines, line -> line.role() == BudgetCategoryRole.VENUE);
        final BigDecimal partyLines = sum(lines, line -> line.role() == BudgetCategoryRole.PARTY);
        final BigDecimal otherIncome = sum(lines, line -> line.kind() == BudgetCategoryKind.INCOME && !OWN_INCOME.contains(line.role()));
        final BigDecimal otherCost = sum(lines, line -> line.kind() == BudgetCategoryKind.COST && !OWN_COST.contains(line.role()));

        final ScenarioParameters baseline = new ScenarioParameters(participantsPlanned, participationFee, show.price(), show.count(),
                party.price(), party.count(), venueLines, partyLines);
        final ScenarioParameters used = new ScenarioParameters(
                or(input.participants(), baseline.participants()),
                or(input.participationFee(), baseline.participationFee()),
                or(input.showTicketPrice(), baseline.showTicketPrice()),
                or(input.showTickets(), baseline.showTickets()),
                or(input.partyTicketPrice(), baseline.partyTicketPrice()),
                or(input.partyTickets(), baseline.partyTickets()),
                or(input.venueCost(), baseline.venueCost()),
                or(input.partyCost(), baseline.partyCost()));

        final BigDecimal showIncome = income(input.showTickets() != null || input.showTicketPrice() != null,
                used.showTickets(), used.showTicketPrice(), show.amount());
        final BigDecimal partyIncome = income(input.partyTickets() != null || input.partyTicketPrice() != null,
                used.partyTickets(), used.partyTicketPrice(), party.amount());
        final BigDecimal feeIncome = income(input.participants() != null || input.participationFee() != null,
                used.participants(), used.participationFee(), feeLines);
        final BigDecimal venueCost = Amounts.orZero(used.venueCost());
        final BigDecimal partyCost = Amounts.orZero(used.partyCost());

        final BigDecimal income = Amounts.round(otherIncome.add(showIncome).add(partyIncome).add(feeIncome));
        final BigDecimal cost = Amounts.round(otherCost.add(venueCost).add(partyCost));

        return new ScenarioResultDto(baseline, used, income, cost, income.subtract(cost),
                breakEven(cost, income.subtract(showIncome), used.showTicketPrice()), Amounts.round(partyCost.subtract(partyIncome)));
    }

    /**
     * The ticket lines of a role: their count rounded to whole tickets and their price per ticket, both null without
     * lines or quantity.
     */
    private record Tickets(@Nullable Integer count, @Nullable BigDecimal price, BigDecimal amount) {
    }

    private static Tickets tickets(final List<Line> lines, final BudgetCategoryRole role) {
        final List<Line> ticketLines = lines.stream().filter(line -> line.role() == role).toList();
        final BigDecimal quantity = ticketLines.stream().map(Line::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        final BigDecimal amount = Amounts.round(ticketLines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add));

        if (ticketLines.isEmpty() || quantity.signum() == 0) {
            return new Tickets(ticketLines.isEmpty() ? null : 0, null, amount);
        }
        return new Tickets(quantity.setScale(0, RoundingMode.HALF_UP).intValueExact(), amount.divide(quantity, Amounts.SCALE, RoundingMode.HALF_UP),
                amount);
    }

    private static BigDecimal sum(final List<Line> lines, final Predicate<Line> filter) {
        return Amounts.round(lines.stream().filter(filter).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * {@code count × price} when a parameter was given and both are known, otherwise the lines' sum.
     */
    private static BigDecimal income(final boolean overridden, final @Nullable Integer count, final @Nullable BigDecimal price,
                                     final BigDecimal lines) {
        return overridden && count != null && price != null ? Amounts.times(BigDecimal.valueOf(count), price) : lines;
    }

    /**
     * Show tickets at the price for a result of zero: what the cost leaves after every other income, never below zero.
     */
    private static @Nullable Integer breakEven(final BigDecimal cost, final BigDecimal otherIncome, final @Nullable BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            return null;
        }

        final BigDecimal needed = cost.subtract(otherIncome);

        return needed.signum() <= 0 ? 0 : needed.divide(price, 0, RoundingMode.CEILING).intValueExact();
    }

    private static <T> @Nullable T or(final @Nullable T value, final @Nullable T fallback) {
        return value != null ? value : fallback;
    }
}
