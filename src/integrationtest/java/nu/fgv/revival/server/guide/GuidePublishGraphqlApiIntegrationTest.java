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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publishing and discarding the draft, and pinning revivals at the first publication (FR-014, FR-015, FR-017, FR-018,
 * SC-006).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
class GuidePublishGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String PUBLISH = "mutation($input: GuideDraftPublishInput!) { guideDraftPublish(input: $input) { id status label publishedBy } }";
    private static final String DISCARD = "mutation($id: ID!, $version: Int!) { guideDraftDiscard(id: $id, version: $version) }";
    private static final String REVIVAL_GUIDE = "query($id: ID!) { revival(id: $id) { id guideVersion { id label publishedAt } } }";

    @Test
    void publishingPinsExistingRevivals() {
        final long revival = createRevival(ALI_BABA, 2028);
        final long draft = draftV5Layout();

        graphQl(SUPER_ADMIN).document(REVIVAL_GUIDE).variable("id", revival).execute()
                .path("revival.guideVersion").valueIsNull();

        graphQl(SUPER_ADMIN).document(PUBLISH)
                .variable("input", Map.of("id", draft, "version", guideVersion(draft), "label", "v5", "note", "Från Word"))
                .execute()
                .path("guideDraftPublish.status").entity(String.class).isEqualTo("PUBLISHED")
                .path("guideDraftPublish.label").entity(String.class).isEqualTo("v5")
                .path("guideDraftPublish.publishedBy").entity(String.class).isEqualTo("Sara Admin");

        graphQl(SUPER_ADMIN).document(REVIVAL_GUIDE).variable("id", revival).execute()
                .path("revival.guideVersion.id").entity(String.class).isEqualTo(String.valueOf(draft))
                .path("revival.guideVersion.label").entity(String.class).isEqualTo("v5");
        assertThat(jdbcClient.sql("SELECT from_version_id IS NULL FROM revival_guide_version_change WHERE revival_id = ?")
                .param(revival).query(Boolean.class).single()).isTrue();
        graphQl(SUPER_ADMIN).document("{ guideDraft { draft { id } } }").execute().path("guideDraft").valueIsNull();
    }

    @Test
    void aRevivalCreatedAfterPublishingIsPinnedToTheLatest() {
        final long version = publishedGuide();
        final long revival = createRevival(NOBEL, 2027);

        graphQl(SUPER_ADMIN).document(REVIVAL_GUIDE).variable("id", revival).execute()
                .path("revival.guideVersion.id").entity(String.class).isEqualTo(String.valueOf(version));
    }

    @Test
    void discardingDeletesTheDraft() {
        final long draft = draftV5Layout();

        graphQl(SUPER_ADMIN).document(DISCARD).variable("id", draft).variable("version", guideVersion(draft)).execute()
                .errors().verify();

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_version").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_item").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_item_audit WHERE revtype = 2").query(Long.class).single()).isPositive();
    }

    @Test
    void aStaleVersionIsAConflict() {
        final long draft = draftV5Layout();

        expectError(graphQl(SUPER_ADMIN).document(PUBLISH)
                .variable("input", Map.of("id", draft, "version", guideVersion(draft) + 1, "label", "v5"))
                .execute(), "CONFLICT");
    }

    @Test
    void everyoneButSuperAdminsIsRefused() {
        final long draft = draftV5Layout();

        for (final String user : List.of(OBSERVER, DIRECTEUR, STEERING_GROUP, PLAIN_USER)) {
            expectError(graphQl(user).document(PUBLISH)
                    .variable("input", Map.of("id", draft, "version", guideVersion(draft), "label", "v5"))
                    .execute(), "FORBIDDEN");
            expectError(graphQl(user).document(DISCARD).variable("id", draft).variable("version", guideVersion(draft)).execute(), "FORBIDDEN");
            expectError(graphQl(user).document("{ guideDraft { draft { id } } }").execute(), "FORBIDDEN");
        }
        assertThat(jdbcClient.sql("SELECT status FROM guide_version WHERE id = ?").param(draft).query(String.class).single()).isEqualTo("DRAFT");
    }

    @Test
    void publishingIsAuditedWithTheActor() {
        final long draft = draftV5Layout();

        publishGuide(draft, "v5");

        assertThat(jdbcClient.sql("SELECT modified_by FROM revinfo WHERE operation = 'guideDraftPublish'").query(String.class).list())
                .isNotEmpty()
                .allSatisfy(subject -> assertThat(subject).doesNotContain("@"));
    }
}
