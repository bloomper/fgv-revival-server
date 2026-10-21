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

package nu.fgv.revival.server.person;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;
import java.util.Map;

import static nu.fgv.revival.server.Documents.REVIVAL;
import static nu.fgv.revival.server.Documents.REVIVALS;

/**
 * Roles given by email wait until a person with that verified email logs in (FR-019b, FR-020).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
class WaitingMemberIT extends AbstractIntegrationTest {

    @Test
    void aWaitingDirecteurIsClaimedOnTheFirstRequestWhateverTheCase() {
        final String email = "vantande" + DOMAIN;

        try (Keycloak admin = keycloakAdmin()) {
            createUser(realm(admin), email, "Vera", "Väntande", CLIENT_ID, List.of("USER"));
        }

        final long id = createRevival(ALI_BABA, 2028, "Ali Baba 2028");

        setStatus(id, "APPROVED");
        addMember(id, "VANTANDE" + DOMAIN.toUpperCase(), "DIRECTEUR", List.of());
        execute(SUPER_ADMIN, REVIVAL, Map.of("id", id)).path("revival.members[0].state").entity(String.class).isEqualTo("WAITING");

        execute(email, REVIVALS, Map.of()).path("revivals[*].id").entityList(String.class).containsExactly(String.valueOf(id));
        execute(SUPER_ADMIN, REVIVAL, Map.of("id", id))
                .path("revival.members[0].state").entity(String.class).isEqualTo("ACTIVE")
                .path("revival.members[0].displayName").entity(String.class).isEqualTo("Vera Väntande");
    }

    @Test
    void anUnverifiedEmailDoesNotClaim() {
        final String email = "overifierad" + DOMAIN;

        try (Keycloak admin = keycloakAdmin()) {
            final RealmResource realm = realm(admin);
            final RealmRepresentation representation = realm.toRepresentation();

            representation.setVerifyEmail(false);
            realm.update(representation);
            try {
                final String userId = createUser(realm, email, "Otto", "Overifierad", CLIENT_ID, List.of("USER"));
                final UserRepresentation user = realm.users().get(userId).toRepresentation();

                user.setEmailVerified(false);
                realm.users().get(userId).update(user);

                final long id = createRevival(ALI_BABA, 2028, "Ali Baba 2028");

                setStatus(id, "APPROVED");
                addMember(id, email, "DIRECTEUR", List.of());

                graphQlWithToken(freshToken(email, CLIENT_ID))
                        .document("{ revivals { id } }")
                        .execute()
                        .path("revivals").entityList(Object.class).hasSize(0);
                execute(SUPER_ADMIN, REVIVAL, Map.of("id", id)).path("revival.members[0].state").entity(String.class).isEqualTo("WAITING");
            } finally {
                representation.setVerifyEmail(true);
                realm.update(representation);
            }
        }
    }

    @Test
    void aMatchedRoleStaysAfterTheEmailChanges() {
        final String email = "byter" + DOMAIN;
        final String newEmail = "bytt" + DOMAIN;
        final String userId;

        try (Keycloak admin = keycloakAdmin()) {
            userId = createUser(realm(admin), email, "Bo", "Byter", CLIENT_ID, List.of("USER"));
        }

        final long id = createRevival(ALI_BABA, 2028, "Ali Baba 2028");

        setStatus(id, "APPROVED");
        addMember(id, email, "DIRECTEUR", List.of());
        graphQlWithToken(freshToken(email, CLIENT_ID)).document("{ revivals { id } }").execute().path("revivals").entityList(Object.class).hasSize(1);

        try (Keycloak admin = keycloakAdmin()) {
            final UserRepresentation user = realm(admin).users().get(userId).toRepresentation();

            user.setEmail(newEmail);
            user.setUsername(newEmail);
            user.setEmailVerified(true);
            realm(admin).users().get(userId).update(user);
        }

        graphQlWithToken(freshToken(newEmail, CLIENT_ID)).document("{ revivals { id } }").execute().path("revivals").entityList(Object.class).hasSize(1);
    }
}
