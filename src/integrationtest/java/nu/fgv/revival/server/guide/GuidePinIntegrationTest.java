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

package nu.fgv.revival.server.guide;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving a revival to another version, the newer-version banner and the changes view (FR-016, FR-019, FR-020).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
class GuidePinIntegrationTest extends AbstractIntegrationTest {

    private static final String CHANGE = """
            mutation($input: RevivalGuideVersionChangeInput!) {
                revivalGuideVersionChange(input: $input) {
                    guideVersion { id }
                    guideVersionHistory { from { id } to { id } changedBy }
                }
            }""";
    private static final String REVIVAL = """
            query($id: ID!) {
                revival(id: $id) {
                    guideVersion { id label }
                    newerGuideVersion { id label }
                    permissions { guideVersionsAllowed { id label } canSuggestGuideChange }
                }
            }""";
    private static final String CHANGES = "query($from: ID!, $to: ID!) { guideChanges(fromId: $from, toId: $to) { from { label } to { label } items { key kind reworded } } }";

    private long v1;
    private long v2;
    private long revival;

    @BeforeEach
    void twoVersionsAndARevival() {
        v1 = publishedGuide();
        revival = createRevival(ALI_BABA, 2028);
        setStatus(revival, "APPROVED");
        login(DIRECTEUR);
        login(STEERING_GROUP);
        addMember(revival, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(revival, STEERING_GROUP, "STEERING_GROUP", List.of());
        setStatus(revival, "ORGANISING");

        graphQl(SUPER_ADMIN).document("mutation { guideDraftStart { draft { id } } }").execute();

        final long draft = jdbcClient.sql("SELECT id FROM guide_version WHERE status = 'DRAFT'").query(Long.class).single();
        final long item = jdbcClient.sql("SELECT id FROM guide_item WHERE version_id = ? ORDER BY id LIMIT 1").param(draft).query(Long.class).single();
        final long itemVersion = jdbcClient.sql("SELECT version FROM guide_item WHERE id = ?").param(item).query(Long.class).single();

        execute(SUPER_ADMIN, "mutation($input: GuideItemUpdateInput!) { guideItemUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", item, "version", itemVersion, "type", "TEXT", "text", Map.of("sv", "Omskrivet i v2."))))
                .errors().verify();
        publishGuide(draft, "v2");
        v2 = draft;
    }

    private Map<String, Object> change(final Long from, final long to) {
        final Map<String, Object> input = new java.util.HashMap<>();

        input.put("revivalId", revival);
        input.put("fromVersionId", from);
        input.put("toVersionId", to);
        return Map.of("input", input);
    }

    @Test
    void theDirecteurSeesTheNewerVersionAndMovesForward() {
        graphQl(DIRECTEUR).document(REVIVAL).variable("id", revival).execute()
                .path("revival.guideVersion.label").entity(String.class).isEqualTo("v1")
                .path("revival.newerGuideVersion.label").entity(String.class).isEqualTo("v2")
                .path("revival.permissions.guideVersionsAllowed[*].label").entityList(String.class).containsExactly("v2");

        execute(DIRECTEUR, CHANGE, change(v1, v2))
                .path("revivalGuideVersionChange.guideVersion.id").entity(String.class).isEqualTo(String.valueOf(v2))
                .path("revivalGuideVersionChange.guideVersionHistory[0].to.id").entity(String.class).isEqualTo(String.valueOf(v2))
                .path("revivalGuideVersionChange.guideVersionHistory[0].changedBy").entity(String.class).isEqualTo("Dana Directeur");

        graphQl(DIRECTEUR).document(REVIVAL).variable("id", revival).execute()
                .path("revival.newerGuideVersion").valueIsNull()
                .path("revival.permissions.guideVersionsAllowed").entityList(Object.class).hasSize(0);
    }

    @Test
    void theDirecteurCannotMoveBackButASuperAdminCan() {
        execute(DIRECTEUR, CHANGE, change(v1, v2)).errors().verify();

        expectError(execute(DIRECTEUR, CHANGE, change(v2, v1)), "FORBIDDEN");
        execute(SUPER_ADMIN, CHANGE, change(v2, v1))
                .path("revivalGuideVersionChange.guideVersion.id").entity(String.class).isEqualTo(String.valueOf(v1));
        graphQl(SUPER_ADMIN).document(REVIVAL).variable("id", revival).execute()
                .path("revival.permissions.guideVersionsAllowed[*].label").entityList(String.class).containsExactly("v2");
    }

    @Test
    void aStaleVersionIsAConflict() {
        expectError(execute(DIRECTEUR, CHANGE, change(v2, v2)), "CONFLICT");
    }

    @Test
    void steeringGroupAndObserverCannotChangeIt() {
        expectError(execute(STEERING_GROUP, CHANGE, change(v1, v2)), "FORBIDDEN");
        expectError(execute(OBSERVER, CHANGE, change(v1, v2)), "FORBIDDEN");
        graphQl(STEERING_GROUP).document(REVIVAL).variable("id", revival).execute()
                .path("revival.permissions.guideVersionsAllowed").entityList(Object.class).hasSize(0)
                .path("revival.permissions.canSuggestGuideChange").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void anArchivedRevivalIsReadOnly() {
        setStatus(revival, "ARCHIVED");

        expectError(execute(SUPER_ADMIN, CHANGE, change(v1, v2)), "FORBIDDEN");
    }

    @Test
    void readersSeeWhatChangedButOnlySuperAdminsCompareTheDraft() {
        execute(STEERING_GROUP, CHANGES, Map.of("from", v1, "to", v2))
                .path("guideChanges.from.label").entity(String.class).isEqualTo("v1")
                .path("guideChanges.to.label").entity(String.class).isEqualTo("v2")
                .path("guideChanges.items").entityList(Object.class).hasSize(1)
                .path("guideChanges.items[0].kind").entity(String.class).isEqualTo("CHANGED")
                .path("guideChanges.items[0].reworded").entityList(String.class).containsExactly("sv");

        graphQl(SUPER_ADMIN).document("mutation { guideDraftStart { draft { id } } }").execute();

        final long draft = jdbcClient.sql("SELECT id FROM guide_version WHERE status = 'DRAFT'").query(Long.class).single();

        execute(SUPER_ADMIN, CHANGES, Map.of("from", v2, "to", draft)).path("guideChanges.items").entityList(Object.class).hasSize(0);
        expectError(execute(STEERING_GROUP, CHANGES, Map.of("from", v2, "to", draft)), "NOT_FOUND");
    }

    @Test
    void theChangeIsAudited() {
        execute(DIRECTEUR, CHANGE, change(v1, v2)).errors().verify();

        assertThat(jdbcClient.sql("SELECT source FROM revinfo WHERE operation = 'revivalGuideVersionChange'").query(String.class).list())
                .containsExactly("WEB");
    }
}
