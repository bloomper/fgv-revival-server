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

package nu.fgv.revival.server.participant;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.test.tester.GraphQlTester;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Participants end to end: the steering group adds and invites them, a participant logs in with their FGV account
 * and answers on their own page, and participants and helpers are refused everything of the steering group's.
 * Spexregister is a stub.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ParticipantGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ADD = "mutation($input: ParticipantAddInput!) { participantAdd(input: $input) { id version pending firstName email } }";
    private static final String INVITE = """
            mutation($input: ParticipantsInviteInput!) {
                participantsInvite(input: $input) { invited { id invitation } skipped { participant { id } reason } }
            }""";
    private static final String MINE = """
            query($id: ID!) {
                myParticipation(revivalId: $id) {
                    participant { id response pending registerConsent fee feeOverride feePaid }
                    revival { id title permissions { isParticipant canReadWork canReadParticipants canReadBudget canSubscribeCalendar } myRole members { id } }
                    settings { version } terms contact { email } dietary swish { number message } steeringGroup { displayName }
                }
            }""";
    private static final String RESPOND = "mutation($input: ParticipationRespondInput!) { participationRespond(input: $input) { participant { response pending registerConsent } } }";
    private static final String PARTICIPANTS = "query($id: ID!) { participants(revivalId: $id) { id firstName lastName email dietary dietaryReadable response invitation feePaid version } }";

    @org.springframework.beans.factory.annotation.Autowired
    private ParticipantRetention retention;

    private long revival;

    private static void stub(final String operation, final String body) {
        SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing(operation))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    @BeforeEach
    void openRevivalAndSpexregister() {
        revival = organisingRevival();
        setStatus(revival, "OPEN");
        stub("SpexareByIds", """
                {"data": {"spexareByIds": [{"id": "7", "firstName": "Anna", "lastName": "Andersson", "nickName": null, "graduation": "E05", "deceased": false,
                  "addresses": [{"id": "70", "type": {"id": "HOME"}, "emailAddress": "anna@example.org", "phone": null, "phoneMobile": "0701234567",
                                 "streetAddress": null, "postalCode": null, "city": null}],
                  "consents": [], "activities": [{"id": "1", "spexActivity": {"id": "2", "spex": {"id": "1", "year": "1956", "title": "Ali Baba", "revival": false}}}]}]}}""");
        stub("SpexareBySpex", """
                {"data": {"spexareBySpex": [
                  {"id": "7", "firstName": "Anna", "lastName": "Andersson", "nickName": null, "graduation": "E05", "deceased": false},
                  {"id": "8", "firstName": "Död", "lastName": "Dödsson", "nickName": null, "graduation": null, "deceased": true}]}}""");
        stub("SpexFamily", "{\"data\": {\"spex\": {\"id\": \"1\", \"revivals\": [{\"id\": \"50\", \"year\": \"2005\"}]}}}");
        stub("query Tasks", """
                {"data": {"taskPaged": {"edges": [{"node": {"id": "ROLE", "name": "Roll", "category": {"id": "1", "name": "Ensemble", "actorPresent": true}}}],
                  "totalCount": 1}}}""");
        stub("VocalParts", "{\"data\": {\"typesOfType\": [{\"id\": \"B1\", \"label\": \"Bas 1\"}]}}");
        stub("SpexareCreate", "{\"data\": {\"spexareCreate\": {\"id\": \"99\"}}}");
        stub("AddressCreate", "{\"data\": {\"addressCreate\": {\"id\": \"100\"}}}");
        stub("ConsentCreate", "{\"data\": {\"consentCreate\": {\"id\": \"101\"}}}");
    }

    private long addPending(final String kind, final String email) {
        return Long.parseLong(execute(STEERING_GROUP, ADD, Map.of("input", Map.of("revivalId", revival, "kind", kind, "firstName", "Pia",
                "lastName", "Deltagare", "email", email))).path("participantAdd.id").entity(String.class).get());
    }

    private void invite(final long... ids) {
        execute(STEERING_GROUP, INVITE, Map.of("input", Map.of("revivalId", revival, "participantIds",
                java.util.Arrays.stream(ids).boxed().toList(), "language", "sv"))).path("participantsInvite.invited").entityList(Object.class).hasSize(ids.length);
    }

    private long participantVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM participant WHERE id = ?").param(id).query(Long.class).single();
    }

    @Test
    void theSteeringGroupAddsAndInvitesAndTheParticipantAnswersOnTheirPage() {
        final long pending = addPending("PARTICIPANT", PARTICIPANT);

        execute(STEERING_GROUP, "query($id: ID!) { participantCandidates(revivalId: $id) { spexareId history added } }", Map.of("id", revival))
                .path("participantCandidates[*].spexareId").entityList(String.class).containsExactly("7");

        final long spexare = Long.parseLong(execute(STEERING_GROUP, "mutation($id: ID!, $ids: [ID!]!) { participantsAddSpexare(revivalId: $id, spexareIds: $ids) { id } }",
                Map.of("id", revival, "ids", List.of(7, 8))).path("participantsAddSpexare[0].id").entity(String.class).get());

        invite(pending, spexare);
        assertThat(MAIL.getReceivedMessages()).anySatisfy(message -> assertThat(message.getAllRecipients()[0].toString()).isEqualTo(PARTICIPANT));
        SPEXREGISTER.verify(postRequestedFor(urlEqualTo("/api/graphql")).withHeader("X-Acting-Person", containing("via%20Nyupps%C3%A4ttning")));

        // The participant logs in with their FGV account and is matched by email
        login(PARTICIPANT);
        execute(PARTICIPANT, MINE, Map.of("id", revival))
                .path("myParticipation.participant.id").entity(String.class).isEqualTo(String.valueOf(pending))
                .path("myParticipation.revival.permissions.isParticipant").entity(Boolean.class).isEqualTo(true)
                .path("myParticipation.revival.permissions.canReadWork").entity(Boolean.class).isEqualTo(false)
                .path("myParticipation.revival.permissions.canReadParticipants").entity(Boolean.class).isEqualTo(false)
                .path("myParticipation.revival.permissions.canSubscribeCalendar").entity(Boolean.class).isEqualTo(true)
                .path("myParticipation.revival.myRole").entity(String.class).isEqualTo("PARTICIPANT")
                .path("myParticipation.revival.members").entityList(Object.class).hasSize(0)
                .path("myParticipation.steeringGroup[*].displayName").entityList(String.class).contains("Stina Styrgrupp")
                .path("myParticipation.contact.email").entity(String.class).isEqualTo(PARTICIPANT);
        execute(STEERING_GROUP, PARTICIPANTS, Map.of("id", revival)).path("participants[0].invitation").entity(String.class).isEqualTo("ACTIVE");

        // Yes without the terms is refused; with them and consent, the pending person becomes a spexare
        expectError(execute(PARTICIPANT, RESPOND, Map.of("input", Map.of("revivalId", revival, "response", "YES", "registerConsent", true))), "BAD_REQUEST");
        execute(PARTICIPANT, RESPOND, Map.of("input", Map.of("revivalId", revival, "response", "YES", "termsAccepted", true, "registerConsent", true)))
                .path("participationRespond.participant.response").entity(String.class).isEqualTo("YES")
                .path("participationRespond.participant.pending").entity(Boolean.class).isEqualTo(false);
        assertThat(jdbcClient.sql("SELECT spexare_id FROM participant WHERE id = ?").param(pending).query(Long.class).single()).isEqualTo(99L);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM pending_person").query(Integer.class).single()).isZero();
        SPEXREGISTER.verify(postRequestedFor(urlEqualTo("/api/graphql")).withRequestBody(containing("SpexareCreate"))
                .withHeader("X-Acting-Person", equalTo("participant%20via%20revival%20" + revival + "%20%28Nyupps%C3%A4ttning%29")));
        SPEXREGISTER.verify(postRequestedFor(urlEqualTo("/api/graphql")).withRequestBody(containing("ConsentCreate")));

        // Dietary needs: theirs, and the directeur's to read; not the steering group's without CATERING
        execute(PARTICIPANT, "mutation($id: ID!) { participationDietarySet(revivalId: $id, text: \"Nötter\") { dietary } }", Map.of("id", revival))
                .path("participationDietarySet.dietary").entity(String.class).isEqualTo("Nötter");
        execute(DIRECTEUR, PARTICIPANTS, Map.of("id", revival)).path("participants[?(@.id == '%d')].dietary".formatted(pending)).entityList(String.class)
                .containsExactly("Nötter");
        execute(STEERING_GROUP, PARTICIPANTS, Map.of("id", revival)).path("participants[0].dietaryReadable").entity(Boolean.class).isEqualTo(false);

        // Fees: writers of the budget only
        expectError(execute(STEERING_GROUP, "mutation($id: ID!, $v: Int!) { participantFeeSetPaid(id: $id, version: $v, paid: true) { feePaid } }",
                Map.of("id", pending, "v", participantVersion(pending))), "FORBIDDEN");
        execute(DIRECTEUR, "mutation($id: ID!, $v: Int!) { participantFeeSetPaid(id: $id, version: $v, paid: true) { feePaid } }",
                Map.of("id", pending, "v", participantVersion(pending))).path("participantFeeSetPaid.feePaid").entity(Boolean.class).isEqualTo(true);

        // Observers do not read participants
        expectError(execute(OBSERVER, PARTICIPANTS, Map.of("id", revival)), "FORBIDDEN");
        execute(OBSERVER, "query($id: ID!) { revival(id: $id) { participantCounts { total } participationSettings { version } } }", Map.of("id", revival))
                .path("revival.participantCounts").valueIsNull()
                .path("revival.participationSettings").valueIsNull();
        execute(STEERING_GROUP, "query($id: ID!) { revival(id: $id) { participantCounts { total notInvited } participationSettings { version } } }",
                Map.of("id", revival))
                .path("revival.participantCounts.total").entity(Integer.class).isEqualTo(2)
                .path("revival.participationSettings.version").entity(Integer.class).isEqualTo(0);
    }

    /**
     * A participant and a helper see their open revival for their own page and nothing of the steering group's.
     */
    @Test
    void participantsAndHelpersAreRefusedTheSteeringGroupsWork() {
        invite(addPending("PARTICIPANT", PARTICIPANT), addPending("HELPER", HELPER));
        login(PARTICIPANT);
        login(HELPER);

        for (final String user : List.of(PARTICIPANT, HELPER)) {
            final Map<String, Object> id = Map.of("id", revival);

            expectError(execute(user, "query($id: ID!) { logEntries(revivalId: $id) { id } }", id), "FORBIDDEN");
            expectError(execute(user, "query($id: ID!) { meetings(revivalId: $id) { id } }", id), "FORBIDDEN");
            expectError(execute(user, "query($id: ID!) { checklists(revivalId: $id) { id } }", id), "FORBIDDEN");
            expectError(execute(user, "query($id: ID!) { checklistItems(revivalId: $id) { id } }", id), "FORBIDDEN");
            expectError(execute(user, PARTICIPANTS, id), "FORBIDDEN");
            execute(user, "query($id: ID!) { guideSuggestions(revivalId: $id) { id } }", id).path("guideSuggestions").entityList(Object.class).hasSize(0);
            expectError(execute(user, "query($id: ID!) { guideVersionForRevival: revival(id: $id) { guideVersionHistory { toLabel } eventProposals { startDate } } }",
                    id), "FORBIDDEN");
            expectError(execute(user, "query($id: ID!) { revival(id: $id) { workSummary { openEntries } } }", id), "FORBIDDEN");
            execute(user, """
                    query($id: ID!) {
                        revival(id: $id) { id title members { id } ownerCandidates { id } statusHistory { to } guideVersion { id } budget { revivalId }
                            participantCounts { total } referrals { id } permissions { canReadWork canWriteLog canReadBudget isParticipant } }
                    }""", id)
                    .path("revival.members").entityList(Object.class).hasSize(0)
                    .path("revival.ownerCandidates").entityList(Object.class).hasSize(0)
                    .path("revival.statusHistory").entityList(Object.class).hasSize(0)
                    .path("revival.guideVersion").valueIsNull()
                    .path("revival.budget").valueIsNull()
                    .path("revival.participantCounts").valueIsNull()
                    .path("revival.permissions.canWriteLog").entity(Boolean.class).isEqualTo(false)
                    .path("revival.permissions.isParticipant").entity(Boolean.class).isEqualTo(true);
            execute(user, "{ me { revivalRoles { role revival { id } } } }", Map.of())
                    .path("me.revivalRoles[0].role").entity(String.class).isEqualTo(user.equals(PARTICIPANT) ? "PARTICIPANT" : "HELPER");
        }
        // Participants read the events; helpers only get the events they hold shifts in, on their own page
        execute(PARTICIPANT, "query($id: ID!) { events(revivalId: $id) { id } }", Map.of("id", revival)).path("events").entityList(Object.class).hasSize(0);
        expectError(execute(HELPER, "query($id: ID!) { events(revivalId: $id) { id } }", Map.of("id", revival)), "FORBIDDEN");

        // Once the revival leaves OPEN they see nothing
        setStatus(revival, "CLOSING");
        expectError(execute(PARTICIPANT, MINE, Map.of("id", revival)), "NOT_FOUND");
    }

    @Test
    void mcpToolsRefuseParticipantsAndNeverShowContactDetails() {
        invite(addPending("PARTICIPANT", PARTICIPANT));
        login(PARTICIPANT);

        for (final String tool : List.of("list_participants", "get_revival_status", "list_events", "list_log_entries", "list_checklist_items",
                "get_budget_summary")) {
            final JsonNode response = mcpRpc(mcpToken(PARTICIPANT), "tools/call", Map.of("name", tool, "arguments", Map.of("revivalId", revival)));

            assertThat(response.has("error") || response.path("result").path("isError").asBoolean(false)).as(tool).isTrue();
        }
        assertThat(listRevivals(mcpToken(PARTICIPANT), Map.of())).hasSize(1);

        final JsonNode listed = mcpRpc(mcpToken(STEERING_GROUP), "tools/call", Map.of("name", "list_participants", "arguments", Map.of("revivalId", revival)));

        assertThat(listed.path("result").path("isError").asBoolean(false)).isFalse();
        assertThat(listed.toString()).contains("Pia Deltagare").doesNotContain(PARTICIPANT).doesNotContain("@");
    }

    @Test
    void invitationsNeedAnOpenRevivalAndSkipThoseWithoutAnAddress() {
        final long helper = Long.parseLong(execute(STEERING_GROUP, ADD, Map.of("input", Map.of("revivalId", revival, "kind", "HELPER", "firstName", "Hugo",
                "lastName", "Springare", "phone", "0701234567"))).path("participantAdd.id").entity(String.class).get());
        final long taken = addPending("PARTICIPANT", STEERING_GROUP);

        execute(STEERING_GROUP, INVITE, Map.of("input", Map.of("revivalId", revival, "participantIds", List.of(helper, taken), "language", "en")))
                .path("participantsInvite.skipped[*].reason").entityList(String.class).containsExactly("NO_EMAIL", "ALREADY_MEMBER");

        setStatus(revival, "CLOSING");
        expectError(execute(STEERING_GROUP, INVITE, Map.of("input", Map.of("revivalId", revival, "participantIds", List.of(helper), "language", "sv"))),
                "BAD_REQUEST");
    }

    /**
     * Leaving OPEN deletes pending people who never answered; the revival can still be deleted with them.
     */
    @Test
    void leavingOpenDeletesUnansweredPendingPeopleAndDeletionTakesEverything() {
        final long unanswered = addPending("PARTICIPANT", "ny@example.org");
        final long spexare = Long.parseLong(execute(STEERING_GROUP, "mutation($id: ID!) { participantsAddSpexare(revivalId: $id, spexareIds: [7]) { id } }",
                Map.of("id", revival)).path("participantsAddSpexare[0].id").entity(String.class).get());

        invite(unanswered);
        execute(STEERING_GROUP, "mutation($input: ParticipationSettingsInput!) { participationSettingsUpdate(input: $input) { version } }",
                Map.of("input", Map.of("revivalId", revival, "version", 0, "swishNumber", "123 456 78 90")));
        setStatus(revival, "CLOSING");

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM pending_person").query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT removed_at IS NOT NULL FROM participant WHERE id = ?").param(unanswered).query(Boolean.class).single()).isTrue();
        assertThat(jdbcClient.sql("SELECT removed_at IS NULL FROM participant WHERE id = ?").param(spexare).query(Boolean.class).single()).isTrue();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM revival_member WHERE role = 'PARTICIPANT' AND removed_at IS NULL").query(Integer.class).single())
                .isZero();

        final GraphQlTester.Response deleted = execute(SUPER_ADMIN, "mutation($id: ID!, $v: Int!) { revivalDelete(id: $id, version: $v) }",
                Map.of("id", revival, "v", version(revival)));

        deleted.path("revivalDelete").entity(String.class).isEqualTo(String.valueOf(revival));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM participant").query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM participation_settings").query(Integer.class).single()).isZero();
    }

    /**
     * The daily job deletes dietary needs once the revival's last show is past, and only then.
     */
    @Test
    void dietaryNeedsAreDeletedAfterTheLastShow() {
        invite(addPending("PARTICIPANT", PARTICIPANT));
        login(PARTICIPANT);
        execute(PARTICIPANT, "mutation($id: ID!) { participationDietarySet(revivalId: $id, text: \"Gluten\") { dietary } }", Map.of("id", revival));
        jdbcClient.sql("INSERT INTO revival_show (revival_id, show_date) VALUES (?, ?)").params(revival, java.time.LocalDate.now().plusDays(1)).update();

        retention.daily();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM dietary_need").query(Integer.class).single()).isEqualTo(1);

        jdbcClient.sql("UPDATE revival_show SET show_date = ? WHERE revival_id = ?").params(java.time.LocalDate.now().minusDays(1), revival).update();
        retention.daily();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM dietary_need").query(Integer.class).single()).isZero();
    }
}
