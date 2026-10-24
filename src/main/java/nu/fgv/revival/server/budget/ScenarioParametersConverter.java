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

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import nu.fgv.revival.server.util.Amounts;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/**
 * A scenario's parameters as a JSON object. Amounts are written as strings, so the database keeps them exact.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Converter
public class ScenarioParametersConverter implements AttributeConverter<ScenarioParameters, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(final @Nullable ScenarioParameters parameters) {
        final ScenarioParameters value = parameters == null ? ScenarioParameters.none() : parameters;
        final ObjectNode node = JSON.createObjectNode();

        put(node, "participants", value.participants());
        put(node, "participationFee", value.participationFee());
        put(node, "showTicketPrice", value.showTicketPrice());
        put(node, "showTickets", value.showTickets());
        put(node, "partyTicketPrice", value.partyTicketPrice());
        put(node, "partyTickets", value.partyTickets());
        put(node, "venueCost", value.venueCost());
        put(node, "partyCost", value.partyCost());
        return JSON.writeValueAsString(node);
    }

    @Override
    public ScenarioParameters convertToEntityAttribute(final @Nullable String json) {
        if (json == null || json.isBlank()) {
            return ScenarioParameters.none();
        }

        final JsonNode node = JSON.readTree(json);

        return new ScenarioParameters(integer(node, "participants"), amount(node, "participationFee"), amount(node, "showTicketPrice"),
                integer(node, "showTickets"), amount(node, "partyTicketPrice"), integer(node, "partyTickets"), amount(node, "venueCost"),
                amount(node, "partyCost"));
    }

    private static void put(final ObjectNode node, final String name, final @Nullable Integer value) {
        if (value != null) {
            node.put(name, value.intValue());
        }
    }

    private static void put(final ObjectNode node, final String name, final @Nullable BigDecimal value) {
        if (value != null) {
            node.put(name, Amounts.format(value));
        }
    }

    private static @Nullable Integer integer(final JsonNode node, final String name) {
        final JsonNode value = node.get(name);

        return value == null || value.isNull() ? null : value.asInt();
    }

    private static @Nullable BigDecimal amount(final JsonNode node, final String name) {
        final JsonNode value = node.get(name);

        return value == null || value.isNull() ? null : Amounts.parse(value.asString());
    }
}
