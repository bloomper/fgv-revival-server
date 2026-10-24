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
import nu.fgv.revival.server.checklist.RevivalGuideSync;
import nu.fgv.revival.server.guide.GuideTestChapters;
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
    @Autowired
    private RevivalGuideSync guideSync;
    @Autowired
    private SampleActivityImporter activityImporter;

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
        for (final SeededCapability capability : SeededCapability.values()) {
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
        assertThat(count("revival_guide_version_change_audit")).isEqualTo(count("revival_guide_version_change"));
        assertThat(count("guide_item_audit")).isEqualTo(count("guide_item"));
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
    void createsAGuideInTwoVersionsAndPinsEveryRevival() throws SQLException {
        importSampleData();

        assertThat(jdbcClient.sql("SELECT label FROM guide_version WHERE status = 'PUBLISHED' ORDER BY published_at").query(String.class).list())
                .containsExactly("v1", "v2");
        assertThat(count("guide_chapter")).isEqualTo(2 * GuideTestChapters.KEYS.size());
        assertThat(count("guide_item WHERE type = 'QUESTION'")).isPositive();
        assertThat(count("revival WHERE guide_version_id IS NULL")).isZero();
        assertThat(count("revival_guide_version_change")).isEqualTo(count("revival"));
    }

    @Test
    void theGuideSyncGivesTheRevivalsRequiredItemsAndQuestions() throws SQLException {
        importSampleData();

        guideSync.run(null);

        assertThat(count("checklist_item WHERE required = 1")).isPositive();
        assertThat(count("log_entry WHERE origin = 'GUIDE'")).isPositive();
    }

    private void importSampleDataAndActivity() throws SQLException {
        importSampleData();
        guideSync.run(null);
        activityImporter.importSampleActivity();
    }

    @Test
    void createsChapterAndEventTemplates() throws SQLException {
        importSampleData();

        assertThat(count("checklist_template WHERE kind = 'CHAPTER'")).isPositive();
        assertThat(jdbcClient.sql("SELECT name_sv FROM checklist_template WHERE kind = 'EVENT'").query(String.class).list())
                .containsExactlyInAnyOrder(SampleChecklistTemplates.REHEARSAL_WEEKEND, SampleChecklistTemplates.SHOW_WEEKEND);
        assertThat(count("checklist_template_audit")).isEqualTo(count("checklist_template"));
    }

    @Test
    void bringsTheRevivalsToLife() throws SQLException {
        importSampleDataAndActivity();

        assertThat(count("checklist_item WHERE template_item_id IS NOT NULL")).isPositive();
        assertThat(count("checklist_item WHERE state = 'DONE'")).isPositive();
        assertThat(count("checklist_item WHERE state = 'NOT_APPLICABLE'")).isPositive();
        assertThat(count("checklist WHERE kind = 'EVENT'")).isPositive();
        assertThat(count("log_entry WHERE origin = 'MANUAL'")).isPositive();
        assertThat(count("log_entry WHERE answers_entry_id IS NOT NULL")).isPositive();
        assertThat(count("meeting WHERE status = 'CLOSED' AND minutes IS NULL")).isZero();
        assertThat(count("meeting WHERE status = 'PLANNED'")).isPositive();
        assertThat(jdbcClient.sql("SELECT status FROM guide_suggestion").query(String.class).list())
                .containsExactlyInAnyOrder("ACCEPTED", "REJECTED", "OPEN", "OPEN");
        assertThat(count("log_entry WHERE id NOT IN (SELECT id FROM log_entry_audit)")).isZero();
        assertThat(count("checklist_item_audit WHERE state = 'DONE'")).isPositive();
        assertThat(count("event WHERE type_id IS NOT NULL")).isPositive();
        assertThat(count("event WHERE type_id IS NULL")).isPositive();
        assertThat(count("checklist WHERE event_id IS NOT NULL")).isPositive();
        assertThat(count("event_type WHERE checklist_template_id IS NOT NULL")).isEqualTo(4);
        assertThat(count("event WHERE id NOT IN (SELECT id FROM event_audit)")).isZero();
        // M6b: the mailing plan, and for open revivals participants, shifts and a draft mailing
        assertThat(count("mailing_template WHERE event_type_id IS NOT NULL")).isEqualTo(2);
        assertThat(count("participant WHERE kind = 'HELPER'")).isPositive();
        assertThat(count("shift_slot")).isPositive();
        assertThat(count("shift_assignment WHERE self_signed_up = 1")).isPositive();
        assertThat(count("mailing WHERE status = 'DRAFT'")).isPositive();
        assertThat(count("shift_slot WHERE id NOT IN (SELECT id FROM shift_slot_audit)")).isZero();
        // M8: materials in FGV's applications and other services, and inventory items with owners
        assertThat(count("material WHERE service_id = 'SPEXFLIX'")).isPositive();
        assertThat(count("material WHERE service_id = 'GOOGLE_DRIVE'")).isPositive();
        assertThat(count("inventory_item WHERE owner_person_id IS NOT NULL")).isPositive();
        assertThat(count("inventory_item WHERE material_id IS NOT NULL")).isPositive();
        assertThat(count("material WHERE id NOT IN (SELECT id FROM material_audit)")).isZero();
        assertThat(count("link_service WHERE id NOT IN (SELECT id FROM link_service_audit)")).isZero();
    }

    @Test
    void fillsEveryKindOfData() throws SQLException {
        importSampleDataAndActivity();

        // Every table but the audit tables, and the positions of materials, which come from Spexregister
        final List<String> empty = jdbcClient.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                        AND table_name NOT LIKE '%\\_audit' AND table_name NOT LIKE 'flyway%'
                        AND table_name <> 'material_position'
                        """)
                .query(String.class)
                .list()
                .stream()
                .filter(table -> count(table) == 0)
                .toList();

        assertThat(empty).isEmpty();
    }

    @Test
    void coversTheStatesOfEachKind() throws SQLException {
        importSampleDataAndActivity();

        assertThat(jdbcClient.sql("SELECT DISTINCT response FROM participant").query(String.class).list())
                .containsExactlyInAnyOrder("PENDING", "YES", "NO", "MAYBE");
        assertThat(count("participant WHERE removed_at IS NOT NULL")).isPositive();
        assertThat(count("participant WHERE fee IS NOT NULL")).isPositive();
        assertThat(count("participant WHERE fee_paid_at IS NOT NULL")).isPositive();
        assertThat(count("participant WHERE membership_paid = 1")).isPositive();
        assertThat(jdbcClient.sql("SELECT DISTINCT status FROM mailing").query(String.class).list())
                .containsExactlyInAnyOrder("DRAFT", "SENT", "CANCELLED");
        assertThat(count("mailing WHERE template_id IS NOT NULL")).isPositive();
        assertThat(jdbcClient.sql("SELECT DISTINCT status FROM mailing_recipient").query(String.class).list()).contains("SENT", "FAILED");
        assertThat(jdbcClient.sql("SELECT DISTINCT answer FROM availability").query(String.class).list())
                .containsExactlyInAnyOrder("YES", "NO", "MAYBE");
        assertThat(count("event WHERE cancelled_at IS NOT NULL")).isPositive();
        assertThat(count("budget_transaction WHERE cancelled_at IS NOT NULL")).isPositive();
        assertThat(count("budget_transaction WHERE receipt_content_type = 'application/pdf'")).isEqualTo(count("receipt_content"));
        assertThat(count("budget_line WHERE removed_at IS NOT NULL")).isPositive();
        assertThat(count("budget_settings WHERE loan_repaid_on IS NOT NULL")).isPositive();
        assertThat(count("material WHERE removed_at IS NOT NULL")).isPositive();
        assertThat(count("inventory_item WHERE removed_at IS NOT NULL")).isPositive();
        assertThat(count("log_entry WHERE origin = 'IMPORT'")).isPositive();
        assertThat(count("log_entry e JOIN log_entry_tag t ON t.entry_id = e.id WHERE e.type = 'DECISION' AND t.tag = 'överskott'")).isPositive();
        assertThat(count("calendar_feed WHERE revoked_at IS NOT NULL")).isPositive();
        assertThat(jdbcClient.sql("SELECT DISTINCT status FROM sync_run").query(String.class).list()).containsExactlyInAnyOrder("DONE", "FAILED");
        assertThat(count("revival WHERE membership_year IS NOT NULL")).isPositive();
        assertThat(count("checklist_item WHERE state = 'OPEN' AND revival_id IN (SELECT id FROM revival WHERE status = 'ARCHIVED')")).isZero();
    }

    @Test
    void anonymizesTheRevivalArchivedLongAgo() throws SQLException {
        importSampleDataAndActivity();

        assertThat(jdbcClient.sql("SELECT status FROM revival WHERE anonymized_at IS NOT NULL").query(String.class).list()).containsExactly("ARCHIVED");
        assertThat(count("participant p JOIN revival r ON r.id = p.revival_id WHERE r.anonymized_at IS NOT NULL")).isZero();
        assertThat(count("participant p JOIN revival r ON r.id = p.revival_id WHERE r.status = 'ARCHIVED'")).isPositive();
    }

    @Test
    void theSampleParticipantSeesTheirOwnPage() throws SQLException {
        importSampleDataAndActivity();
        forgetTokens();

        final Long revivalId = jdbcClient.sql("SELECT id FROM revival WHERE status = 'OPEN' ORDER BY id LIMIT 1").query(Long.class).single();

        graphQl(SampleDataImporter.PARTICIPANT)
                .document("query($id: ID!) { myParticipation(revivalId: $id) { participant { response } dietary } }")
                .variable("id", revivalId)
                .execute()
                .path("myParticipation.participant.response").entity(String.class).isEqualTo("YES")
                .path("myParticipation.dietary").hasValue();
    }

    @Test
    void theDirecteurReadsTheMinutesOfAClosedMeeting() throws SQLException {
        importSampleDataAndActivity();
        forgetTokens();

        final Long meetingId = jdbcClient.sql("""
                        SELECT m.id FROM meeting m
                        JOIN revival_member rm ON rm.revival_id = m.revival_id AND rm.role = 'DIRECTEUR'
                        JOIN person p ON p.id = rm.person_id
                        WHERE m.status = 'CLOSED' AND p.email = ?
                        LIMIT 1
                        """)
                .param(SampleDataImporter.DIRECTEUR)
                .query(Long.class)
                .single();

        graphQl(SampleDataImporter.DIRECTEUR)
                .document("query($id: ID!) { meeting(id: $id) { minutes { decisions { number text } closed { number } agendaPoints attendees { id } recorder { id } } } }")
                .variable("id", meetingId)
                .execute()
                .path("meeting.minutes.decisions").entityList(Object.class).hasSizeGreaterThan(1)
                .path("meeting.minutes.closed").entityList(Object.class).hasSizeGreaterThan(1)
                .path("meeting.minutes.agendaPoints").entityList(String.class).contains("Budget")
                .path("meeting.minutes.recorder.id").hasValue();
    }

    @Test
    void replacesAGuideCreatedInTheApplication() throws SQLException {
        importSampleData();
        jdbcClient.sql("""
                        INSERT INTO guide_version (status, intro_sv, published_by, created_by, created_at)
                        SELECT 'DRAFT', 'Intro', id, 'test', NOW(6) FROM person WHERE email = ?
                        """)
                .param(SampleDataImporter.DIRECTEUR)
                .update();

        importSampleData();

        assertThat(count("guide_version")).isEqualTo(2);
        assertThat(count("guide_version WHERE status = 'DRAFT'")).isZero();
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
        graphQl(SampleDataImporter.steeringGroupWith(SeededCapability.FINANCE))
                .document("{ me { revivalRoles { role capabilities { id } } } }")
                .execute()
                .path("me.revivalRoles[*].role").entityList(String.class).contains("STEERING_GROUP").doesNotContain("DIRECTEUR")
                .path("me.revivalRoles[*].capabilities[*].id").entityList(String.class).contains("FINANCE").doesNotContain("CATERING", "STAFFING", "COMMS");
        graphQl(SampleDataImporter.NO_ROLE)
                .document("{ me { globalRoles revivalRoles { role } } }")
                .execute()
                .path("me.globalRoles").entityList(String.class).hasSize(0)
                .path("me.revivalRoles").entityList(Object.class).hasSize(0);
    }
}
