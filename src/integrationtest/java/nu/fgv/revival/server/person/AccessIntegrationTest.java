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
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only people with {@code USER} on {@code fgv-revival} get in (FR-001), whatever their Spexregister roles.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
class AccessIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aUserWithOnlyASpexregisterRoleIsRefused() {
        graphQl(SPEXREGISTER_ONLY)
                .document("{ me { id } }")
                .execute()
                .errors().satisfy(errors -> assertThat(errors).singleElement()
                        .satisfies(error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN)));
    }

    @Test
    void aTokenFromAnotherClientIsRefused() {
        for (final String clientId : List.of(SPEXREGISTER_MCP_CLIENT_ID, MCP_CLIENT_ID)) {
            restTestClient()
                    .post()
                    .uri("/api/graphql")
                    .header(HttpHeaders.AUTHORIZATION, freshToken(PLAIN_USER, clientId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", "{ me { id } }"))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Test
    void noTokenIsUnauthorized() {
        graphQl(null)
                .document("{ me { id } }")
                .execute()
                .errors().satisfy(errors -> assertThat(errors).singleElement()
                        .satisfies(error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED)));
    }

    @Test
    void aUserWithOnlyUserGetsIn() {
        graphQl(PLAIN_USER)
                .document("{ me { email displayName globalRoles revivalRoles { role } } }")
                .execute()
                .path("me.email").entity(String.class).isEqualTo(PLAIN_USER)
                .path("me.displayName").entity(String.class).isEqualTo("Ulla Användare")
                .path("me.globalRoles").entityList(String.class).hasSize(0)
                .path("me.revivalRoles").entityList(Object.class).hasSize(0);
    }

    @Test
    void afterUserIsRemovedTheNextTokenIsRefusedThoughRevivalRolesRemain() {
        final String email = "removed" + DOMAIN;
        final String userId;

        try (Keycloak admin = keycloakAdmin()) {
            userId = createUser(realm(admin), email, "Rut", "Borttagen", CLIENT_ID, List.of("USER"));
        }

        final long revivalId = createRevival(ALI_BABA, 2028, "Ali Baba 2028");

        setStatus(revivalId, "APPROVED");
        addMember(revivalId, email, "DIRECTEUR", List.of());
        graphQlWithToken(freshToken(email, CLIENT_ID))
                .document("{ revivals { id } }")
                .execute()
                .path("revivals").entityList(Object.class).hasSize(1);

        try (Keycloak admin = keycloakAdmin()) {
            final RealmResource realm = realm(admin);
            final ClientResource client = realm.clients().get(realm.clients().findByClientId(CLIENT_ID).getFirst().getId());

            realm.users().get(userId).roles().clientLevel(client.toRepresentation().getId())
                    .remove(List.of(client.roles().get("USER").toRepresentation()));
        }

        graphQlWithToken(freshToken(email, CLIENT_ID))
                .document("{ revivals { id } }")
                .execute()
                .errors().satisfy(errors -> assertThat(errors).singleElement()
                        .satisfies(error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN)));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM revival_member WHERE removed_at IS NULL").query(Integer.class).single()).isEqualTo(1);
    }
}
