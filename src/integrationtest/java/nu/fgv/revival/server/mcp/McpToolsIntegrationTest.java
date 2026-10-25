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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP tools as a whole: everything but personal data, nothing that deletes or sends, and a round through the
 * revival's work and the super admin's lists.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class McpToolsIntegrationTest extends AbstractIntegrationTest {

    private long revival;

    private static boolean refused(final JsonNode response) {
        return response.has("error") || response.path("result").path("isError").asBoolean(false);
    }

    @BeforeEach
    void organisingRevivalWithAPremiere() {
        revival = organisingRevival();
        execute(SUPER_ADMIN, "mutation($input: RevivalUpdateInput!) { revivalUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", revival, "version", version(revival), "premiereDate", "2028-02-10")));
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

    private JsonNode ok(final String user, final String tool, final Map<String, Object> arguments) {
        final JsonNode response = call(user, tool, arguments);

        assertThat(response.toString()).as(tool).doesNotContain(DOMAIN);
        return result(response);
    }

    private static Map<String, Object> args(final Object... pairs) {
        final Map<String, Object> map = new HashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void theToolsCoverTheWorkButNoPersonalDataDeletingOrSending() {
        final List<String> names = new ArrayList<>();

        for (final JsonNode tool : mcpRpc(mcpToken(STEERING_GROUP), "tools/list", Map.of()).path("result").path("tools")) {
            names.add(tool.get("name").asString());
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).as(tool.get("name").asString()).isFalse();
        }

        assertThat(names).contains("list_meetings", "change_meeting_state", "answer_log_question", "set_checklist_item_state", "create_event",
                        "set_event_cancelled", "create_budget_line", "list_transactions", "calculate_budget_scenario", "list_materials",
                        "list_inventory", "list_mailings", "list_shift_slots", "list_guide_versions", "get_guide_draft", "move_guide_part",
                        "list_catalog_entries", "create_event_type", "list_checklist_templates", "create_mailing_template", "create_news",
                        "save_spex_calendar_date", "get_history", "list_upcoming_revivals", "change_revival_status")
                .doesNotContain("list_participants", "get_staffing")
                .noneMatch(name -> name.contains("delete") || name.contains("send") || name.contains("participant") || name.contains("member")
                        || name.contains("availability") || name.contains("anonymi") || name.contains("sync") || name.contains("publish")
                        || name.contains("calendar_feed") || name.contains("discard"));
    }

    @Test
    void theSteeringGroupRunsAMeetingAndAnswersAQuestion() {
        final long meeting = ok(STEERING_GROUP, "create_meeting", args("revivalId", revival, "date", "2027-11-01", "title", "Styrgruppsmöte",
                "attendees", List.of("me", "Dana"), "othersPresent", "Vaktmästaren", "agenda", List.of("Lokaler"))).get("id").asLong();

        assertThat(ok(STEERING_GROUP, "get_meeting", args("meetingId", meeting)).get("attendees").size()).isEqualTo(2);
        assertThat(refused(call(STEERING_GROUP, "create_meeting", args("revivalId", revival, "date", "2027-11-02", "attendees", List.of("Okänd Person")))))
                .isTrue();
        assertThat(ok(STEERING_GROUP, "change_meeting_state", args("meetingId", meeting, "action", "START")).get("status").asString())
                .isEqualTo("IN_PROGRESS");

        final int question = ok(STEERING_GROUP, "create_log_entry", args("revivalId", revival, "type", "QUESTION", "text", "Var spelar vi?"))
                .get("number").asInt();
        final JsonNode answered = ok(DIRECTEUR, "answer_log_question", args("revivalId", revival, "number", question, "text", "I RunAn"));

        assertThat(answered.get("question").get("status").asString()).isEqualTo("DONE");
        assertThat(answered.get("decision").get("type").asString()).isEqualTo("DECISION");
        assertThat(ok(OBSERVER, "get_history", args("type", "LOG_ENTRY", "id", answered.get("question").get("id").asLong()))
                .get("items").get(0).get("modifiedBy").asString()).isEqualTo("Dana Directeur");
        assertThat(ok(STEERING_GROUP, "change_meeting_state", args("meetingId", meeting, "action", "CLOSE")).get("status").asString())
                .isEqualTo("CLOSED");
    }

    @Test
    void eventsBudgetMaterialsAndSlotsAreWrittenAndNeverDeleted() {
        final long event = ok(STEERING_GROUP, "create_event", args("revivalId", revival, "title", "Förrådsbesök", "startDate", "2028-01-10"))
                .get("id").asLong();

        assertThat(ok(STEERING_GROUP, "update_event", args("eventId", event, "location", "Kemi")).get("location").asString()).isEqualTo("Kemi");
        assertThat(ok(STEERING_GROUP, "set_event_cancelled", args("eventId", event, "cancelled", true)).get("cancelled").asBoolean()).isTrue();
        assertThat(ok(STEERING_GROUP, "set_event_cancelled", args("eventId", event, "cancelled", false)).get("cancelled").asBoolean()).isFalse();

        final long line = ok(DIRECTEUR, "create_budget_line", args("revivalId", revival, "category", "FOOD", "description", "Mat",
                "unitAmount", "1200")).get("id").asLong();

        ok(DIRECTEUR, "update_budget_line", args("revivalId", revival, "lineId", line, "unitAmount", "1450.50"));
        assertThat(ok(DIRECTEUR, "set_budget_line_removed", args("revivalId", revival, "lineId", line, "removed", true)).get("removed").asBoolean())
                .isTrue();
        assertThat(refused(call(STEERING_GROUP, "create_budget_line", args("revivalId", revival, "category", "FOOD", "description", "x",
                "unitAmount", "1")))).isTrue();
        assertThat(ok(OBSERVER, "calculate_budget_scenario", args("revivalId", revival, "parameters", Map.of("participants", 40))).isObject()).isTrue();

        final long material = ok(STEERING_GROUP, "create_material", args("revivalId", revival, "title", "Manus", "url", "https://drive.google.com/x",
                "category", "SCRIPT", "visibility", "STEERING_GROUP")).get("id").asLong();

        assertThat(ok(STEERING_GROUP, "set_material_removed", args("revivalId", revival, "materialId", material, "removed", true))
                .get("removed").asBoolean()).isTrue();
        assertThat(refused(call(OBSERVER, "list_materials", args("revivalId", revival)))).isTrue();

        final long show = ok(STEERING_GROUP, "create_event", args("revivalId", revival, "title", "Föreställning", "startDate", "2028-02-10"))
                .get("id").asLong();
        final long slot = ok(DIRECTEUR, "create_shift_slot", args("eventId", show, "date", "2028-02-10", "startTime", "17:00", "endTime", "19:00",
                "task", "Biljettkontroll", "needed", 2)).get("id").asLong();

        assertThat(ok(DIRECTEUR, "update_shift_slot", args("revivalId", revival, "slotId", slot, "date", "2028-02-10", "startTime", "17:30",
                "endTime", "19:00", "task", "Biljettkontroll", "needed", 3)).get("open").asInt()).isEqualTo(3);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM revinfo WHERE source = 'MCP'").query(Integer.class).single()).isPositive();
    }

    @Test
    void checklistsAndTheRevivalItself() {
        final long checklist = ok(STEERING_GROUP, "create_event_checklist", args("revivalId", revival, "name", "Kalas", "date", "2028-02-12"))
                .get("id").asLong();
        final long item = ok(STEERING_GROUP, "create_checklist_item", args("checklistId", checklist, "text", "Boka band", "owner", "me"))
                .get("id").asLong();

        assertThat(ok(STEERING_GROUP, "set_checklist_item_state", args("itemId", item, "state", "NOT_APPLICABLE", "reason", "Inget band i år"))
                .get("state").asString()).isEqualTo("NOT_APPLICABLE");
        assertThat(ok(STEERING_GROUP, "get_checklist", args("checklistId", checklist)).get("items").size()).isEqualTo(1);
        assertThat(ok(STEERING_GROUP, "cancel_event_checklist", args("checklistId", checklist)).get("cancelled").asBoolean()).isTrue();

        final JsonNode updated = ok(SUPER_ADMIN, "update_revival", args("revivalId", revival, "premiereDate", "2028-02-11",
                "shows", List.of(Map.of("date", "2028-02-11", "venue", "RunAn"))));

        assertThat(updated.get("shows").get(0).get("venue").asString()).isEqualTo("RunAn");
        assertThat(ok(PLAIN_USER, "list_upcoming_revivals", args()).get("items").get(0).get("premiereDate").asString()).isEqualTo("2028-02-11");
        assertThat(refused(call(STEERING_GROUP, "change_revival_status", args("revivalId", revival, "status", "ARCHIVED")))).isTrue();
    }

    @Test
    void theSuperAdminKeepsTheListsTheGuideDraftAndTheNews() {
        final String area = ok(SUPER_ADMIN, "create_catalog_entry", args("catalog", "CAPABILITIES", "nameSv", "Scenografi", "nameEn", "Set design"))
                .get("id").asString();

        assertThat(ok(SUPER_ADMIN, "update_catalog_entry", args("catalog", "CAPABILITIES", "id", area, "nameSv", "Scen", "position", 0))
                .get("position").asInt()).isZero();
        assertThat(ok(SUPER_ADMIN, "set_catalog_entry_retired", args("catalog", "CAPABILITIES", "id", area, "retired", true)).get("retired").asBoolean())
                .isTrue();
        assertThat(refused(call(STEERING_GROUP, "create_catalog_entry", args("catalog", "CAPABILITIES", "nameSv", "x")))).isTrue();
        assertThat(ok(STEERING_GROUP, "list_catalog_entries", args("catalog", "BUDGET_CATEGORIES")).get("items").size()).isPositive();

        final long template = ok(SUPER_ADMIN, "create_checklist_template", args("kind", "EVENT", "nameSv", "Kalas")).get("id").asLong();

        ok(SUPER_ADMIN, "create_checklist_template_item", args("templateId", template, "textSv", "Boka lokal",
                "deadline", Map.of("kind", "DAYS", "amount", -30)));
        assertThat(ok(SUPER_ADMIN, "create_mailing_template", args("template", Map.of("nameSv", "Välkommen", "subjectSv", "Välkommen",
                "bodySv", "Hej!", "audience", "PARTICIPANTS", "due", Map.of("kind", "DAYS", "amount", -60)))).get("id").isNumber()).isTrue();

        final long news = ok(SUPER_ADMIN, "create_news", args("subject", "Nytt", "text", "Text", "visibleFrom", "2026-01-01")).get("id").asLong();

        ok(SUPER_ADMIN, "update_news", args("newsId", news, "subject", "Nyare", "text", "Text", "visibleFrom", "2026-01-01"));
        assertThat(ok(PLAIN_USER, "list_news", args()).get("items").get(0).get("subject").asString()).isEqualTo("Nyare");
        assertThat(refused(call(STEERING_GROUP, "create_news", args("subject", "x", "text", "y")))).isTrue();

        publishedGuide();
        ok(SUPER_ADMIN, "start_guide_draft", args());

        final long chapter = ok(SUPER_ADMIN, "create_guide_chapter", args("titleSv", "Efterarbete")).get("id").asLong();
        final long item = ok(SUPER_ADMIN, "create_guide_item", args("chapterId", chapter, "type", "TIP", "textSv", "Tacka alla")).get("id").asLong();

        ok(SUPER_ADMIN, "update_guide_item", args("itemId", item, "type", "TIP", "textSv", "Tacka alla som hjälpt till"));
        assertThat(ok(SUPER_ADMIN, "move_guide_part", args("part", "CHAPTER", "id", chapter, "position", 0)).get("chapters").get(0).get("id").asLong())
                .isEqualTo(chapter);
        assertThat(refused(call(STEERING_GROUP, "get_guide_draft", args()))).isTrue();
    }
}
