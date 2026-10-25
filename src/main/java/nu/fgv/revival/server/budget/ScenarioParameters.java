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

import java.math.BigDecimal;

/**
 * The parameters of a scenario, as GraphQL's {@code BudgetScenarioInput} and
 * {@code BudgetScenarioParameters}. As input, a null parameter keeps the budget's value.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record ScenarioParameters(@Nullable Integer participants,
                                 @Nullable BigDecimal participationFee,
                                 @Nullable BigDecimal showTicketPrice,
                                 @Nullable Integer showTickets,
                                 @Nullable BigDecimal partyTicketPrice,
                                 @Nullable Integer partyTickets,
                                 @Nullable BigDecimal venueCost,
                                 @Nullable BigDecimal partyCost) {

    public static ScenarioParameters none() {
        return new ScenarioParameters(null, null, null, null, null, null, null, null);
    }
}
