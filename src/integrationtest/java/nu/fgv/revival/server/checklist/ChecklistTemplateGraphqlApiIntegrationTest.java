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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChecklistTemplateGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String TEMPLATE_FIELDS = "id kind phase name { sv en } retired version "
            + "items { id position text { sv en } guideItemKey guideItem { type } defaultCapability deadline { kind amount weekday } version }";
    private static final String CREATE = "mutation($input: ChecklistTemplateCreateInput!) { checklistTemplateCreate(input: $input) { " + TEMPLATE_FIELDS + " } }";
    private static final String ITEM_CREATE = "mutation($input: ChecklistTemplateItemCreateInput!) { checklistTemplateItemCreate(input: $input) { id position version } }";
    private static final String LIST = "query($all: Boolean) { checklistTemplates(includeRetired: $all) { id kind phase name { sv } retired } }";
    private static final String CHECKLIST_FIELDS = "id kind name date cancelled version template { id name { sv } } templatesAvailable { id } "
            + "items { id text { sv } deadline deadlineManual defaultCapability owner { person { displayName } } version }";
    private static final String EVENT_CREATE = "mutation($input: EventChecklistCreateInput!) { eventChecklistCreate(input: $input) { " + CHECKLIST_FIELDS + " } }";
    private static final String EVENT_UPDATE = "mutation($input: EventChecklistUpdateInput!) { eventChecklistUpdate(input: $input) { " + CHECKLIST_FIELDS + " } }";
    private static final String ADD = "mutation($input: ChecklistAddFromTemplateInput!) { checklistAddFromTemplate(input: $input) { " + CHECKLIST_FIELDS + " } }";

    @BeforeEach
    void guide() {
        publishedGuide();
    }

    private long template(final Map<String, Object> input) {
        return Long.parseLong(execute(SUPER_ADMIN, CREATE, Map.of("input", input)).path("checklistTemplateCreate.id").entity(String.class).get());
    }

    private long item(final long template, final Map<String, Object> fields) {
        final Map<String, Object> input = new HashMap<>(fields);

        input.put("templateId", template);
        return Long.parseLong(execute(SUPER_ADMIN, ITEM_CREATE, Map.of("input", input)).path("checklistTemplateItemCreate.id").entity(String.class).get());
    }

    private long rehearsalTemplate() {
        final long template = template(Map.of("kind", "EVENT", "name", Map.of("sv", "Rephelg", "en", "Rehearsal weekend")));

        item(template, Map.of("text", Map.of("sv", "Boka replokal"), "defaultCapability", "FINANCE",
                "deadline", Map.of("kind", "DAYS", "amount", -14)));
        item(template, Map.of("text", Map.of("sv", "Hämta nycklar"), "deadline", Map.of("kind", "WEEKDAY_WEEK_BEFORE", "weekday", "MONDAY")));
        item(template, Map.of("text", Map.of("sv", "Skicka schema"), "deadline", Map.of("kind", "WORKING_DAYS", "amount", -5)));
        return template;
    }

    private long revivalWithTreasurer() {
        final long revival = createRevival(ALI_BABA, 2028, "Ali Baba 2028");

        setStatus(revival, "APPROVED");
        login(DIRECTEUR);
        login(STEERING_GROUP);
        addMember(revival, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(revival, STEERING_GROUP, "STEERING_GROUP", List.of("FINANCE"));
        setStatus(revival, "ORGANISING");
        return revival;
    }

    private long checklistVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM checklist WHERE id = ?").param(id).query(Long.class).single();
    }

    @Test
    void superAdminsManageTemplatesAndOthersOnlyRead() {
        final long template = rehearsalTemplate();

        execute(SUPER_ADMIN, "query($id: ID!) { checklistTemplate(id: $id) { " + TEMPLATE_FIELDS + " } }", Map.of("id", template))
                .path("checklistTemplate.name.en").entity(String.class).isEqualTo("Rehearsal weekend")
                .path("checklistTemplate.items[*].position").entityList(Integer.class).containsExactly(0, 1, 2)
                .path("checklistTemplate.items[1].deadline.weekday").entity(String.class).isEqualTo("MONDAY")
                .path("checklistTemplate.items[0].defaultCapability").entity(String.class).isEqualTo("FINANCE");
        expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", Map.of("kind", "EVENT"))), "BAD_REQUEST");
        template(Map.of("kind", "PHASE", "phase", "C"));
        expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", Map.of("kind", "PHASE", "phase", "C"))), "CONFLICT");

        final long retired = template(Map.of("kind", "EVENT", "name", Map.of("sv", "Gammal")));

        execute(SUPER_ADMIN, "mutation($id: ID!) { checklistTemplateRetire(id: $id, version: 0) { retired } }", Map.of("id", retired))
                .path("checklistTemplateRetire.retired").entity(Boolean.class).isEqualTo(true);
        execute(SUPER_ADMIN, LIST, Map.of("all", true)).path("checklistTemplates").entityList(Object.class).hasSize(3);

        revivalWithTreasurer();
        for (final String user : List.of(OBSERVER, DIRECTEUR, STEERING_GROUP)) {
            execute(user, LIST, Map.of("all", true)).path("checklistTemplates").entityList(Object.class).hasSize(2);
            expectError(execute(user, CREATE, Map.of("input", Map.of("kind", "PHASE", "phase", "D"))), "FORBIDDEN");
            expectError(execute(user, ITEM_CREATE, Map.of("input", Map.of("templateId", template, "text", Map.of("sv", "x")))), "FORBIDDEN");
            expectError(execute(user, "mutation($id: ID!) { checklistTemplateRetire(id: $id, version: 0) { id } }", Map.of("id", template)), "FORBIDDEN");
        }
        login(PLAIN_USER);
        expectError(execute(PLAIN_USER, LIST, Map.of()), "FORBIDDEN");
    }

    @Test
    void theSteeringGroupMakesAnEventChecklistFromATemplateAndMovesItsDate() {
        final long template = rehearsalTemplate();
        final long revival = revivalWithTreasurer();

        final String id = execute(STEERING_GROUP, EVENT_CREATE, Map.of("input", Map.of("revivalId", revival, "name", "Rephelg 2",
                "date", "2027-11-20", "templateId", template)))
                .path("eventChecklistCreate.template.name.sv").entity(String.class).isEqualTo("Rephelg")
                .path("eventChecklistCreate.items[*].deadline").entityList(String.class).containsExactly("2027-11-06", "2027-11-08", "2027-11-15")
                .path("eventChecklistCreate.items[0].owner.person.displayName").entity(String.class).isEqualTo("Stina Styrgrupp")
                .path("eventChecklistCreate.items[1].owner").valueIsNull()
                .path("eventChecklistCreate.templatesAvailable[*].id").entityList(String.class).containsExactly(String.valueOf(template))
                .path("eventChecklistCreate.id").entity(String.class).get();
        final long checklist = Long.parseLong(id);
        final long manual = Long.parseLong(execute(STEERING_GROUP, "query($id: ID!) { checklist(id: $id) { items { id } } }", Map.of("id", checklist))
                .path("checklist.items[2].id").entity(String.class).get());
        final long manualVersion = jdbcClient.sql("SELECT version FROM checklist_item WHERE id = ?").param(manual).query(Long.class).single();

        execute(STEERING_GROUP, "mutation($input: ChecklistItemUpdateInput!) { checklistItemUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", manual, "version", manualVersion, "deadline", "2027-11-10")));
        execute(STEERING_GROUP, EVENT_UPDATE, Map.of("input", Map.of("id", checklist, "version", checklistVersion(checklist), "date", "2027-11-27")))
                .path("eventChecklistUpdate.date").entity(String.class).isEqualTo("2027-11-27")
                .path("eventChecklistUpdate.items[*].deadline").entityList(String.class).containsExactly("2027-11-13", "2027-11-15", "2027-11-10")
                .path("eventChecklistUpdate.items[2].deadlineManual").entity(Boolean.class).isEqualTo(true);

        item(template, Map.of("text", Map.of("sv", "Städa lokalen")));
        execute(STEERING_GROUP, ADD, Map.of("input", Map.of("checklistId", checklist, "version", checklistVersion(checklist), "templateId", template)))
                .path("checklistAddFromTemplate.items[*].text.sv").entityList(String.class)
                .containsExactly("Boka replokal", "Hämta nycklar", "Skicka schema", "Städa lokalen");
        execute(STEERING_GROUP, ADD, Map.of("input", Map.of("checklistId", checklist, "version", checklistVersion(checklist), "templateId", template)))
                .path("checklistAddFromTemplate.items").entityList(Object.class).hasSize(4);

        execute(STEERING_GROUP, "mutation($id: ID!, $version: Int!) { eventChecklistCancel(id: $id, version: $version) { cancelled templatesAvailable { id } } }",
                Map.of("id", checklist, "version", checklistVersion(checklist)))
                .path("eventChecklistCancel.cancelled").entity(Boolean.class).isEqualTo(true)
                .path("eventChecklistCancel.templatesAvailable").entityList(Object.class).hasSize(0);
        execute(STEERING_GROUP, "query($id: ID!) { checklists(revivalId: $id) { kind } }", Map.of("id", revival))
                .path("checklists").entityList(Object.class).hasSize(10);
        expectError(execute(OBSERVER, EVENT_CREATE, Map.of("input", Map.of("revivalId", revival, "name", "Kalas"))), "FORBIDDEN");
    }

    @Test
    void aNewRevivalGetsThePhaseTemplatesItemsAfterTheRequiredOnes() {
        final long template = template(Map.of("kind", "PHASE", "phase", "A"));

        item(template, Map.of("text", Map.of("sv", "Skriv en tidsplan"), "defaultCapability", "COMMS"));
        expectError(execute(SUPER_ADMIN, ITEM_CREATE, Map.of("input", Map.of("templateId", template, "text", Map.of("sv", "x"),
                "deadline", Map.of("kind", "DAYS", "amount", 1)))), "BAD_REQUEST");

        final long revival = revivalWithTreasurer();
        final long phaseA = jdbcClient.sql("SELECT id FROM checklist WHERE revival_id = ? AND phase = 'A'").param(revival).query(Long.class).single();

        execute(STEERING_GROUP, "query($id: ID!) { checklist(id: $id) { items { required text { sv } defaultCapability } templatesAvailable { id } } }",
                Map.of("id", phaseA))
                .path("checklist.items[*].required").entityList(Boolean.class).containsExactly(true, false)
                .path("checklist.items[1].text.sv").entity(String.class).isEqualTo("Skriv en tidsplan")
                .path("checklist.items[1].defaultCapability").entity(String.class).isEqualTo("COMMS")
                .path("checklist.templatesAvailable[*].id").entityList(String.class).containsExactly(String.valueOf(template));
        execute(STEERING_GROUP, ADD, Map.of("input", Map.of("checklistId", phaseA, "version", checklistVersion(phaseA))))
                .path("checklistAddFromTemplate.items").entityList(Object.class).hasSize(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM checklist_item WHERE template_item_id IS NOT NULL").query(Integer.class).single()).isEqualTo(1);
    }
}
