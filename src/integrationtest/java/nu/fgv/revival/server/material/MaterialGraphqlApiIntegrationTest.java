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
package nu.fgv.revival.server.material;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Materials, the inventory and the link services end to end (M8): links recognised by host, refused unless https,
 * visibility per position for participants, nothing for observers and helpers, the inventory with owners, the super
 * admin's list of services, and deletion of the revival taking everything. Participants are pending people.
 *
 * @author Anders Jacobsson
 */
class MaterialGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String MATERIAL_FIELDS = "id title url category visibility comment removed version service { id } positions { id name }";
    private static final String MATERIALS = "query($id: ID!, $removed: Boolean) { materials(revivalId: $id, includeRemoved: $removed) { " + MATERIAL_FIELDS + " } }";
    private static final String CREATE = "mutation($input: MaterialInput!) { materialCreate(input: $input) { " + MATERIAL_FIELDS + " } }";
    private static final String UPDATE = "mutation($id: ID!, $v: Int!, $input: MaterialInput!) { materialUpdate(id: $id, version: $v, input: $input) { "
            + MATERIAL_FIELDS + " } }";
    private static final String ITEM_FIELDS = """
            id name kind quantity status location locationNote comment removed version owner { name person { id displayName active } } material { id title }""";
    private static final String INVENTORY = "query($id: ID!, $removed: Boolean) { inventory(revivalId: $id, includeRemoved: $removed) { " + ITEM_FIELDS + " } }";
    private static final String ITEM_CREATE = "mutation($input: InventoryItemInput!) { inventoryItemCreate(input: $input) { " + ITEM_FIELDS + " } }";
    private static final String MY_MATERIALS = "query($id: ID!) { myParticipation(revivalId: $id) { materials { title service { id } } } }";
    private static final String SERVICES = "query($all: Boolean) { linkServices(includeRetired: $all) { id name { sv en } hosts homeUrl position retired inUse version } }";

    private long revival;
    private long participant;

    private static Map<String, Object> material(final String title, final String url, final String visibility, final Object... extra) {
        final Map<String, Object> input = new HashMap<>(Map.of("title", title, "url", url, "category", "SCRIPT", "visibility", visibility));

        for (int index = 0; index < extra.length; index += 2) {
            input.put((String) extra[index], extra[index + 1]);
        }
        return input;
    }

    @BeforeEach
    void openRevivalWithAParticipant() {
        revival = organisingRevival();
        setStatus(revival, "OPEN");
        SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing("query Tasks"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"taskPaged": {"edges": [
                          {"node": {"id": "BAND", "name": "Orkester", "category": {"id": "2", "name": "Orkester", "actorPresent": false}}},
                          {"node": {"id": "ENSEMBLE", "name": "Ensemble", "category": {"id": "1", "name": "Ensemble", "actorPresent": true}}}],
                          "totalCount": 2}}}""")));
        participant = pending("PARTICIPANT", "Pia", "Deltagare", PARTICIPANT);
        invite(participant, pending("HELPER", "Hugo", "Springare", HELPER));
        login(PARTICIPANT);
        login(HELPER);
        setPosition("BAND");
    }

    private long pending(final String kind, final String firstName, final String lastName, final String email) {
        return Long.parseLong(execute(STEERING_GROUP, "mutation($input: ParticipantAddInput!) { participantAdd(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "kind", kind, "firstName", firstName, "lastName", lastName, "email", email)))
                .path("participantAdd.id").entity(String.class).get());
    }

    private void invite(final long... ids) {
        execute(STEERING_GROUP, "mutation($input: ParticipantsInviteInput!) { participantsInvite(input: $input) { invited { id } } }",
                Map.of("input", Map.of("revivalId", revival, "participantIds", Arrays.stream(ids).boxed().toList(), "language", "sv")))
                .path("participantsInvite.invited").entityList(Object.class).hasSize(ids.length);
    }

    private void setPosition(final String positionId) {
        final long version = jdbcClient.sql("SELECT version FROM participant WHERE id = ?").param(participant).query(Long.class).single();
        final Map<String, Object> input = new HashMap<>(Map.of("id", participant, "version", version));

        if (positionId == null) {
            input.put("clearPosition", true);
        } else {
            input.put("positionId", positionId);
        }
        execute(STEERING_GROUP, "mutation($input: ParticipantUpdateInput!) { participantUpdate(input: $input) { id } }", Map.of("input", input));
    }

    private Map<String, Object> create(final Map<String, Object> input) {
        final Map<String, Object> withRevival = new HashMap<>(input);

        withRevival.put("revivalId", revival);
        return withRevival;
    }

    private long materialVersion(final String id) {
        return jdbcClient.sql("SELECT version FROM material WHERE id = ?").param(Long.parseLong(id)).query(Long.class).single();
    }

    private List<String> titles(final String user) {
        return execute(user, MATERIALS, Map.of("id", revival)).path("materials[*].title").entityList(String.class).get();
    }

    @Test
    void theSteeringGroupAddsLinksAndParticipantsSeeWhatIsForThem() {
        final String drive = execute(STEERING_GROUP, CREATE, Map.of("input", create(material("Manus", "https://docs.google.com/document/d/abc",
                "PARTICIPANTS", "comment", "Senaste versionen"))))
                .path("materialCreate.service.id").entity(String.class).isEqualTo("GOOGLE_DRIVE")
                .path("materialCreate.id").entity(String.class).get();
        final String film = execute(DIRECTEUR, CREATE, Map.of("input", create(material("Originalet på film", "https://spexflix.example.org/v/1",
                "POSITIONS", "serviceId", "SPEXFLIX", "positionIds", List.of("BAND")))))
                .path("materialCreate.service.id").entity(String.class).isEqualTo("SPEXFLIX")
                .path("materialCreate.positions[0].name").entity(String.class).isEqualTo("Orkester")
                .path("materialCreate.id").entity(String.class).get();

        execute(STEERING_GROUP, CREATE, Map.of("input", create(material("Budgetunderlag", "https://example.org/budget.pdf", "STEERING_GROUP"))))
                .path("materialCreate.service").valueIsNull();

        // Links must be https, with a host and without credentials
        for (final String url : List.of("http://docs.google.com/x", "javascript:alert(1)", "https://kim:hemligt@example.org/x", "https://exa mple.org",
                "https:///x")) {
            expectError(execute(STEERING_GROUP, CREATE, Map.of("input", create(material("Fel", url, "PARTICIPANTS")))), "BAD_REQUEST");
        }
        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", create(material("Fel", "https://youtu.be/x", "POSITIONS")))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", create(material("Fel", "https://youtu.be/x", "POSITIONS",
                "positionIds", List.of("NOPE"))))), "BAD_REQUEST");

        // Who reads what
        assertThat(titles(STEERING_GROUP)).containsExactly("Budgetunderlag", "Manus", "Originalet på film");
        assertThat(titles(SUPER_ADMIN)).containsExactly("Budgetunderlag", "Manus", "Originalet på film");
        assertThat(titles(PARTICIPANT)).containsExactly("Manus", "Originalet på film");
        execute(PARTICIPANT, MY_MATERIALS, Map.of("id", revival)).path("myParticipation.materials[*].title").entityList(String.class)
                .containsExactly("Manus", "Originalet på film");
        execute(HELPER, MY_MATERIALS, Map.of("id", revival)).path("myParticipation.materials").entityList(Object.class).hasSize(0);
        expectError(execute(HELPER, MATERIALS, Map.of("id", revival)), "FORBIDDEN");
        expectError(execute(OBSERVER, MATERIALS, Map.of("id", revival)), "FORBIDDEN");
        execute(DIRECTEUR, "query($id: ID!) { revival(id: $id) { permissions { canWriteMaterials } } }", Map.of("id", revival))
                .path("revival.permissions.canWriteMaterials").entity(Boolean.class).isEqualTo(true);
        execute(SUPER_ADMIN, "query($id: ID!) { revival(id: $id) { permissions { canWriteMaterials } } }", Map.of("id", revival))
                .path("revival.permissions.canWriteMaterials").entity(Boolean.class).isEqualTo(false);

        // Without the position, the participant no longer sees it
        setPosition("ENSEMBLE");
        assertThat(titles(PARTICIPANT)).containsExactly("Manus");

        // Only the steering group writes
        expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", create(material("Nej", "https://youtu.be/x", "PARTICIPANTS")))), "FORBIDDEN");
        expectError(execute(PARTICIPANT, "mutation($id: ID!, $v: Int!) { materialRemove(id: $id, version: $v) { id } }",
                Map.of("id", drive, "v", materialVersion(drive))), "NOT_FOUND");

        // Removed and restored, never deleted
        execute(STEERING_GROUP, "mutation($id: ID!, $v: Int!) { materialRemove(id: $id, version: $v) { removed } }",
                Map.of("id", drive, "v", materialVersion(drive))).path("materialRemove.removed").entity(Boolean.class).isEqualTo(true);
        assertThat(titles(PARTICIPANT)).isEmpty();
        execute(STEERING_GROUP, MATERIALS, Map.of("id", revival, "removed", true)).path("materials[*].title").entityList(String.class).hasSize(3);
        execute(SUPER_ADMIN, MATERIALS, Map.of("id", revival, "removed", true)).path("materials[*].title").entityList(String.class).hasSize(2);
        expectError(execute(STEERING_GROUP, "mutation($id: ID!, $v: Int!) { materialRemove(id: $id, version: $v) { removed } }",
                Map.of("id", drive, "v", 0)), "CONFLICT");
        execute(STEERING_GROUP, "mutation($id: ID!, $v: Int!) { materialRestore(id: $id, version: $v) { removed } }",
                Map.of("id", drive, "v", materialVersion(drive))).path("materialRestore.removed").entity(Boolean.class).isEqualTo(false);

        // A changed link is recognised again, unless a service is chosen
        execute(STEERING_GROUP, UPDATE, Map.of("id", film, "v", materialVersion(film), "input", material("Originalet på film",
                "https://www.youtube.com/watch?v=abc", "PARTICIPANTS")))
                .path("materialUpdate.service.id").entity(String.class).isEqualTo("YOUTUBE")
                .path("materialUpdate.positions").entityList(Object.class).hasSize(0);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM material_audit").query(Integer.class).single()).isGreaterThanOrEqualTo(6);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM material_position_audit").query(Integer.class).single()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void positionsAlreadyHeldAreKeptWhileSpexregisterIsDown() {
        final String id = execute(STEERING_GROUP, CREATE, Map.of("input", create(material("Arr", "https://1drv.ms/x", "POSITIONS",
                "positionIds", List.of("BAND"))))).path("materialCreate.id").entity(String.class).get();

        spexregisterDown(false);
        execute(STEERING_GROUP, UPDATE, Map.of("id", id, "v", materialVersion(id), "input", material("Arr, ny", "https://1drv.ms/x", "POSITIONS",
                "positionIds", List.of("BAND"))))
                .path("materialUpdate.title").entity(String.class).isEqualTo("Arr, ny")
                .path("materialUpdate.positions[0].id").entity(String.class).isEqualTo("BAND");
        expectError(execute(STEERING_GROUP, UPDATE, Map.of("id", id, "v", materialVersion(id), "input", material("Arr", "https://1drv.ms/x",
                "POSITIONS", "positionIds", List.of("BAND", "ENSEMBLE")))), "SPEXREGISTER_UNAVAILABLE");
    }

    @Test
    void theInventoryHasOwnersStatusesAndMaterials() {
        final String script = execute(STEERING_GROUP, CREATE, Map.of("input", create(material("Manus", "https://docs.google.com/d/x", "PARTICIPANTS"))))
                .path("materialCreate.id").entity(String.class).get();
        final long directeur = personId(DIRECTEUR);
        final String crown = execute(STEERING_GROUP, ITEM_CREATE, Map.of("input", Map.of("revivalId", revival, "name", "Kungakrona", "kind", "PROPS",
                "status", "BUILD", "owner", Map.of("personId", directeur))))
                .path("inventoryItemCreate.quantity").entity(Integer.class).isEqualTo(1)
                .path("inventoryItemCreate.owner.person.displayName").entity(String.class).isEqualTo("Dana Directeur")
                .path("inventoryItemCreate.id").entity(String.class).get();

        execute(DIRECTEUR, ITEM_CREATE, Map.of("input", Map.of("revivalId", revival, "name", "Manus, utskrivna", "kind", "SCRIPT", "quantity", 30,
                "status", "HAVE", "location", "KEMIFORRADET", "locationNote", "Hylla 3", "owner", Map.of("name", "Kim Kostym"),
                "materialId", script)))
                .path("inventoryItemCreate.material.title").entity(String.class).isEqualTo("Manus")
                .path("inventoryItemCreate.owner.name").entity(String.class).isEqualTo("Kim Kostym");
        expectError(execute(STEERING_GROUP, ITEM_CREATE, Map.of("input", Map.of("revivalId", revival, "name", "Noll", "kind", "WIG", "quantity", 0,
                "status", "HAVE"))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, ITEM_CREATE, Map.of("input", Map.of("revivalId", revival, "name", " ", "kind", "WIG", "status", "HAVE"))),
                "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, ITEM_CREATE, Map.of("input", Map.of("revivalId", revival, "name", "Fel", "kind", "WIG", "status", "HAVE",
                "materialId", 999_999))), "BAD_REQUEST");
        expectError(execute(SUPER_ADMIN, ITEM_CREATE, Map.of("input", Map.of("revivalId", revival, "name", "Nej", "kind", "WIG", "status", "HAVE"))),
                "FORBIDDEN");

        execute(SUPER_ADMIN, INVENTORY, Map.of("id", revival)).path("inventory[*].name").entityList(String.class)
                .containsExactly("Kungakrona", "Manus, utskrivna");
        expectError(execute(OBSERVER, INVENTORY, Map.of("id", revival)), "FORBIDDEN");
        expectError(execute(PARTICIPANT, INVENTORY, Map.of("id", revival)), "FORBIDDEN");
        expectError(execute(HELPER, INVENTORY, Map.of("id", revival)), "FORBIDDEN");

        final long version = jdbcClient.sql("SELECT version FROM inventory_item WHERE id = ?").param(Long.parseLong(crown)).query(Long.class).single();

        execute(STEERING_GROUP, "mutation($id: ID!, $v: Int!, $input: InventoryItemInput!) { inventoryItemUpdate(id: $id, version: $v, input: $input) { "
                + ITEM_FIELDS + " } }", Map.of("id", crown, "v", version, "input", Map.of("name", "Kungakrona", "kind", "PROPS", "status", "HAVE",
                "location", "OTHER", "locationNote", "Hos Danne")))
                .path("inventoryItemUpdate.status").entity(String.class).isEqualTo("HAVE")
                .path("inventoryItemUpdate.owner").valueIsNull();
        execute(STEERING_GROUP, "mutation($id: ID!, $v: Int!) { inventoryItemRemove(id: $id, version: $v) { removed } }",
                Map.of("id", crown, "v", version + 1)).path("inventoryItemRemove.removed").entity(Boolean.class).isEqualTo(true);
        execute(STEERING_GROUP, INVENTORY, Map.of("id", revival)).path("inventory[*].name").entityList(String.class).containsExactly("Manus, utskrivna");
        execute(STEERING_GROUP, INVENTORY, Map.of("id", revival, "removed", true)).path("inventory").entityList(Object.class).hasSize(2);
    }

    @Test
    void theSuperAdminKeepsTheLinkServices() {
        execute(DIRECTEUR, SERVICES, Map.of()).path("linkServices[0].hosts").entityList(String.class).hasSize(0);
        execute(DIRECTEUR, SERVICES, Map.of()).path("linkServices[*].id").entityList(String.class)
                .containsExactly("SPEXFLIX", "MANUSARKIV", "SPEXIPEDIA", "SPEXIFY", "LATARKIV", "GOOGLE_DRIVE", "DROPBOX", "YOUTUBE", "ONEDRIVE");

        final String update = "mutation($id: ID!, $v: Int!, $input: LinkServiceInput!) { linkServiceUpdate(id: $id, version: $v, input: $input) { hosts homeUrl version } }";
        final Map<String, Object> input = Map.of("name", Map.of("sv", "Spexflix"), "hosts", List.of("Spexflix.example.org"),
                "homeUrl", "https://spexflix.example.org/");

        expectError(execute(DIRECTEUR, update, Map.of("id", "SPEXFLIX", "v", 0, "input", input)), "FORBIDDEN");
        execute(SUPER_ADMIN, update, Map.of("id", "SPEXFLIX", "v", 0, "input", input))
                .path("linkServiceUpdate.hosts").entityList(String.class).containsExactly("spexflix.example.org");
        expectError(execute(SUPER_ADMIN, "mutation($input: LinkServiceInput!) { linkServiceCreate(input: $input) { id } }",
                Map.of("input", Map.of("name", Map.of("sv", "Film"), "hosts", List.of("spexflix.example.org")))), "BAD_REQUEST");
        expectError(execute(SUPER_ADMIN, "mutation($input: LinkServiceInput!) { linkServiceCreate(input: $input) { id } }",
                Map.of("input", Map.of("name", Map.of("sv", "Osäker"), "hosts", List.of(), "homeUrl", "http://example.org"))), "BAD_REQUEST");

        // Links to the new host are now recognised; a service in use is retired, not deleted
        execute(STEERING_GROUP, CREATE, Map.of("input", create(material("Film", "https://www.spexflix.example.org/v/2", "PARTICIPANTS"))))
                .path("materialCreate.service.id").entity(String.class).isEqualTo("SPEXFLIX");
        execute(SUPER_ADMIN, SERVICES, Map.of()).path("linkServices[0].inUse").entity(Boolean.class).isEqualTo(true);
        expectError(execute(SUPER_ADMIN, "mutation($id: ID!, $v: Int!) { linkServiceDelete(id: $id, version: $v) }", Map.of("id", "SPEXFLIX", "v", 1)),
                "BAD_REQUEST");
        execute(SUPER_ADMIN, "mutation($id: ID!, $v: Int!) { linkServiceRetire(id: $id, version: $v) { retired } }", Map.of("id", "SPEXFLIX", "v", 1))
                .path("linkServiceRetire.retired").entity(Boolean.class).isEqualTo(true);
        execute(DIRECTEUR, SERVICES, Map.of()).path("linkServices[*].id").entityList(String.class).doesNotContain("SPEXFLIX");
        execute(DIRECTEUR, SERVICES, Map.of("all", true)).path("linkServices[*].id").entityList(String.class).contains("SPEXFLIX");

        final String mistake = execute(SUPER_ADMIN, "mutation($input: LinkServiceInput!) { linkServiceCreate(input: $input) { id } }",
                Map.of("input", Map.of("name", Map.of("sv", "Felskriven"), "hosts", List.of("fel.example.org"))))
                .path("linkServiceCreate.id").entity(String.class).get();

        execute(SUPER_ADMIN, "mutation($id: ID!, $v: Int!) { linkServiceDelete(id: $id, version: $v) }", Map.of("id", mistake, "v", 0))
                .path("linkServiceDelete").valueIsNull();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM link_service_host WHERE link_service_id = ?").param(mistake).query(Integer.class).single()).isZero();
    }

    @Test
    void deletingTheRevivalTakesItsMaterialsAndInventory() {
        final String id = execute(STEERING_GROUP, CREATE, Map.of("input", create(material("Arr", "https://1drv.ms/x", "POSITIONS",
                "positionIds", List.of("BAND"))))).path("materialCreate.id").entity(String.class).get();

        execute(STEERING_GROUP, ITEM_CREATE, Map.of("input", Map.of("revivalId", revival, "name", "Noter", "kind", "SHEET_MUSIC", "status", "MISSING",
                "materialId", id))).path("inventoryItemCreate.id").hasValue();
        execute(SUPER_ADMIN, "mutation($id: ID!, $v: Int!) { revivalDelete(id: $id, version: $v) }", Map.of("id", revival, "v", version(revival)))
                .path("revivalDelete").entity(String.class).isEqualTo(String.valueOf(revival));
        for (final String table : List.of("material", "material_position", "inventory_item")) {
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single()).as(table).isZero();
        }
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM material_audit WHERE revtype = 2").query(Integer.class).single()).isEqualTo(1);
    }
}
