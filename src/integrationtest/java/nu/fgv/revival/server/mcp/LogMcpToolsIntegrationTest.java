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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision log MCP tools apply the web application's rules, with owners by display name (M3 FR-043, FR-045).
 *
 * @author Anders Jacobsson
 */
class LogMcpToolsIntegrationTest extends AbstractIntegrationTest {

    private long revival;

    @BeforeEach
    void revivalWithRows() {
        revival = organisingRevival();
        for (final String text : List.of("Boka RunAn", "Fråga JK om datum", "Beställ affischer")) {
            result(call(STEERING_GROUP, "create_log_entry", Map.of("revivalId", revival, "type", "TODO", "text", text)));
        }
    }

    private JsonNode call(final String user, final String tool, final Map<String, Object> arguments) {
        return mcpRpc(mcpToken(user), "tools/call", Map.of("name", tool, "arguments", arguments));
    }

    private JsonNode result(final JsonNode response) {
        final JsonNode result = response.get("result");

        if (result == null || result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool error: " + response);
        }
        return objectMapper.readTree(result.get("content").get(0).get("text").asString());
    }

    private static boolean refused(final JsonNode response) {
        return response.has("error") || response.path("result").path("isError").asBoolean(false);
    }

    private static String errorText(final JsonNode response) {
        return response.toString();
    }

    @Test
    void listLogEntriesReturnsTheWebsRowsInTheWebsOrder() {
        result(call(STEERING_GROUP, "update_log_entry", Map.of("revivalId", revival, "number", 2, "status", "DONE")));

        for (final Map<String, Object> filter : List.<Map<String, Object>>of(Map.of(), Map.of("open", true), Map.of("text", "JK"))) {
            final Map<String, Object> arguments = new HashMap<>(filter);

            arguments.put("revivalId", revival);

            final List<Integer> mcp = new ArrayList<>();
            final List<Integer> web = graphQl(STEERING_GROUP)
                    .document("query($id: ID!, $filter: LogEntryFilter) { logEntries(revivalId: $id, filter: $filter) { number } }")
                    .variable("id", revival).variable("filter", filter)
                    .execute()
                    .path("logEntries[*].number").entityList(Integer.class).get();

            result(call(STEERING_GROUP, "list_log_entries", arguments)).get("items").forEach(row -> mcp.add(row.get("number").asInt()));
            assertThat(mcp).isNotEmpty().isEqualTo(web);
        }

        final long meeting = Long.parseLong(execute(STEERING_GROUP, "mutation($input: MeetingCreateInput!) { meetingCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "date", LocalDate.now().toString()))).path("meetingCreate.id").entity(String.class).get());

        execute(STEERING_GROUP, "mutation($id: ID!, $version: Int!) { meetingStart(id: $id, version: $version) { id } }",
                Map.of("id", meeting, "version", jdbcClient.sql("SELECT version FROM meeting WHERE id = ?").param(meeting).query(Long.class).single()));
        execute(STEERING_GROUP, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "type", "DECISION", "text", "Vi spelar i RunAn", "meetingId", meeting)));

        final JsonNode inMeeting = result(call(STEERING_GROUP, "list_log_entries", Map.of("revivalId", revival, "meeting", meeting)));

        assertThat(inMeeting.get("items")).singleElement().satisfies(row -> assertThat(row.get("number").asInt()).isEqualTo(4));

        final JsonNode limited = result(call(STEERING_GROUP, "list_log_entries", Map.of("revivalId", revival, "limit", 2)));

        assertThat(limited.get("items")).hasSize(2);
        assertThat(limited.get("total").asInt()).isEqualTo(4);
    }

    @Test
    void createLogEntryResolvesOwnersByName() {
        final JsonNode me = result(call(STEERING_GROUP, "create_log_entry", Map.of("revivalId", revival, "type", "TODO", "text", "Ring lokalen",
                "owner", "me", "deadline", "2027-01-22", "tags", List.of("lokal"))));

        assertThat(me.get("ownerResolution").asString()).isEqualTo("MEMBER");
        assertThat(me.at("/owner/name").asString()).isEqualTo("Stina Styrgrupp");
        assertThat(me.at("/owner/member").asBoolean()).isTrue();
        assertThat(me.get("deadline").asString()).isEqualTo("2027-01-22");
        assertThat(result(call(STEERING_GROUP, "create_log_entry", Map.of("revivalId", revival, "type", "DECISION", "text", "Vi spelar i RunAn",
                "owner", "dana"))).get("ownerResolution").asString()).isEqualTo("MEMBER");

        final JsonNode freeText = result(call(STEERING_GROUP, "create_log_entry", Map.of("revivalId", revival, "type", "TODO", "text", "Hyr ljus",
                "owner", "Sam från lokalen")));

        assertThat(freeText.get("ownerResolution").asString()).isEqualTo("FREE_TEXT");
        assertThat(freeText.at("/owner/member").asBoolean()).isFalse();

        login(DIRECTEUR_2);
        addMember(revival, DIRECTEUR_2, "DIRECTEUR", List.of());
        jdbcClient.sql("UPDATE person SET display_name = 'Dana Berg' WHERE email = ?").param(DIRECTEUR_2).update();

        final int before = jdbcClient.sql("SELECT COUNT(*) FROM log_entry").query(Integer.class).single();
        final JsonNode ambiguous = call(STEERING_GROUP, "create_log_entry", Map.of("revivalId", revival, "type", "TODO", "text", "x", "owner", "Dana"));

        assertThat(refused(ambiguous)).isTrue();
        assertThat(errorText(ambiguous)).contains("Dana Berg, Dana Directeur");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM log_entry").query(Integer.class).single()).isEqualTo(before);
        assertThat(jdbcClient.sql("SELECT DISTINCT r.source FROM log_entry_audit a JOIN revinfo r ON r.id = a.rev").query(String.class).list())
                .containsExactly("MCP");
    }

    @Test
    void updateLogEntryWorksByNumberAndEmptyStringsClear() {
        result(call(STEERING_GROUP, "update_log_entry", Map.of("revivalId", revival, "number", 1, "owner", "Stina", "deadline", "2027-02-01",
                "comment", "Ring på måndag")));

        final JsonNode cleared = result(call(STEERING_GROUP, "update_log_entry", Map.of("revivalId", revival, "number", 1, "owner", "",
                "deadline", "", "comment", "", "status", "WAITING")));

        assertThat(cleared.get("owner").isNull()).isTrue();
        assertThat(cleared.get("deadline").isNull()).isTrue();
        assertThat(cleared.get("comment").isNull()).isTrue();
        assertThat(cleared.get("status").asString()).isEqualTo("WAITING");
        assertThat(cleared.get("ownerResolution").asString()).isEqualTo("NONE");
        assertThat(refused(call(STEERING_GROUP, "update_log_entry", Map.of("revivalId", revival, "number", 99, "status", "DONE")))).isTrue();
        assertThat(errorText(call(STEERING_GROUP, "update_log_entry", Map.of("revivalId", revival, "number", 99, "status", "DONE"))))
                .contains("does not exist");
    }

    @Test
    void observersReadAndOthersAreRefused() {
        assertThat(result(call(OBSERVER, "list_log_entries", Map.of("revivalId", revival))).get("items")).hasSize(3);
        assertThat(refused(call(OBSERVER, "create_log_entry", Map.of("revivalId", revival, "type", "TODO", "text", "x")))).isTrue();
        assertThat(refused(call(OBSERVER, "update_log_entry", Map.of("revivalId", revival, "number", 1, "status", "DONE")))).isTrue();
        assertThat(refused(call(SUPER_ADMIN, "create_log_entry", Map.of("revivalId", revival, "type", "TODO", "text", "x")))).isTrue();

        login(PLAIN_USER);
        assertThat(refused(call(PLAIN_USER, "list_log_entries", Map.of("revivalId", revival)))).isTrue();
        assertThat(refused(call(PLAIN_USER, "create_log_entry", Map.of("revivalId", revival, "type", "TODO", "text", "x")))).isTrue();

        restTestClient().post().uri(MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, token(STEERING_GROUP))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of()))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void outputHasNoEmailAddress() {
        result(call(STEERING_GROUP, "update_log_entry", Map.of("revivalId", revival, "number", 1, "owner", "me")));

        for (final JsonNode response : List.of(
                call(STEERING_GROUP, "list_log_entries", Map.of("revivalId", revival)),
                call(DIRECTEUR, "update_log_entry", Map.of("revivalId", revival, "number", 2, "owner", "Dana")),
                call(DIRECTEUR, "create_log_entry", Map.of("revivalId", revival, "type", "QUESTION", "text", "Vem bokar?", "owner", "me")))) {
            assertThat(response.toString()).doesNotContain(DOMAIN);
        }
    }
}
