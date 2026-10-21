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

package nu.fgv.revival.server.util.data;

import nu.fgv.revival.server.AbstractIntegrationTest;
import nu.fgv.revival.server.member.Capability;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample data is built on Spexregister's: its spex and its sample user {@code admin@fgv.nu}. The other users
 * have one purpose each and are created by the import.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
class SampleDataImporterIntegrationTest extends AbstractIntegrationTest {

    private static final int APPROVED_STATUSES = 6;

    @Autowired
    private SampleDataImporter importer;
    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void createSpexregisterSampleAdmin() {
        try (Keycloak admin = keycloakAdmin()) {
            createUser(realm(admin), SampleDataImporter.ADMIN, "Admin", "Spexregister", "spexregister", List.of("USER", "EDITOR", "ADMIN"));
        }
    }

    private void importSampleData() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            importer.importSampleData(connection);
        }
    }

    private int count(final String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private int memberships(final String email, final String role) {
        return count("revival_member m JOIN person p ON p.id = m.person_id WHERE m.removed_at IS NULL AND p.email = '%s' AND m.role = '%s'".formatted(email, role));
    }

    @Test
    void createsRevivalsInEveryStatusOnSpexregistersSpex() throws SQLException {
        importSampleData();

        assertThat(count("revival")).isEqualTo(SampleDataImporter.NUMBER_OF_SAMPLES_REVIVALS);
        assertThat(jdbcClient.sql("SELECT DISTINCT status FROM revival").query(String.class).list())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(RevivalStatus.values()).map(Enum::name).toList());
        assertThat(jdbcClient.sql("SELECT DISTINCT spex_id FROM revival").query(Long.class).list()).isSubsetOf(ALI_BABA, NOBEL, SNORRE);
    }

    @Test
    void givesEachSampleUserOnePurpose() throws SQLException {
        importSampleData();

        assertThat(memberships(SampleDataImporter.DIRECTEUR, "DIRECTEUR")).isEqualTo(APPROVED_STATUSES);
        assertThat(memberships(SampleDataImporter.DIRECTEUR_2, "DIRECTEUR")).isEqualTo(APPROVED_STATUSES);
        assertThat(memberships(SampleDataImporter.STEERING_GROUP, "STEERING_GROUP")).isEqualTo(APPROVED_STATUSES);
        for (final Capability capability : Capability.values()) {
            final String email = SampleDataImporter.steeringGroupWith(capability);

            assertThat(memberships(email, "STEERING_GROUP")).isEqualTo(APPROVED_STATUSES);
            assertThat(jdbcClient.sql("SELECT DISTINCT c.capability FROM revival_member_capability c JOIN revival_member m ON m.id = c.member_id JOIN person p ON p.id = m.person_id WHERE p.email = ?")
                    .param(email).query(String.class).list()).containsExactly(capability.name());
        }
        assertThat(memberships(SampleDataImporter.NO_ROLE, "STEERING_GROUP") + memberships(SampleDataImporter.NO_ROLE, "DIRECTEUR")).isZero();
        assertThat(count("revival_member WHERE pending_email = '%s'".formatted(SampleDataImporter.INVITED))).isEqualTo(APPROVED_STATUSES);
        assertThat(count("revival_member WHERE removed_at IS NOT NULL")).isEqualTo(APPROVED_STATUSES);
    }

    @Test
    void givesEveryRowOneBaselineRevision() throws SQLException {
        importSampleData();

        assertThat(count("revinfo")).isOne();
        assertThat(jdbcClient.sql("SELECT operation FROM revinfo").query(String.class).single()).isEqualTo(SampleDataImporter.OPERATION);
        assertThat(count("revival_audit")).isEqualTo(count("revival"));
        assertThat(count("revival_status_change_audit")).isEqualTo(count("revival_status_change"));
        assertThat(count("revival_member_audit")).isEqualTo(count("revival_member"));
        assertThat(count("revival_member_capability_audit")).isEqualTo(count("revival_member_capability"));
    }

    @Test
    void replacesTheDataAndReusesTheUsersOnEveryImport() throws SQLException {
        importSampleData();
        importSampleData();

        assertThat(count("revival")).isEqualTo(SampleDataImporter.NUMBER_OF_SAMPLES_REVIVALS);
        assertThat(count("revinfo")).isOne();
        try (Keycloak admin = keycloakAdmin()) {
            assertThat(realm(admin).users().searchByEmail(SampleDataImporter.DIRECTEUR, true)).hasSize(1);
        }
    }

    @Test
    void theSampleUsersCanLogInWithTheirRoles() throws SQLException {
        importSampleData();
        forgetTokens();

        graphQl(SampleDataImporter.ADMIN)
                .document("{ me { globalRoles } revivals { id } }")
                .execute()
                .path("me.globalRoles").entityList(String.class).containsExactly("SUPER_ADMIN")
                .path("revivals").entityList(Object.class).hasSize(SampleDataImporter.NUMBER_OF_SAMPLES_REVIVALS);
        graphQl(SampleDataImporter.OBSERVER)
                .document("{ me { globalRoles } }")
                .execute()
                .path("me.globalRoles").entityList(String.class).containsExactly("OBSERVER");
        graphQl(SampleDataImporter.DIRECTEUR)
                .document("{ me { globalRoles revivalRoles { role } } }")
                .execute()
                .path("me.globalRoles").entityList(String.class).hasSize(0)
                .path("me.revivalRoles[*].role").entityList(String.class).contains("DIRECTEUR").doesNotContain("STEERING_GROUP");
        graphQl(SampleDataImporter.steeringGroupWith(Capability.FINANCE))
                .document("{ me { revivalRoles { role capabilities } } }")
                .execute()
                .path("me.revivalRoles[*].role").entityList(String.class).contains("STEERING_GROUP").doesNotContain("DIRECTEUR")
                .path("me.revivalRoles[*].capabilities[*]").entityList(String.class).contains("FINANCE").doesNotContain("CATERING", "STAFFING", "COMMS");
        graphQl(SampleDataImporter.NO_ROLE)
                .document("{ me { globalRoles revivalRoles { role } } }")
                .execute()
                .path("me.globalRoles").entityList(String.class).hasSize(0)
                .path("me.revivalRoles").entityList(Object.class).hasSize(0);
    }
}
