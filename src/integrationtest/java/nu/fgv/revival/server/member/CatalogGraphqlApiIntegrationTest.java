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
package nu.fgv.revival.server.member;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static nu.fgv.revival.server.Documents.MEMBER_ADD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lists the super admin keeps: areas of responsibility and guide item types.
 *
 * @author Anders Jacobsson
 */
class CatalogGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String CAPABILITIES = "query($all: Boolean) { capabilities(includeRetired: $all) { id name { sv en } position retired inUse version } }";
    private static final String CAPABILITY_CREATE = "mutation($input: CapabilityCreateInput!) { capabilityCreate(input: $input) { id name { sv en } position version } }";
    private static final String CAPABILITY_RETIRE = "mutation($id: ID!, $version: Int!) { capabilityRetire(id: $id, version: $version) { id retired version } }";
    private static final String CAPABILITY_DELETE = "mutation($id: ID!, $version: Int!) { capabilityDelete(id: $id, version: $version) }";
    private static final String TYPES = "{ guideItemTypes { id kind name { sv en } } }";
    private static final String TYPE_CREATE = "mutation($input: GuideItemTypeCreateInput!) { guideItemTypeCreate(input: $input) { id kind name { sv } version } }";

    @Test
    void everyoneReadsTheListsThatStartWithTheOldEnums() {
        execute(STEERING_GROUP, CAPABILITIES, Map.of())
                .path("capabilities[*].id").entityList(String.class).containsExactly("FINANCE", "CATERING", "STAFFING", "COMMS")
                .path("capabilities[0].name.sv").entity(String.class).isEqualTo("Ekonomi");
        execute(STEERING_GROUP, TYPES, Map.of())
                .path("guideItemTypes[*].id").entityList(String.class).containsExactly("TIP", "REQUIREMENT", "QUESTION", "TEXT")
                .path("guideItemTypes[*].kind").entityList(String.class).containsExactly("TIP", "REQUIREMENT", "QUESTION", "TEXT");
    }

    @Test
    void onlyTheSuperAdminChangesThem() {
        expectError(execute(DIRECTEUR, CAPABILITY_CREATE, Map.of("input", Map.of("name", Map.of("sv", "Scenografi")))), "FORBIDDEN");
        expectError(execute(DIRECTEUR, TYPE_CREATE, Map.of("input", Map.of("kind", "TIP", "name", Map.of("sv", "Varning")))), "FORBIDDEN");
    }

    @Test
    void aNewCapabilityCanBeHeldThenRetiredButNotDeletedWhileInUse() {
        final long revival = createRevival(ALI_BABA, 2028);

        setStatus(revival, "APPROVED");

        final String id = execute(SUPER_ADMIN, CAPABILITY_CREATE, Map.of("input", Map.of("name", Map.of("sv", " Scenografi ", "en", "Set design"))))
                .path("capabilityCreate.name.sv").entity(String.class).isEqualTo("Scenografi")
                .path("capabilityCreate.position").entity(Integer.class).isEqualTo(4)
                .path("capabilityCreate.id").entity(String.class).get();

        expectError(execute(SUPER_ADMIN, CAPABILITY_CREATE, Map.of("input", Map.of("name", Map.of("sv", "scenografi")))), "BAD_REQUEST");
        execute(SUPER_ADMIN, "mutation($input: RevivalMemberAddInput!) { revivalMemberAdd(input: $input) { capabilities { name { en } } } }",
                Map.of("input", Map.of("revivalId", revival, "email", "ny" + DOMAIN, "role", "STEERING_GROUP", "capabilities", List.of(id))))
                .path("revivalMemberAdd.capabilities[0].name.en").entity(String.class).isEqualTo("Set design");
        execute(SUPER_ADMIN, CAPABILITIES, Map.of())
                .path("capabilities[4].inUse").entity(Boolean.class).isEqualTo(true);
        expectError(execute(SUPER_ADMIN, CAPABILITY_DELETE, Map.of("id", id, "version", 0)), "BAD_REQUEST");

        execute(SUPER_ADMIN, CAPABILITY_RETIRE, Map.of("id", id, "version", 0)).path("capabilityRetire.retired").entity(Boolean.class).isEqualTo(true);

        execute(SUPER_ADMIN, CAPABILITIES, Map.of())
                .path("capabilities[*].id").entityList(String.class).doesNotContain(id);
        execute(SUPER_ADMIN, CAPABILITIES, Map.of("all", true))
                .path("capabilities[*].id").entityList(String.class).contains(id);
        expectError(execute(SUPER_ADMIN, MEMBER_ADD, Map.of("input", Map.of("revivalId", revival, "email", "fler" + DOMAIN, "role", "STEERING_GROUP",
                "capabilities", List.of(id)))), "BAD_REQUEST");
    }

    @Test
    void aSeededEntryCanBeRetiredRestoredAndRenamed() {
        final long version = jdbcClient.sql("SELECT version FROM capability WHERE id = 'FINANCE'").query(Long.class).single();

        execute(SUPER_ADMIN, CAPABILITY_RETIRE, Map.of("id", "FINANCE", "version", version))
                .path("capabilityRetire.retired").entity(Boolean.class).isEqualTo(true);
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { capabilityRestore(id: $id, version: $version) { retired } }",
                Map.of("id", "FINANCE", "version", version + 1))
                .path("capabilityRestore.retired").entity(Boolean.class).isEqualTo(false);
        execute(SUPER_ADMIN, "mutation($input: GuideItemTypeUpdateInput!) { guideItemTypeUpdate(input: $input) { name { sv } } }",
                Map.of("input", Map.of("id", "TIP", "version", jdbcClient.sql("SELECT version FROM guide_item_type WHERE id = 'TIP'").query(Long.class).single(),
                        "name", Map.of("sv", "Tips", "en", "Tip"))))
                .path("guideItemTypeUpdate.name.sv").entity(String.class).isEqualTo("Tips");
    }

    @Test
    void anUnusedCapabilityIsDeleted() {
        final String id = execute(SUPER_ADMIN, CAPABILITY_CREATE, Map.of("input", Map.of("name", Map.of("sv", "Felskriven"))))
                .path("capabilityCreate.id").entity(String.class).get();

        execute(SUPER_ADMIN, CAPABILITY_DELETE, Map.of("id", id, "version", 0)).errors().verify();

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM capability WHERE id = ?").param(id).query(Integer.class).single()).isZero();
    }

    @Test
    void aNewItemTypeKeepsItsKind() {
        final long draft = draftV5Layout();
        final long chapter = jdbcClient.sql("SELECT id FROM guide_chapter WHERE version_id = ? ORDER BY position LIMIT 1").param(draft).query(Long.class).single();
        final String id = execute(SUPER_ADMIN, TYPE_CREATE, Map.of("input", Map.of("kind", "REQUIREMENT", "name", Map.of("sv", "Myndighetskrav"))))
                .path("guideItemTypeCreate.kind").entity(String.class).isEqualTo("REQUIREMENT")
                .path("guideItemTypeCreate.id").entity(String.class).get();

        execute(SUPER_ADMIN, "mutation($input: GuideItemCreateInput!) { guideItemCreate(input: $input) { type { id kind name { sv } } } }",
                Map.of("input", Map.of("chapterId", chapter, "type", id, "text", Map.of("sv", "Tillstånd från polisen."))))
                .path("guideItemCreate.type.kind").entity(String.class).isEqualTo("REQUIREMENT")
                .path("guideItemCreate.type.name.sv").entity(String.class).isEqualTo("Myndighetskrav");
        expectError(execute(SUPER_ADMIN, "mutation($input: GuideItemCreateInput!) { guideItemCreate(input: $input) { id } }",
                Map.of("input", Map.of("chapterId", chapter, "type", "NOPE", "text", Map.of("sv", "x")))), "BAD_REQUEST");
    }
}
