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


package nu.fgv.revival.server.checklist;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chapter checklists over GraphQL against MySQL and Keycloak (US4, FR-022–FR-029).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class ChecklistGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    static final String ITEM_FIELDS = "id required text { sv en } state reason deadline deadlineManual overdue comment version "
            + "owner { name person { displayName } } stateChangedBy { displayName } guideItem { key type } checklist { id chapterKey chapterTitle { sv } } logEntries { number }";
    private static final String LIST = "query($id: ID!) { checklists(revivalId: $id) { id kind chapterKey openCount overdueCount totalCount } }";
    private static final String GET = "query($id: ID!) { checklist(id: $id) { id chapterKey chapterTitle { sv } tips { type text { sv } } items { " + ITEM_FIELDS + " } } }";
    private static final String ITEMS = "query($id: ID!, $filter: ChecklistItemFilter) { checklistItems(revivalId: $id, filter: $filter) { id text { sv } } }";
    static final String SET_STATE = "mutation($input: ChecklistItemSetStateInput!) { checklistItemSetState(input: $input) { " + ITEM_FIELDS + " } }";
    private static final String CREATE = "mutation($input: ChecklistItemCreateInput!) { checklistItemCreate(input: $input) { " + ITEM_FIELDS + " } }";
    private static final String UPDATE = "mutation($input: ChecklistItemUpdateInput!) { checklistItemUpdate(input: $input) { " + ITEM_FIELDS + " } }";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Stockholm"));

    private long revival;

    @BeforeEach
    void pinnedRevival() {
        publishedGuide();
        revival = organisingRevival();
    }

    long chapterChecklist(final String chapter) {
        return jdbcClient.sql("SELECT id FROM checklist WHERE revival_id = ? AND chapter_key = ?").param(revival).param(chapter).query(Long.class).single();
    }

    long requiredItem(final String chapter) {
        return jdbcClient.sql("SELECT i.id FROM checklist_item i JOIN checklist c ON c.id = i.checklist_id WHERE c.revival_id = ? AND c.chapter_key = ? AND i.required = 1")
                .param(revival).param(chapter).query(Long.class).single();
    }

    long itemVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM checklist_item WHERE id = ?").param(id).query(Long.class).single();
    }

    @Test
    void aRevivalHasAChecklistPerChapterWithTheGuidesRequirementsAndTips() {
        execute(STEERING_GROUP, LIST, Map.of("id", revival))
                .path("checklists[*].chapterKey").entityList(String.class).containsExactly("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
                .path("checklists[0].openCount").entity(Integer.class).isEqualTo(1)
                .path("checklists[1].totalCount").entity(Integer.class).isEqualTo(0);
        execute(STEERING_GROUP, GET, Map.of("id", chapterChecklist("A")))
                .path("checklist.chapterTitle.sv").entity(String.class).isEqualTo("Först: godkännande och tajmning")
                .path("checklist.tips[*].type").entityList(String.class).containsExactly("TIP", "TIP")
                .path("checklist.items[0].required").entity(Boolean.class).isEqualTo(true)
                .path("checklist.items[0].text.sv").entity(String.class).isEqualTo("Nyuppsättningen ska godkännas av JK och FGV innan arbetet startar.")
                .path("checklist.items[0].guideItem.type").entity(String.class).isEqualTo("REQUIREMENT");
        execute(STEERING_GROUP, GET, Map.of("id", chapterChecklist("J")))
                .path("checklist.items[0].text.sv").entity(String.class).isEqualTo("Ett eventuellt lån ska återbetalas.");
        execute(STEERING_GROUP, "query($id: ID!) { logEntries(revivalId: $id) { number type status text origin owner { name } } }", Map.of("id", revival))
                .path("logEntries[0].type").entity(String.class).isEqualTo("QUESTION")
                .path("logEntries[0].origin").entity(String.class).isEqualTo("GUIDE")
                .path("logEntries[0].text").entity(String.class).isEqualTo("Vilka datum har sittande spex?")
                .path("logEntries[0].owner").valueIsNull();
    }

    @Test
    void onlyADirecteurWaivesARequirementAndOnlyWithAReason() {
        final long item = requiredItem("A");

        expectError(execute(STEERING_GROUP, SET_STATE, Map.of("input", Map.of("id", item, "version", itemVersion(item), "state", "NOT_APPLICABLE",
                "reason", "Godkänt muntligt"))), "FORBIDDEN");
        expectError(execute(DIRECTEUR, SET_STATE, Map.of("input", Map.of("id", item, "version", itemVersion(item), "state", "NOT_APPLICABLE"))),
                "BAD_REQUEST");
        execute(DIRECTEUR, SET_STATE, Map.of("input", Map.of("id", item, "version", itemVersion(item), "state", "NOT_APPLICABLE",
                "reason", "Godkänt muntligt")))
                .path("checklistItemSetState.state").entity(String.class).isEqualTo("NOT_APPLICABLE")
                .path("checklistItemSetState.reason").entity(String.class).isEqualTo("Godkänt muntligt")
                .path("checklistItemSetState.stateChangedBy.displayName").entity(String.class).isEqualTo("Dana Directeur");
        execute(STEERING_GROUP, SET_STATE, Map.of("input", Map.of("id", item, "version", itemVersion(item), "state", "DONE")))
                .path("checklistItemSetState.state").entity(String.class).isEqualTo("DONE");
        expectError(execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", item, "version", itemVersion(item), "text", "Annat"))), "BAD_REQUEST");
    }

    @Test
    void membersAddAndWorkItemsAndFilterThem() {
        final long stina = personId(STEERING_GROUP);
        final String created = execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("checklistId", chapterChecklist("B"), "text", "Boka RunAn",
                "owner", Map.of("personId", stina), "deadline", TODAY.minusDays(1).toString())))
                .path("checklistItemCreate.deadlineManual").entity(Boolean.class).isEqualTo(true)
                .path("checklistItemCreate.overdue").entity(Boolean.class).isEqualTo(true)
                .path("checklistItemCreate.id").entity(String.class).get();
        final long item = Long.parseLong(created);

        execute(STEERING_GROUP, ITEMS, Map.of("id", revival, "filter", Map.of("mine", true))).path("checklistItems[*].text.sv")
                .entityList(String.class).containsExactly("Boka RunAn");
        execute(STEERING_GROUP, ITEMS, Map.of("id", revival, "filter", Map.of("overdue", true))).path("checklistItems").entityList(Object.class).hasSize(1);
        execute(STEERING_GROUP, ITEMS, Map.of("id", revival, "filter", Map.of("requiredOnly", true))).path("checklistItems").entityList(Object.class).hasSize(3);
        execute(DIRECTEUR, ITEMS, Map.of("id", revival, "filter", Map.of("mine", true))).path("checklistItems").entityList(Object.class).hasSize(0);

        execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", item, "version", itemVersion(item), "text", "Boka RunAn och Kårhuset",
                "comment", "Ring på måndag")))
                .path("checklistItemUpdate.text.sv").entity(String.class).isEqualTo("Boka RunAn och Kårhuset");
        execute(STEERING_GROUP, SET_STATE, Map.of("input", Map.of("id", item, "version", itemVersion(item), "state", "NOT_APPLICABLE")))
                .path("checklistItemSetState.state").entity(String.class).isEqualTo("NOT_APPLICABLE")
                .path("checklistItemSetState.overdue").entity(Boolean.class).isEqualTo(false);

        execute(STEERING_GROUP, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { checklistItem { id state text { sv } } } }",
                Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "Följ upp", "checklistItemId", item)))
                .path("logEntryCreate.checklistItem.text.sv").entity(String.class).isEqualTo("Boka RunAn och Kårhuset");
        execute(STEERING_GROUP, GET, Map.of("id", chapterChecklist("B")))
                .path("checklist.items[0].logEntries[*].number").entityList(Integer.class).containsExactly(2);
    }

    @Test
    void readersAndRefusals() {
        final long item = requiredItem("C");

        for (final String user : List.of(SUPER_ADMIN, OBSERVER)) {
            execute(user, LIST, Map.of("id", revival)).path("checklists").entityList(Object.class).hasSize(10);
            expectError(execute(user, SET_STATE, Map.of("input", Map.of("id", item, "version", itemVersion(item), "state", "DONE"))), "FORBIDDEN");
            expectError(execute(user, CREATE, Map.of("input", Map.of("checklistId", chapterChecklist("C"), "text", "x"))), "FORBIDDEN");
        }
        login(PLAIN_USER);
        expectError(execute(PLAIN_USER, LIST, Map.of("id", revival)), "NOT_FOUND");
        expectError(execute(PLAIN_USER, GET, Map.of("id", chapterChecklist("C"))), "NOT_FOUND");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM checklist_item WHERE revival_id = ?").param(revival).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void theRevivalPageCountsOpenAndOverdueWork() {
        execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("checklistId", chapterChecklist("B"), "text", "Boka RunAn",
                "deadline", TODAY.minusDays(1).toString())));
        execute(STEERING_GROUP, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "Försenad", "deadline", TODAY.minusDays(1).toString())));
        execute(STEERING_GROUP, "mutation($input: MeetingCreateInput!) { meetingCreate(input: $input) { id version } }",
                Map.of("input", Map.of("revivalId", revival, "date", TODAY.toString())));

        final long meeting = jdbcClient.sql("SELECT id FROM meeting WHERE revival_id = ?").param(revival).query(Long.class).single();

        execute(STEERING_GROUP, "mutation($id: ID!) { meetingStart(id: $id, version: 0) { id } }", Map.of("id", meeting));
        execute(OBSERVER, "query($id: ID!) { revival(id: $id) { workSummary { openEntries overdueEntries overdueChecklistItems openRequiredItems "
                + "meetingInProgress { id status } } } }", Map.of("id", revival))
                .path("revival.workSummary.openEntries").entity(Integer.class).isEqualTo(2)
                .path("revival.workSummary.overdueEntries").entity(Integer.class).isEqualTo(1)
                .path("revival.workSummary.overdueChecklistItems").entity(Integer.class).isEqualTo(1)
                .path("revival.workSummary.openRequiredItems").entity(Integer.class).isEqualTo(3)
                .path("revival.workSummary.meetingInProgress.status").entity(String.class).isEqualTo("IN_PROGRESS");
        login(PLAIN_USER);
        expectError(execute(PLAIN_USER, "query($id: ID!) { revival(id: $id) { workSummary { openEntries } } }", Map.of("id", revival)), "NOT_FOUND");
    }
}
