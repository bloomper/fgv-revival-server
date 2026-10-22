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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checklist MCP tools and get_revival_status (M3 FR-043, FR-044).
 *
 * @author Anders Jacobsson
 */
class ChecklistMcpToolsIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Stockholm"));

    private long revival;
    private long rehearsal;

    @BeforeEach
    void revivalWithChecklists() {
        publishedGuide();
        revival = organisingRevival();
        rehearsal = Long.parseLong(graphQl(STEERING_GROUP)
                .document("mutation($input: EventChecklistCreateInput!) { eventChecklistCreate(input: $input) { id } }")
                .variable("input", Map.of("revivalId", revival, "name", "Rephelg 2", "date", TODAY.plusWeeks(3).toString()))
                .execute().path("eventChecklistCreate.id").entity(String.class).get());
        for (final Map<String, Object> item : List.<Map<String, Object>>of(
                Map.of("checklistId", rehearsal, "text", "Nycklar och access", "deadline", TODAY.minusDays(2).toString()),
                Map.of("checklistId", rehearsal, "text", "Fika", "owner", Map.of("personId", personId(STEERING_GROUP))))) {
            graphQl(STEERING_GROUP).document("mutation($input: ChecklistItemCreateInput!) { checklistItemCreate(input: $input) { id } }")
                    .variable("input", item).execute();
        }
        result(call(STEERING_GROUP, "create_log_entry", Map.of("revivalId", revival, "type", "TODO", "text", "Försenad", "deadline",
                TODAY.minusDays(1).toString())));
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

    private List<Long> ids(final JsonNode list) {
        final List<Long> ids = new ArrayList<>();

        list.get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    @Test
    void listChecklistItemsMatchesTheWeb() {
        for (final Map<String, Object> filter : List.<Map<String, Object>>of(Map.of(), Map.of("mine", true), Map.of("overdue", true), Map.of("open", true))) {
            final Map<String, Object> arguments = new HashMap<>(filter);

            arguments.put("revivalId", revival);

            final List<Long> web = graphQl(STEERING_GROUP)
                    .document("query($id: ID!, $filter: ChecklistItemFilter) { checklistItems(revivalId: $id, filter: $filter) { id } }")
                    .variable("id", revival).variable("filter", filter)
                    .execute()
                    .path("checklistItems[*].id").entityList(Long.class).get();

            assertThat(ids(result(call(STEERING_GROUP, "list_checklist_items", arguments)))).isNotEmpty().isEqualTo(web);
        }

        final JsonNode phaseA = result(call(STEERING_GROUP, "list_checklist_items", Map.of("revivalId", revival, "chapter", "1")));

        assertThat(phaseA.at("/items/0/required").asBoolean()).isTrue();
        assertThat(phaseA.at("/items/0/checklist/chapterKey").asString()).isEqualTo("A");
        assertThat(phaseA.at("/items/0/checklist/chapterTitle/sv").asString()).isEqualTo("Först: godkännande och tajmning");
        assertThat(phaseA.at("/items/0/text/sv").asString()).startsWith("Nyuppsättningen ska godkännas");
        assertThat(call(STEERING_GROUP, "list_checklist_items", Map.of("revivalId", revival, "chapter", "K")).toString())
                .contains("BAD_REQUEST").contains("There is no chapter");

        final JsonNode byName = result(call(STEERING_GROUP, "list_checklist_items", Map.of("revivalId", revival, "checklist", "rephelg 2", "limit", 1)));

        assertThat(byName.get("total").asInt()).isEqualTo(2);
        assertThat(byName.get("items")).hasSize(1);
        assertThat(byName.at("/items/0/checklist/name").asString()).isEqualTo("Rephelg 2");
        assertThat(result(call(STEERING_GROUP, "list_checklist_items", Map.of("revivalId", revival, "checklist", "Kalas"))).get("total").asInt()).isZero();
    }

    @Test
    void completeChecklistItemMarksItDoneByTheTokensPerson() {
        final long item = result(call(STEERING_GROUP, "list_checklist_items", Map.of("revivalId", revival, "overdue", true))).at("/items/0/id").asLong();
        final JsonNode done = result(call(STEERING_GROUP, "complete_checklist_item", Map.of("itemId", item, "comment", "Hämtade i dag")));

        assertThat(done.get("state").asString()).isEqualTo("DONE");
        assertThat(done.get("comment").asString()).isEqualTo("Hämtade i dag");
        assertThat(jdbcClient.sql("SELECT state_changed_by FROM checklist_item WHERE id = ?").param(item).query(Long.class).single())
                .isEqualTo(personId(STEERING_GROUP));
        assertThat(jdbcClient.sql("SELECT DISTINCT r.source FROM checklist_item_audit a JOIN revinfo r ON r.id = a.rev WHERE a.id = ? AND a.state = 'DONE'")
                .param(item).query(String.class).list()).containsExactly("MCP");

        final long version = jdbcClient.sql("SELECT version FROM checklist_item WHERE id = ?").param(item).query(Long.class).single();

        assertThat(result(call(STEERING_GROUP, "complete_checklist_item", Map.of("itemId", item, "comment", "Igen"))).get("comment").asString())
                .isEqualTo("Hämtade i dag");
        assertThat(jdbcClient.sql("SELECT version FROM checklist_item WHERE id = ?").param(item).query(Long.class).single()).isEqualTo(version);
        assertThat(refused(call(OBSERVER, "complete_checklist_item", Map.of("itemId", item)))).isTrue();
    }

    @Test
    void getRevivalStatusSummarisesWhatIsOverdueAndOpen() {
        final JsonNode status = result(call(DIRECTEUR, "get_revival_status", Map.of("revivalId", revival)));

        assertThat(status.at("/revival/status").asString()).isEqualTo("ORGANISING");
        assertThat(status.at("/revival/year").asInt()).isEqualTo(2028);
        assertThat(status.get("overdueLogEntries")).hasSize(1);
        assertThat(status.at("/overdueLogEntries/0/text").asString()).isEqualTo("Försenad");
        assertThat(status.get("overdueChecklistItems")).hasSize(1);
        assertThat(status.at("/overdueChecklistItems/0/checklist/name").asString()).isEqualTo("Rephelg 2");
        assertThat(status.get("openRequirements")).hasSize(10);
        assertThat(status.at("/openRequirements/0/chapterKey").asString()).isEqualTo("A");
        assertThat(status.at("/openRequirements/0/open").asInt()).isEqualTo(1);
        assertThat(status.at("/openRequirements/1/total").asInt()).isZero();

        final List<Long> webOverdue = graphQl(DIRECTEUR)
                .document("query($id: ID!) { checklistItems(revivalId: $id, filter: {overdue: true}) { id } }")
                .variable("id", revival).execute().path("checklistItems[*].id").entityList(Long.class).get();

        assertThat(status.at("/overdueChecklistItems/0/id").asLong()).isEqualTo(webOverdue.getFirst());
        assertThat(result(call(OBSERVER, "get_revival_status", Map.of("revivalId", revival))).get("openRequirements")).hasSize(10);
    }

    @Test
    void outputHasNoEmailAddress() {
        for (final JsonNode response : List.of(
                call(STEERING_GROUP, "list_checklist_items", Map.of("revivalId", revival)),
                call(STEERING_GROUP, "get_revival_status", Map.of("revivalId", revival)))) {
            assertThat(response.toString()).doesNotContain(DOMAIN);
        }
    }
}
