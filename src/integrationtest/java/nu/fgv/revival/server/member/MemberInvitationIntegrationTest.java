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

import jakarta.mail.internet.MimeMessage;
import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static nu.fgv.revival.server.Documents.MEMBER_ADD;
import static nu.fgv.revival.server.Documents.MEMBER_CANDIDATES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Members picked from the FGV realm's users, and invited into it when they have no account, against a real Keycloak
 * whose mail goes to GreenMail. Access to Nyuppsättning is never granted here.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class MemberInvitationIntegrationTest extends AbstractIntegrationTest {

    private long id;

    private static List<String> recipients(final String subjectPart) {
        return Arrays.stream(MAIL.getReceivedMessages())
                .filter(message -> subject(message).contains(subjectPart))
                .map(message -> {
                    try {
                        return message.getAllRecipients()[0].toString();
                    } catch (final Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }

    private static String subject(final MimeMessage message) {
        try {
            return String.valueOf(message.getSubject());
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String recipientsOf(final MimeMessage message) {
        try {
            return message.getAllRecipients()[0].toString();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        id = organisingRevival();
        MAIL.reset();
    }

    @Test
    void candidatesComeFromTheRealmWithWhetherTheyCanLogIn() {
        execute(DIRECTEUR, MEMBER_CANDIDATES, Map.of("revivalId", id, "query", " Ulla "))
                .path("revivalMemberCandidates[*].email").entityList(String.class).containsExactly(PLAIN_USER)
                .path("revivalMemberCandidates[0].displayName").entity(String.class).isEqualTo("Ulla Användare")
                .path("revivalMemberCandidates[0].hasAccess").entity(Boolean.class).isEqualTo(true);
        execute(SUPER_ADMIN, MEMBER_CANDIDATES, Map.of("revivalId", id, "query", "spexare"))
                .path("revivalMemberCandidates[*].email").entityList(String.class).containsExactly(SPEXREGISTER_ONLY)
                .path("revivalMemberCandidates[0].hasAccess").entity(Boolean.class).isEqualTo(false);
        expectError(execute(DIRECTEUR, MEMBER_CANDIDATES, Map.of("revivalId", id, "query", " ul ")), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, MEMBER_CANDIDATES, Map.of("revivalId", id, "query", "Ulla")), "FORBIDDEN");
        expectError(execute(OBSERVER, MEMBER_CANDIDATES, Map.of("revivalId", id, "query", "Ulla")), "FORBIDDEN");
    }

    @Test
    void anAddressWithoutAnAccountIsInvitedWithoutAccessAndTheSuperAdminsAreTold() {
        final String email = "inbjuden-" + UUID.randomUUID() + DOMAIN;

        execute(DIRECTEUR, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", email, "role", "STEERING_GROUP",
                "firstName", " Ines ", "lastName", "Inbjuden")))
                .path("revivalMemberAdd.state").entity(String.class).isEqualTo("WAITING")
                .path("revivalMemberAdd.invitedAt").hasValue();

        try (Keycloak admin = keycloakAdmin()) {
            final RealmResource realm = realm(admin);
            final List<UserRepresentation> users = realm.users().searchByEmail(email, true);

            assertThat(users).singleElement().satisfies(user -> {
                assertThat(user.getUsername()).isEqualTo(email);
                assertThat(user.getFirstName()).isEqualTo("Ines");
                assertThat(user.getLastName()).isEqualTo("Inbjuden");
                assertThat(user.isEnabled()).isTrue();
                assertThat(user.isEmailVerified()).isFalse();
                assertThat(realm.users().get(user.getId()).roles().getAll().getClientMappings()).isNullOrEmpty();
            });
        }
        assertThat(recipients("Nyuppsättning")).containsExactly(SUPER_ADMIN);
        assertThat(Arrays.stream(MAIL.getReceivedMessages()).filter(message -> !subject(message).contains("Nyuppsättning")).toList())
                .as("Keycloak's invitation")
                .singleElement()
                .satisfies(message -> assertThat(message.getAllRecipients()[0].toString()).isEqualTo(email));

        final long other = createRevival(NOBEL, 2029);

        setStatus(other, "APPROVED");
        MAIL.reset();
        addMember(other, email, "DIRECTEUR", List.of());
        try (Keycloak admin = keycloakAdmin()) {
            assertThat(realm(admin).users().searchByEmail(email, true)).hasSize(1);
        }
        assertThat(jdbcClient.sql("SELECT invited_at IS NULL FROM revival_member WHERE revival_id = ?").param(other).query(Boolean.class).single()).isTrue();
        assertThat(recipients("Nyuppsättning")).containsExactly(SUPER_ADMIN);
    }

    @Test
    void anAccountWithoutAccessIsNotInvitedButTheSuperAdminsAreTold() {
        execute(DIRECTEUR, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", SPEXREGISTER_ONLY, "role", "STEERING_GROUP")))
                .path("revivalMemberAdd.state").entity(String.class).isEqualTo("WAITING")
                .path("revivalMemberAdd.invitedAt").valueIsNull();

        assertThat(Arrays.stream(MAIL.getReceivedMessages()).map(message -> recipientsOf(message)).toList()).containsExactly(SUPER_ADMIN);
    }

    @Test
    void anAccountWithAccessJustWaitsForTheFirstLogin() {
        execute(DIRECTEUR, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", PLAIN_USER, "role", "STEERING_GROUP")))
                .path("revivalMemberAdd.state").entity(String.class).isEqualTo("WAITING")
                .path("revivalMemberAdd.invitedAt").valueIsNull();

        assertThat(MAIL.getReceivedMessages()).isEmpty();
    }
}
