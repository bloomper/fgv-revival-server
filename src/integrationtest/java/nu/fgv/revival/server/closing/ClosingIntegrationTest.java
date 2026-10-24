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

package nu.fgv.revival.server.closing;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import nu.fgv.revival.server.AbstractIntegrationTest;
import nu.fgv.revival.server.person.PersonReferences;
import nu.fgv.revival.server.revival.RevivalAnonymization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closing end to end (M7): the sync's preview, starting it, its run against a stub of Spexregister with a failure and a
 * resume without duplicates, membership marks, the closing status, anonymization with its person rule and audit purge,
 * the automatic anonymization, the audit purge on deletion, and the guard on references to {@code person}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ClosingIntegrationTest extends AbstractIntegrationTest {

    private static final String PREVIEW = """
            query($id: ID!) {
                syncPreview(revivalId: $id) {
                    hash spexregisterRevivalExists membershipYear
                    participants { participant { id displayName } operations { operation detail exists } }
                    excluded { id displayName }
                    lastRun { id status }
                }
            }""";
    private static final String START = "mutation($id: ID!, $hash: String!) { syncStart(revivalId: $id, previewHash: $hash) { id status total } }";
    private static final String RUN = """
            query($id: ID!) {
                syncRun(id: $id) { id status total done failed startedBy { displayName } failures { participant { displayName } operation reason } }
            }""";
    private static final String MEMBERSHIP = "mutation($input: MembershipSetInput!) { membershipSet(input: $input) { id membershipPaid } }";
    private static final String ANONYMIZE = """
            mutation($id: ID!, $v: Int!, $c: String!) {
                revivalAnonymize(id: $id, version: $v, confirmation: $c) { id anonymizedAt permissions { canAnonymize canSync } }
            }""";
    private static final String NO_ACTIVITIES = """
            {"data": {"spexareByIds": [{"id": "7", "activities": []}, {"id": "8", "activities": []}]}}""";

    @Autowired
    private RevivalAnonymization anonymization;

    private long revival;
    private long anna;
    private long bertil;
    private long pia;

    @BeforeEach
    void closingRevival() {
        revival = organisingRevival();
        setStatus(revival, "OPEN");
        stub("SpexareByIds", """
                {"data": {"spexareByIds": [
                  {"id": "7", "firstName": "Anna", "lastName": "Andersson", "nickName": null, "graduation": "E05", "deceased": false,
                   "addresses": [], "consents": [], "activities": []},
                  {"id": "8", "firstName": "Bertil", "lastName": "Bengtsson", "nickName": null, "graduation": "K07", "deceased": false,
                   "addresses": [], "consents": [], "activities": []}]}}""");
        stub("SpexFamily", "{\"data\": {\"spex\": {\"id\": \"1\", \"revivals\": []}}}");
        stub("query Tasks", """
                {"data": {"taskPaged": {"edges": [
                  {"node": {"id": "ROLE", "name": "Roll", "category": {"id": "1", "name": "Ensemble", "actorPresent": true}}},
                  {"node": {"id": "TRUMPET", "name": "Trumpet", "category": {"id": "2", "name": "Orkester", "actorPresent": false}}}],
                  "totalCount": 2}}}""");
        stub("VocalParts", "{\"data\": {\"typesOfType\": [{\"id\": \"B1\", \"label\": \"Bas 1\"}]}}");
        stub("SpexRevivals", "{\"data\": {\"spex\": {\"id\": \"1\", \"revivals\": []}}}");
        stub("SpexareActivities", NO_ACTIVITIES);
        stub("SpexRevivalCreate", "{\"data\": {\"spexRevivalCreate\": {\"id\": \"500\", \"year\": \"2028\"}}}");
        stub("mutation ActivityCreate", "{\"data\": {\"activityCreate\": {\"id\": \"70\"}}}");
        stub("SpexActivityCreate", "{\"data\": {\"spexActivityCreate\": {\"id\": \"71\"}}}");
        stub("TaskActivityCreate", "{\"data\": {\"taskActivityCreate\": {\"id\": \"72\"}}}");
        stub("ActorCreate", "{\"data\": {\"actorCreate\": {\"id\": \"73\"}}}");
        stub("MembershipCreate", "{\"data\": {\"membershipCreate\": {\"id\": \"74\"}}}");

        final List<String> added = execute(STEERING_GROUP, "mutation($id: ID!) { participantsAddSpexare(revivalId: $id, spexareIds: [7, 8]) { id } }",
                Map.of("id", revival)).path("participantsAddSpexare[*].id").entityList(String.class).get();

        anna = Long.parseLong(added.get(0));
        bertil = Long.parseLong(added.get(1));
        pia = Long.parseLong(execute(STEERING_GROUP, "mutation($input: ParticipantAddInput!) { participantAdd(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "kind", "PARTICIPANT", "firstName", "Pia", "lastName", "Deltagare", "email", PARTICIPANT)))
                .path("participantAdd.id").entity(String.class).get());
        execute(STEERING_GROUP, """
                mutation($input: ParticipantsInviteInput!) { participantsInvite(input: $input) { invited { id } } }""",
                Map.of("input", Map.of("revivalId", revival, "participantIds", List.of(pia), "language", "sv")));
        login(PARTICIPANT);
        jdbcClient.sql("UPDATE participant SET response = 'YES', position_task_id = 'ROLE', role_name = 'Kalifen', vocal_part_id = 'B1' WHERE id = ?")
                .param(anna).update();
        jdbcClient.sql("UPDATE participant SET response = 'YES', position_task_id = 'TRUMPET' WHERE id = ?").param(bertil).update();
        jdbcClient.sql("UPDATE participant SET response = 'YES' WHERE id = ?").param(pia).update();
        setStatus(revival, "CLOSING");
    }

    private static StubMapping stub(final String operation, final String body) {
        return SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing(operation))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static int calls(final String operation) {
        return SPEXREGISTER.countRequestsMatching(postRequestedFor(urlEqualTo("/api/graphql")).withRequestBody(containing(operation)).build()).getCount();
    }

    private String hash(final String user) {
        return execute(user, PREVIEW, Map.of("id", revival)).path("syncPreview.hash").entity(String.class).get();
    }

    private long start(final String user, final String hash) {
        return Long.parseLong(execute(user, START, Map.of("id", revival, "hash", hash)).path("syncStart.id").entity(String.class).get());
    }

    private String awaitFinished(final long run) throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            final String status = jdbcClient.sql("SELECT status FROM sync_run WHERE id = ?").param(run).query(String.class).single();

            if (!"RUNNING".equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The sync run did not finish");
    }

    @Test
    void theSyncWritesWhatThePreviewShowsAndResumesWithoutDuplicates() throws InterruptedException {
        execute(DIRECTEUR, PREVIEW, Map.of("id", revival))
                .path("syncPreview.spexregisterRevivalExists").entity(Boolean.class).isEqualTo(false)
                .path("syncPreview.excluded[*].displayName").entityList(String.class).containsExactly("Pia Deltagare")
                .path("syncPreview.participants[*].participant.displayName").entityList(String.class).containsExactly("Anna Andersson", "Bertil Bengtsson")
                .path("syncPreview.participants[0].operations[*].operation").entityList(String.class).containsExactly("PARTICIPATION", "TASK", "ACTOR")
                .path("syncPreview.participants[0].operations[2].detail").entity(String.class).isEqualTo("Kalifen (Bas 1)")
                .path("syncPreview.participants[1].operations[*].detail").entityList(String.class).contains("Trumpet");

        // Memberships: only yes-sayers; the preview then changes
        final String before = hash(DIRECTEUR);

        assertThat(execute(DIRECTEUR, MEMBERSHIP, Map.of("input", Map.of("revivalId", revival, "year", 2028, "participantIds", List.of(anna))))
                .path("membershipSet[*].membershipPaid").entityList(Boolean.class).get()).containsExactly(true, false, false);
        expectError(execute(STEERING_GROUP, MEMBERSHIP, Map.of("input", Map.of("revivalId", revival, "year", 2028, "participantIds", List.of()))),
                "FORBIDDEN");
        expectError(execute(SUPER_ADMIN, START, Map.of("id", revival, "hash", before)), "CONFLICT");

        // The steering group reads the preview but does not start
        final String hash = hash(STEERING_GROUP);

        expectError(execute(STEERING_GROUP, START, Map.of("id", revival, "hash", hash)), "FORBIDDEN");

        // Bertil's task is refused
        final StubMapping refused = SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql")).atPriority(1)
                .withRequestBody(containing("TaskActivityCreate"))
                .withRequestBody(matchingJsonPath("$.variables.spexareId", equalTo("8")))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\": {\"taskActivityCreate\": null}, \"errors\": [{\"message\": \"No\", \"extensions\": {\"classification\": \"BAD_REQUEST\"}}]}")));
        final long first = start(DIRECTEUR, hash);

        assertThat(awaitFinished(first)).isEqualTo("FAILED");
        execute(DIRECTEUR, RUN, Map.of("id", first))
                .path("syncRun.total").entity(Integer.class).isEqualTo(7)
                .path("syncRun.failed").entity(Integer.class).isEqualTo(1)
                .path("syncRun.done").entity(Integer.class).isEqualTo(6)
                .path("syncRun.startedBy.displayName").entity(String.class).isEqualTo("Dana Directeur")
                .path("syncRun.failures[0].operation").entity(String.class).isEqualTo("TASK")
                .path("syncRun.failures[0].reason").entity(String.class).isEqualTo("REFUSED")
                .path("syncRun.failures[0].participant.displayName").entity(String.class).isEqualTo("Bertil Bengtsson");
        assertThat(jdbcClient.sql("SELECT spexregister_revival_id FROM revival WHERE id = ?").param(revival).query(Long.class).single()).isEqualTo(500L);
        SPEXREGISTER.verify(postRequestedFor(urlEqualTo("/api/graphql")).withRequestBody(containing("MembershipCreate"))
                .withHeader("X-Acting-Person", equalTo("Dana%20Directeur%20%28via%20Nyupps%C3%A4ttning%29")));

        // Spexregister now has what the first run wrote; resuming writes only Bertil's task
        SPEXREGISTER.removeStub(refused);
        stub("SpexRevivals", "{\"data\": {\"spex\": {\"id\": \"1\", \"revivals\": [{\"id\": \"500\", \"year\": \"2028\"}]}}}");
        stub("SpexareActivities", """
                {"data": {"spexareByIds": [
                  {"id": "7", "activities": [{"id": "70", "spexActivity": {"id": "71", "spex": {"id": "500"}},
                    "taskActivities": [{"id": "72", "task": {"id": "ROLE"}, "actors": [{"id": "73", "role": "Kalifen", "vocal": {"id": "B1"}}]}]}]},
                  {"id": "8", "activities": [{"id": "80", "spexActivity": {"id": "81", "spex": {"id": "500"}}, "taskActivities": []}]}]}}""");
        execute(DIRECTEUR, PREVIEW, Map.of("id", revival))
                .path("syncPreview.spexregisterRevivalExists").entity(Boolean.class).isEqualTo(true)
                .path("syncPreview.lastRun.status").entity(String.class).isEqualTo("FAILED")
                .path("syncPreview.participants[0].operations[*].exists").entityList(Boolean.class).containsExactly(true, true, true, true)
                .path("syncPreview.participants[1].operations[*].exists").entityList(Boolean.class).containsExactly(true, false);

        final long second = start(DIRECTEUR, hash(DIRECTEUR));

        assertThat(awaitFinished(second)).isEqualTo("DONE");
        execute(DIRECTEUR, RUN, Map.of("id", second)).path("syncRun.total").entity(Integer.class).isEqualTo(1);
        assertThat(calls("SpexRevivalCreate")).isEqualTo(1);
        assertThat(calls("SpexActivityCreate")).isEqualTo(2);
        assertThat(calls("MembershipCreate")).isEqualTo(1);
        assertThat(calls("ActorCreate")).isEqualTo(1);

        // Nothing left to write: a run with no steps is done at once
        stub("SpexareActivities", """
                {"data": {"spexareByIds": [
                  {"id": "7", "activities": [{"id": "70", "spexActivity": {"id": "71", "spex": {"id": "500"}},
                    "taskActivities": [{"id": "72", "task": {"id": "ROLE"}, "actors": [{"id": "73", "role": "Kalifen", "vocal": {"id": "B1"}}]}]}]},
                  {"id": "8", "activities": [{"id": "80", "spexActivity": {"id": "81", "spex": {"id": "500"}},
                    "taskActivities": [{"id": "82", "task": {"id": "TRUMPET"}, "actors": []}]}]}]}}""");
        execute(DIRECTEUR, START, Map.of("id", revival, "hash", hash(DIRECTEUR))).path("syncStart.status").entity(String.class).isEqualTo("DONE");
        execute(DIRECTEUR, "query($id: ID!) { revival(id: $id) { closing { lastSync { status } membershipsPaid membershipYear } } }", Map.of("id", revival))
                .path("revival.closing.lastSync.status").entity(String.class).isEqualTo("DONE")
                .path("revival.closing.membershipsPaid").entity(Integer.class).isEqualTo(1)
                .path("revival.closing.membershipYear").entity(Integer.class).isEqualTo(2028);
    }

    @Test
    void aRunningSyncIsNotStartedTwiceAndSpexregisterDownIsReported() {
        final long person = personId(DIRECTEUR);

        jdbcClient.sql("""
                INSERT INTO sync_run (revival_id, started_by, started_at, status, preview_hash, created_by, created_at, version)
                VALUES (?, ?, NOW(6), 'RUNNING', 'x', 'test', NOW(6), 0)""").params(revival, person).update();
        expectError(execute(DIRECTEUR, START, Map.of("id", revival, "hash", hash(DIRECTEUR))), "CONFLICT");

        spexregisterDown(false);
        expectError(execute(DIRECTEUR, PREVIEW, Map.of("id", revival)), "SPEXREGISTER_UNAVAILABLE");
    }

    @Test
    void theClosingStatusGathersTheSignals() {
        final long logEntry = Long.parseLong(execute(DIRECTEUR, """
                        mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }""",
                Map.of("input", Map.of("revivalId", revival, "type", "DECISION", "text", "Överskottet går till FGV", "tags", List.of("Överskott"))))
                .path("logEntryCreate.id").entity(String.class).get());

        jdbcClient.sql("UPDATE budget_settings SET loan_amount = 20000 WHERE revival_id = ?").param(revival).update();
        execute(OBSERVER, """
                        query($id: ID!) {
                            revival(id: $id) { closing { actualResult loanAmount loanRepaid surplusDecision { id } suggestions anonymizedAt autoAnonymizeOn } }
                        }""", Map.of("id", revival))
                .path("revival.closing.loanAmount").entity(String.class).isEqualTo("20000")
                .path("revival.closing.loanRepaid").entity(Boolean.class).isEqualTo(false)
                .path("revival.closing.surplusDecision.id").entity(String.class).isEqualTo(String.valueOf(logEntry))
                .path("revival.closing.suggestions").entity(Integer.class).isEqualTo(0)
                .path("revival.closing.autoAnonymizeOn").valueIsNull();

        setStatus(revival, "ARCHIVED");
        execute(SUPER_ADMIN, "query($id: ID!) { revival(id: $id) { closing { autoAnonymizeOn } } }", Map.of("id", revival))
                .path("revival.closing.autoAnonymizeOn").entity(String.class).isEqualTo(LocalDate.now().plusMonths(36).toString());
    }

    private Set<String> audited(final String table, final String where) {
        return jdbcClient.sql("SELECT CONCAT(id, '') FROM " + table + " WHERE " + where).param(revival).query(String.class).list().stream()
                .collect(Collectors.toSet());
    }

    @Test
    void anonymizingRemovesThePeopleAndKeepsTheWork() {
        // Another revival keeps David, who is in both
        login(DIRECTEUR_2);
        addMember(revival, DIRECTEUR_2, "STEERING_GROUP", List.of());

        final long other = createRevival(NOBEL, 2029);

        setStatus(other, "APPROVED");
        addMember(other, DIRECTEUR_2, "DIRECTEUR", List.of());

        final long directeur = personId(DIRECTEUR);
        final long david = personId(DIRECTEUR_2);
        final long participant = personId(PARTICIPANT);

        // A meeting with someone from outside, a row owned by name, a calendar link
        final String meeting = execute(DIRECTEUR, "mutation($input: MeetingCreateInput!) { meetingCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "date", "2028-05-01", "attendeeIds", List.of(directeur), "othersPresent", "Kim Extern")))
                .path("meetingCreate.id").entity(String.class).get();

        execute(DIRECTEUR, "mutation($id: ID!) { meetingStart(id: $id, version: 0) { id } }", Map.of("id", meeting));
        execute(DIRECTEUR, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "meetingId", meeting, "type", "DECISION", "text", "Vi hyr kostymer",
                        "owner", Map.of("name", "Kim Extern"))));
        execute(DIRECTEUR, "mutation($id: ID!) { meetingClose(id: $id, version: 1) { id } }", Map.of("id", meeting));
        execute(DIRECTEUR, "mutation($id: ID!) { calendarFeedCreate(revivalId: $id) { url } }", Map.of("id", revival));
        execute(DIRECTEUR, MEMBERSHIP, Map.of("input", Map.of("revivalId", revival, "year", 2028, "participantIds", List.of(anna, bertil))));
        assertThat(audited("participant_audit", "revival_id = ?")).isNotEmpty();
        jdbcClient.sql("""
                INSERT INTO budget_line (revival_id, category_id, description, quantity, unit_amount, created_by, created_at, version)
                VALUES (?, 'VENUE', 'Lokal', 1, 5000, 'test', NOW(6), 0)""").param(revival).update();

        // Only while settling, with the title, at the version seen
        expectError(execute(DIRECTEUR, ANONYMIZE, Map.of("id", revival, "v", version(revival), "c", "Ali Baba 2028")), "FORBIDDEN");
        setStatus(revival, "SETTLING");
        expectError(execute(DIRECTEUR, ANONYMIZE, Map.of("id", revival, "v", version(revival), "c", "Nobel 2028")), "BAD_REQUEST");
        expectError(execute(DIRECTEUR, ANONYMIZE, Map.of("id", revival, "v", version(revival) - 1, "c", "Ali Baba 2028")), "CONFLICT");

        execute(DIRECTEUR, ANONYMIZE, Map.of("id", revival, "v", version(revival), "c", "  ali baba 2028 "))
                .path("revivalAnonymize.anonymizedAt").hasValue()
                .path("revivalAnonymize.permissions.canAnonymize").entity(Boolean.class).isEqualTo(false)
                .path("revivalAnonymize.permissions.canSync").entity(Boolean.class).isEqualTo(false);

        // The people, and their history
        for (final String table : List.of("participant", "pending_person", "dietary_need", "referral", "calendar_feed")) {
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single()).as(table).isZero();
        }
        assertThat(audited("participant_audit", "revival_id = ?")).isEmpty();
        assertThat(audited("revival_member_audit", "revival_id = ? AND role IN ('PARTICIPANT', 'HELPER')")).isEmpty();
        assertThat(audited("revival_member", "revival_id = ? AND role IN ('PARTICIPANT', 'HELPER')")).isEmpty();
        assertThat(audited("revival_member", "revival_id = ? AND role = 'DIRECTEUR'")).hasSize(1);

        // Names in free text, now and in the history; the texts stay
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM log_entry WHERE revival_id = ? AND owner_name IS NULL AND text = 'Vi hyr kostymer'")
                .param(revival).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM log_entry_audit WHERE revival_id = ? AND owner_name IS NOT NULL").param(revival)
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM meeting_audit WHERE revival_id = ? AND others_present IS NOT NULL").param(revival)
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM meeting_attendee").query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT CAST(minutes AS CHAR) FROM meeting WHERE revival_id = ?").param(revival).query(String.class).single())
                .contains("Vi hyr kostymer").doesNotContain("Kim Extern");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM meeting_audit WHERE revival_id = ? AND CAST(minutes AS CHAR) LIKE '%Kim Extern%'")
                .param(revival).query(Integer.class).single()).isZero();

        // The budget as a reference with the three who said yes
        assertThat(jdbcClient.sql("SELECT participants FROM reference_revival WHERE automatic = TRUE").query(Integer.class).single()).isEqualTo(3);

        // Login data: gone for those only this revival kept, kept for David
        assertThat(jdbcClient.sql("SELECT email FROM person WHERE id = ?").param(participant).query(String.class).single())
                .isEqualTo("anonymized-" + participant + "@invalid");
        assertThat(jdbcClient.sql("SELECT email FROM person WHERE id = ?").param(directeur).query(String.class).single()).startsWith("anonymized-");
        assertThat(jdbcClient.sql("SELECT external_id FROM person WHERE id = ?").param(directeur).query(String.class).single()).startsWith("anonymized-");
        assertThat(jdbcClient.sql("SELECT display_name FROM person WHERE id = ?").param(directeur).query(String.class).optional()).isEmpty();
        assertThat(jdbcClient.sql("SELECT email FROM person WHERE id = ?").param(david).query(String.class).single()).isEqualTo(DIRECTEUR_2);
        assertThat(jdbcClient.sql("SELECT anonymized_by FROM revival WHERE id = ?").param(revival).query(Long.class).single()).isEqualTo(directeur);

        // The directeur logs in again as a new person; the super admin sees it anonymized and cannot do it twice
        login(DIRECTEUR);
        assertThat(personId(DIRECTEUR)).isNotEqualTo(directeur);
        final GraphQlTester.Response again = execute(SUPER_ADMIN, ANONYMIZE, Map.of("id", revival, "v", version(revival), "c", "Ali Baba 2028"));

        expectError(again, "BAD_REQUEST");
        execute(SUPER_ADMIN, "query($id: ID!) { revival(id: $id) { anonymizedAt closing { anonymizedAt } } }", Map.of("id", revival))
                .path("revival.anonymizedAt").hasValue()
                .path("revival.closing.anonymizedAt").hasValue();
    }

    @Test
    void archivedRevivalsAreAnonymizedAfterThirtySixMonths() {
        final long recent = createRevival(SNORRE, 2029);

        setStatus(revival, "ARCHIVED");
        setStatus(recent, "REJECTED");
        jdbcClient.sql("UPDATE revival_status_change SET changed_at = DATE_SUB(NOW(6), INTERVAL 37 MONTH) WHERE revival_id = ? AND to_status = 'ARCHIVED'")
                .param(revival).update();

        assertThat(anonymization.anonymizeExpired()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT anonymized_at IS NOT NULL AND anonymized_by IS NULL FROM revival WHERE id = ?").param(revival)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbcClient.sql("SELECT anonymized_at IS NULL FROM revival WHERE id = ?").param(recent).query(Boolean.class).single()).isTrue();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM participant").query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT source FROM revinfo WHERE operation = 'revivalAnonymizeAutomatic'").query(String.class).list())
                .containsOnly("SYSTEM");
        assertThat(anonymization.anonymizeExpired()).isZero();
    }

    @Test
    void deletingARevivalLeavesNoPeopleInTheHistory() {
        assertThat(audited("participant_audit", "revival_id = ?")).isNotEmpty();
        assertThat(audited("revival_member_audit", "revival_id = ? AND role = 'PARTICIPANT'")).isNotEmpty();

        execute(SUPER_ADMIN, "mutation($id: ID!, $v: Int!) { revivalDelete(id: $id, version: $v) }", Map.of("id", revival, "v", version(revival)))
                .path("revivalDelete").entity(String.class).isEqualTo(String.valueOf(revival));

        assertThat(audited("participant_audit", "revival_id = ?")).isEmpty();
        assertThat(audited("revival_member_audit", "revival_id = ? AND role IN ('PARTICIPANT', 'HELPER')")).isEmpty();
        // Other rows' deletion is still recorded
        assertThat(audited("revival_member_audit", "revival_id = ? AND role = 'DIRECTEUR' AND revtype = 2")).hasSize(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM person WHERE email = ?").param(PARTICIPANT).query(Integer.class).single()).isZero();
    }

    /**
     * Every foreign key to {@code person} must be in {@link PersonReferences}, so anonymization knows every reference.
     */
    @Test
    void everyReferenceToAPersonIsKnown() {
        final Set<String> foreignKeys = jdbcClient.sql("""
                        SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME) FROM information_schema.KEY_COLUMN_USAGE
                        WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME = 'person'""")
                .query(String.class).list().stream().collect(Collectors.toSet());
        final Set<String> known = PersonReferences.REFERENCES.stream().map(reference -> reference.table() + "." + reference.column())
                .collect(Collectors.toSet());
        final Set<String> declared = PersonReferences.REFERENCES.stream().filter(PersonReferences.Reference::foreignKey)
                .map(reference -> reference.table() + "." + reference.column()).collect(Collectors.toSet());

        assertThat(known).as("references listed in PersonReferences").containsAll(foreignKeys);
        assertThat(foreignKeys).as("foreign keys PersonReferences says exist").containsAll(declared);
    }
}
