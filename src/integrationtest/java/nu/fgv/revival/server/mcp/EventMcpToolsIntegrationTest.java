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


package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list_events MCP tool (M4-FR-10).
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
class EventMcpToolsIntegrationTest extends AbstractIntegrationTest {

    private static final String CREATE = "mutation($input: EventCreateInput!) { eventCreate(input: $input) { id version } }";

    private long revival;
    private long rehearsal;

    private static List<String> titles(final JsonNode events) {
        final List<String> titles = new ArrayList<>();

        events.get("items").forEach(event -> titles.add(event.get("title").asString()));
        return titles;
    }

    @BeforeEach
    void revivalWithEvents() {
        revival = organisingRevival();
        execute(SUPER_ADMIN, "mutation($input: RevivalUpdateInput!) { revivalUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", revival, "version", version(revival), "premiereDate", "2028-02-10")));

        final long type = jdbcClient.sql("SELECT id FROM event_type WHERE name_sv = 'Rephelg 1'").query(Long.class).single();

        rehearsal = Long.parseLong(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "typeId", type,
                "startDate", "2027-12-11", "location", "Kemi"))).path("eventCreate.id").entity(String.class).get());
        execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "title", "Förrådsbesök", "startDate", "2028-01-10",
                "startTime", "18:00")));

        final long cancelled = Long.parseLong(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "title", "AW",
                "startDate", "2027-10-01"))).path("eventCreate.id").entity(String.class).get());

        execute(STEERING_GROUP, "mutation($id: ID!, $version: Int!) { eventCancel(id: $id, version: $version) { id } }",
                Map.of("id", cancelled, "version", 0));
    }

    private JsonNode call(final String user, final Map<String, Object> arguments) {
        return mcpRpc(mcpToken(user), "tools/call", Map.of("name", "list_events", "arguments", arguments));
    }

    private JsonNode result(final JsonNode response) {
        final JsonNode result = response.get("result");

        if (result == null || result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool error: " + response);
        }
        return objectMapper.readTree(result.get("content").get(0).get("text").asString());
    }

    @Test
    void listEventsMatchesTheWeb() {
        final JsonNode events = result(call(STEERING_GROUP, Map.of("revivalId", revival)));
        final List<String> web = graphQl(STEERING_GROUP).document("query($id: ID!) { events(revivalId: $id) { title } }")
                .variable("id", revival).execute().path("events[*].title").entityList(String.class).get();

        assertThat(titles(events)).isEqualTo(web).containsExactly("Rehearsal weekend 1", "Förrådsbesök");
        assertThat(events.get("total").asInt()).isEqualTo(2);
        assertThat(events.at("/items/0/id").asLong()).isEqualTo(rehearsal);
        assertThat(events.at("/items/0/type/sv").asString()).isEqualTo("Rephelg 1");
        assertThat(events.at("/items/0/endDate").asString()).isEqualTo("2027-12-12");
        assertThat(events.at("/items/0/location").asString()).isEqualTo("Kemi");
        assertThat(events.at("/items/0/daysFromPremiere").asInt()).isEqualTo(-61);
        assertThat(events.at("/items/1/startTime").asString()).isEqualTo("18:00");
        assertThat(events.at("/items/1/type").isNull() || events.at("/items/1/type").isMissingNode()).isTrue();
    }

    @Test
    void filtersCombine() {
        assertThat(titles(result(call(STEERING_GROUP, Map.of("revivalId", revival, "includeCancelled", true))))).containsExactly("AW",
                "Rehearsal weekend 1", "Förrådsbesök");
        assertThat(titles(result(call(STEERING_GROUP, Map.of("revivalId", revival, "from", "2027-12-12"))))).containsExactly("Rehearsal weekend 1",
                "Förrådsbesök");
        assertThat(titles(result(call(STEERING_GROUP, Map.of("revivalId", revival, "to", "2027-12-31"))))).containsExactly("Rehearsal weekend 1");

        final JsonNode limited = result(call(STEERING_GROUP, Map.of("revivalId", revival, "limit", 1)));

        assertThat(limited.get("items")).hasSize(1);
        assertThat(limited.get("total").asInt()).isEqualTo(2);
        assertThat(call(STEERING_GROUP, Map.of("revivalId", revival, "from", "i morgon")).toString()).contains("BAD_REQUEST");
    }

    @Test
    void observersReadAndOthersDoNot() {
        assertThat(titles(result(call(OBSERVER, Map.of("revivalId", revival))))).hasSize(2);
        assertThat(call(PLAIN_USER, Map.of("revivalId", revival)).toString()).contains("NOT_FOUND");
        assertThat(call(STEERING_GROUP, Map.of("revivalId", revival)).toString()).doesNotContain(DOMAIN);
    }
}
