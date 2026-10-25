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

package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static nu.fgv.revival.server.Documents.CREATE;
import static nu.fgv.revival.server.Documents.MEMBER_ADD;
import static nu.fgv.revival.server.Documents.MEMBER_REMOVE;
import static nu.fgv.revival.server.Documents.REVIVAL;
import static nu.fgv.revival.server.Documents.REVIVALS;
import static nu.fgv.revival.server.Documents.SPEX_SEARCH;
import static nu.fgv.revival.server.Documents.STATUS;
import static nu.fgv.revival.server.Documents.UPDATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The super admin records, approves and staffs revivals.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class RevivalAdminGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    @Nested
    class AsSuperAdmin {

        @Test
        void spexSearchFindsOriginalSpex() {
            execute(SUPER_ADMIN, SPEX_SEARCH, Map.of("query", "ali"))
                    .path("spexSearch[*].title").entityList(String.class).containsExactly("Ali Baba")
                    .path("spexSearch[0].year").entity(Integer.class).isEqualTo(1956);
        }

        @Test
        void aCreatedRevivalIsProposedAndListed() {
            execute(SUPER_ADMIN, CREATE, Map.of("input", Map.of("spexId", ALI_BABA, "year", 2028)))
                    .path("revivalCreate.status").entity(String.class).isEqualTo("PROPOSED")
                    .path("revivalCreate.spex.title").entity(String.class).isEqualTo("Ali Baba");

            execute(SUPER_ADMIN, REVIVALS, Map.of())
                    .path("revivals[0].title").entity(String.class).isEqualTo("Ali Baba 2028")
                    .path("revivals[0].spex.available").entity(Boolean.class).isEqualTo(true);
        }

        @Test
        void aRevivalOfASpexRevivalIsRefused() {
            expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", Map.of("spexId", SPEX_REVIVAL, "year", 2028))), "BAD_REQUEST");
        }

        @Test
        void aSecondRevivalOfTheSameSpexInTheSameYearIsRefused() {
            createRevival(ALI_BABA, 2028);

            expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", Map.of("spexId", ALI_BABA, "year", 2028))), "BAD_REQUEST");
            execute(SUPER_ADMIN, REVIVALS, Map.of()).path("revivals").entityList(Object.class).hasSize(1);
        }

        @Test
        void theYearIsNeitherBeforeTheOriginalNorBeforeThisYear() {
            expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", Map.of("spexId", NOBEL, "year", 1987))), "BAD_REQUEST");
            expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", Map.of("spexId", ALI_BABA, "year", 2020))), "BAD_REQUEST");
        }

        @Test
        void aDeletedRevivalIsGoneWithEverythingThatBelongsToIt() {
            final long id = createRevival(ALI_BABA, 2028);

            addMember(id, DIRECTEUR, "DIRECTEUR", List.of());
            execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { revivalDelete(id: $id, version: $version) }", Map.of("id", id, "version", version(id)))
                    .path("revivalDelete").entity(String.class).isEqualTo(String.valueOf(id));

            execute(SUPER_ADMIN, REVIVALS, Map.of()).path("revivals").entityList(Object.class).hasSize(0);
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM revival_member").query(Integer.class).single()).isZero();
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM revival_audit WHERE id = ? AND revtype = 2").param(id).query(Integer.class).single()).isOne();
        }

        @Test
        void coreFieldsCanBeUpdated() {
            final long id = createRevival(ALI_BABA, 2028);

            execute(SUPER_ADMIN, UPDATE, Map.of("input", Map.of("id", id, "version", version(id), "spexId", NOBEL, "year", 2029)))
                    .path("revivalUpdate.title").entity(String.class).isEqualTo("Nobel 2029")
                    .path("revivalUpdate.year").entity(Integer.class).isEqualTo(2029)
                    .path("revivalUpdate.spex.id").entity(String.class).isEqualTo(String.valueOf(NOBEL));
        }

        @Test
        void anyStatusCanBeSetIncludingBackwards() {
            final long id = createRevival(ALI_BABA, 2028);

            for (final String[] step : List.of(
                    new String[]{"PROPOSED", "APPROVED"},
                    new String[]{"APPROVED", "SETTLING"},
                    new String[]{"SETTLING", "APPROVED"},
                    new String[]{"APPROVED", "REJECTED"},
                    new String[]{"REJECTED", "ARCHIVED"},
                    new String[]{"ARCHIVED", "PROPOSED"})) {
                execute(SUPER_ADMIN, STATUS, Map.of("input", Map.of("id", id, "from", step[0], "to", step[1])))
                        .path("revivalStatusChange.status").entity(String.class).isEqualTo(step[1]);
            }
        }

        @Test
        void statusHistoryListsEveryChangeWithWhoAndWhen() {
            final long id = createRevival(ALI_BABA, 2028);

            setStatus(id, "APPROVED");
            setStatus(id, "REJECTED");

            execute(SUPER_ADMIN, REVIVAL, Map.of("id", id))
                    .path("revival.statusHistory").entityList(Object.class).hasSize(2)
                    .path("revival.statusHistory[0].from").entity(String.class).isEqualTo("APPROVED")
                    .path("revival.statusHistory[0].to").entity(String.class).isEqualTo("REJECTED")
                    .path("revival.statusHistory[0].changedBy").entity(String.class).isEqualTo("Sara Admin")
                    .path("revival.statusHistory[0].changedAt").hasValue()
                    .path("revival.createdBy").entity(String.class).isEqualTo("Sara Admin");
        }

        @Test
        void revisionsRecordTheKeycloakSubjectNeverAnEmail() {
            final long id = createRevival(ALI_BABA, 2028);

            setStatus(id, "APPROVED");
            addMember(id, "ny-directeur" + DOMAIN, "DIRECTEUR", List.of());

            final List<String> modifiedBy = jdbcClient.sql("SELECT DISTINCT modified_by FROM revinfo WHERE " + NOT_BASELINE).query(String.class).list();
            final List<String> createdBy = jdbcClient.sql("SELECT DISTINCT created_by FROM revival").query(String.class).list();

            assertThat(modifiedBy).isNotEmpty().allSatisfy(value -> assertThat(value).hasSize(36).doesNotContain("@"));
            assertThat(createdBy).allSatisfy(value -> assertThat(value).doesNotContain("@"));
            assertThat(jdbcClient.sql("SELECT DISTINCT source FROM revinfo WHERE " + NOT_BASELINE).query(String.class).list()).containsExactly("WEB");
        }

        @Test
        void aDirecteurCanBeAppointedWaitingAndRemoved() {
            final long id = createRevival(ALI_BABA, 2028);

            setStatus(id, "APPROVED");

            final String memberId = execute(SUPER_ADMIN, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", "Ny.Directeur" + DOMAIN, "role", "DIRECTEUR")))
                    .path("revivalMemberAdd.state").entity(String.class).isEqualTo("WAITING")
                    .path("revivalMemberAdd.email").entity(String.class).isEqualTo("ny.directeur" + DOMAIN)
                    .path("revivalMemberAdd.id").entity(String.class).get();

            execute(SUPER_ADMIN, MEMBER_REMOVE, Map.of("id", memberId)).errors().verify();

            execute(SUPER_ADMIN, REVIVAL, Map.of("id", id)).path("revival.members").entityList(Object.class).hasSize(0);
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM revival_member WHERE removed_at IS NOT NULL AND removed_by IS NOT NULL AND pending_email IS NULL")
                    .query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'revival_member_audit' AND column_name = 'pending_email'")
                    .query(Integer.class).single()).isZero();
        }

        @Test
        void theSameEmailTwiceIsRefused() {
            final long id = createRevival(ALI_BABA, 2028);

            addMember(id, "dubbel" + DOMAIN, "DIRECTEUR", List.of());

            expectError(execute(SUPER_ADMIN, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", "DUBBEL" + DOMAIN, "role", "STEERING_GROUP"))), "BAD_REQUEST");
        }

        @Test
        void participantsCannotBeAdded() {
            final long id = createRevival(ALI_BABA, 2028);

            expectError(execute(SUPER_ADMIN, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", "p" + DOMAIN, "role", "PARTICIPANT"))), "BAD_REQUEST");
        }
    }

    /**
     * Every super-admin-only operation refused for everyone else.
     */
    @Nested
    class Refusals {

        private long id;
        private long directeurMemberId;

        @BeforeEach
        void setUp() {
            id = createRevival(ALI_BABA, 2028);
            setStatus(id, "APPROVED");
            directeurMemberId = addMember(id, DIRECTEUR, "DIRECTEUR", List.of());
        }

        @ParameterizedTest(name = "{0} is refused with {1}")
        @CsvSource({
                "observer@revival.test,  FORBIDDEN, FORBIDDEN",
                "directeur@revival.test, FORBIDDEN, FORBIDDEN",
                "user@revival.test,      FORBIDDEN, NOT_FOUND"
        })
        void superAdminOperations(final String user, final String globalCode, final String revivalCode) {
            expectError(execute(user, SPEX_SEARCH, Map.of("query", "ali")), globalCode);
            expectError(execute(user, CREATE, Map.of("input", Map.of("spexId", ALI_BABA, "year", 2028))), globalCode);
            expectError(execute(user, UPDATE, Map.of("input", Map.of("id", id, "version", version(id), "year", 2029))), revivalCode);
            expectError(execute(user, STATUS, Map.of("input", Map.of("id", id, "from", "APPROVED", "to", "REJECTED"))), revivalCode);
            expectError(execute(user, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", "x" + DOMAIN, "role", "DIRECTEUR"))), revivalCode);
            expectError(execute(user, MEMBER_REMOVE, Map.of("id", directeurMemberId)), revivalCode);

            assertThat(status(id)).isEqualTo("APPROVED");
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM revival").query(Integer.class).single()).isEqualTo(1);
        }
    }
}
