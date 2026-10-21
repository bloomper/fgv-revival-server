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

/**
 * Who reads the guide, and what they read (FR-001–FR-004, FR-021, SC-006).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
class GuideReadGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    static final String VERSIONS = "{ guideVersions { id label } }";
    static final String LATEST = "{ guideLatestVersion { id status label chapters { phase title { sv } items { type } sections { title { sv } } } } }";
    static final String VERSION = "query($id: ID!) { guideVersion(id: $id) { id status label introduction { sv en } } }";
    static final String SEARCH = "query($query: String!) { guideSearch(query: $query) { kind phase sectionTitle { sv } itemKey itemType snippet matchedLanguage } }";
    static final String REVIVAL_GUIDE = "query($id: ID!) { revival(id: $id) { guideVersion { id label } } }";

    private long organisingRevivalWithMembers() {
        final long revival = createRevival(ALI_BABA, 2028, "Ali Baba 2028");

        setStatus(revival, "APPROVED");
        login(DIRECTEUR);
        login(STEERING_GROUP);
        addMember(revival, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(revival, STEERING_GROUP, "STEERING_GROUP", List.of());
        setStatus(revival, "ORGANISING");
        return revival;
    }

    @Test
    void readersReadSearchAndListVersions() {
        final long version = publishedGuide();

        organisingRevivalWithMembers();
        for (final String user : List.of(SUPER_ADMIN, OBSERVER, DIRECTEUR, STEERING_GROUP)) {
            graphQl(user).document(VERSIONS).execute()
                    .path("guideVersions[0].label").entity(String.class).isEqualTo("v1");
            graphQl(user).document(LATEST).execute()
                    .path("guideLatestVersion.id").entity(String.class).isEqualTo(String.valueOf(version))
                    .path("guideLatestVersion.chapters").entityList(Object.class).hasSize(10)
                    .path("guideLatestVersion.chapters[1].sections[0].title.sv").entity(String.class).isEqualTo("Kalaslokal");
            graphQl(user).document(VERSION).variable("id", version).execute()
                    .path("guideVersion.status").entity(String.class).isEqualTo("PUBLISHED");
            graphQl(user).document(SEARCH).variable("query", "sprit").execute()
                    .path("guideSearch[0].kind").entity(String.class).isEqualTo("ITEM")
                    .path("guideSearch[0].phase").entity(String.class).isEqualTo("B")
                    .path("guideSearch[0].itemType").entity(String.class).isEqualTo("TIP")
                    .path("guideSearch[0].sectionTitle.sv").entity(String.class).isEqualTo("Kalaslokal");
        }
    }

    @Test
    void aSteeringGroupMemberOfASettlingRevivalIsRefused() {
        final long version = publishedGuide();
        final long revival = organisingRevivalWithMembers();

        setStatus(revival, "SETTLING");
        expectError(graphQl(STEERING_GROUP).document(VERSIONS).execute(), "FORBIDDEN");
        expectError(graphQl(STEERING_GROUP).document(SEARCH).variable("query", "sprit").execute(), "FORBIDDEN");
        expectError(graphQl(STEERING_GROUP).document(VERSION).variable("id", version).execute(), "NOT_FOUND");
        graphQl(DIRECTEUR).document(VERSIONS).execute().path("guideVersions").entityList(Object.class).hasSize(1);
    }

    @Test
    void aPersonWithoutARevivalRoleIsRefused() {
        final long version = publishedGuide();

        expectError(graphQl(PLAIN_USER).document(VERSIONS).execute(), "FORBIDDEN");
        expectError(graphQl(PLAIN_USER).document(LATEST).execute(), "FORBIDDEN");
        expectError(graphQl(PLAIN_USER).document(VERSION).variable("id", version).execute(), "NOT_FOUND");
    }

    @Test
    void aWaitingMemberIsRefused() {
        publishedGuide();

        final long revival = createRevival(ALI_BABA, 2028, "Ali Baba 2028");

        setStatus(revival, "APPROVED");
        addMember(revival, "waiting" + DOMAIN, "STEERING_GROUP", List.of());
        setStatus(revival, "ORGANISING");
        expectError(graphQl(PLAIN_USER).document(VERSIONS).execute(), "FORBIDDEN");
    }

    @Test
    void theDraftIsOnlyForSuperAdmins() {
        final long draft = importV5Layout();

        organisingRevivalWithMembers();
        graphQl(SUPER_ADMIN).document(VERSION).variable("id", draft).execute()
                .path("guideVersion.status").entity(String.class).isEqualTo("DRAFT");
        for (final String user : List.of(OBSERVER, DIRECTEUR, STEERING_GROUP)) {
            expectError(graphQl(user).document(VERSION).variable("id", draft).execute(), "NOT_FOUND");
        }
    }

    @Test
    void everyoneWhoSeesARevivalSeesItsVersion() {
        final long version = publishedGuide();
        final long revival = organisingRevivalWithMembers();

        for (final String user : List.of(SUPER_ADMIN, OBSERVER, DIRECTEUR, STEERING_GROUP)) {
            graphQl(user).document(REVIVAL_GUIDE).variable("id", revival).execute()
                    .path("revival.guideVersion.id").entity(String.class).isEqualTo(String.valueOf(version));
        }
    }

    @Test
    void beforeThePublicationThereIsNothing() {
        final long revival = organisingRevivalWithMembers();

        graphQl(DIRECTEUR).document(LATEST).execute().path("guideLatestVersion").valueIsNull();
        graphQl(DIRECTEUR).document(VERSIONS).execute().path("guideVersions").entityList(Object.class).hasSize(0);
        graphQl(DIRECTEUR).document(REVIVAL_GUIDE).variable("id", revival).execute().path("revival.guideVersion").valueIsNull();
    }

    @Test
    void aShortQueryIsABadRequest() {
        publishedGuide();
        expectError(graphQl(SUPER_ADMIN).document(SEARCH).variable("query", "a").execute(), "BAD_REQUEST");
    }
}
