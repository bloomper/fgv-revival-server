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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guide works while Spexregister is down. No guide operation calls Spexregister.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class GuideSpexregisterUnavailableIntegrationTest extends AbstractIntegrationTest {

    @Test
    void readingAndSearchingWork() {
        final long version = publishedGuide();

        spexregisterDown(false);

        final int before = spexregisterCalls();

        graphQl(SUPER_ADMIN).document(GuideReadGraphqlApiIntegrationTest.LATEST).execute()
                .errors().verify()
                .path("guideLatestVersion.chapters").entityList(Object.class).hasSize(10);
        graphQl(SUPER_ADMIN).document(GuideReadGraphqlApiIntegrationTest.VERSION).variable("id", version).execute()
                .errors().verify();
        graphQl(SUPER_ADMIN).document(GuideReadGraphqlApiIntegrationTest.SEARCH).variable("query", "sprit").execute()
                .errors().verify()
                .path("guideSearch").entityList(Object.class).hasSize(1);
        assertThat(spexregisterCalls()).isEqualTo(before);
    }

    @Test
    void startingAndEditingADraftWork() {
        publishedGuide();
        spexregisterDown(false);

        graphQl(SUPER_ADMIN).document("mutation { guideDraftStart { draft { id } } }").execute().errors().verify();

        final long item = jdbcClient.sql("SELECT i.id FROM guide_item i JOIN guide_version v ON v.id = i.version_id WHERE v.status = 'DRAFT' ORDER BY i.id LIMIT 1")
                .query(Long.class).single();
        final long version = jdbcClient.sql("SELECT version FROM guide_item WHERE id = ?").param(item).query(Long.class).single();

        execute(SUPER_ADMIN, "mutation($input: GuideItemUpdateInput!) { guideItemUpdate(input: $input) { id } }",
                java.util.Map.of("input", java.util.Map.of("id", item, "version", version, "type", "TIP", "text", java.util.Map.of("sv", "Ändrat."))))
                .errors().verify();
    }

    @Test
    void importingAndPublishingWork() {
        spexregisterDown(false);

        final long draft = draftV5Layout();

        publishGuide(draft, "v1");
        graphQl(SUPER_ADMIN).document(GuideReadGraphqlApiIntegrationTest.VERSIONS).execute()
                .errors().verify()
                .path("guideVersions[0].label").entity(String.class).isEqualTo("v1");
    }
}
