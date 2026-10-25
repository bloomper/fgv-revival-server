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

package nu.fgv.revival.server.mailing;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mailings end to end: audiences resolved per kind, the steering group drafts, a member with
 * COMMS or the directeur sends one multipart message per recipient with replies to the sender, failed recipients are
 * sent again, the mail server being down records nothing, and the mailing plan with its reminders. Participants are
 * pending people; the mail server is GreenMail.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class MailingGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ADD = "mutation($input: ParticipantAddInput!) { participantAdd(input: $input) { id version } }";
    private static final String INVITE = "mutation($input: ParticipantsInviteInput!) { participantsInvite(input: $input) { invited { id } } }";
    private static final String MAILING_FIELDS = """
            id status subject body canSend version audience { kind position { id } event { id } } createdBy { displayName } sentBy { displayName }
            recipients { displayName status } plannedFor { template { id } event { id } dueDate due }""";
    private static final String CREATE = "mutation($input: MailingInput!) { mailingCreate(input: $input) { " + MAILING_FIELDS + " } }";
    private static final String SEND = "mutation($id: ID!, $v: Int!) { mailingSend(id: $id, version: $v) { " + MAILING_FIELDS + " } }";
    private static final String PREVIEW = """
            query($id: ID!, $audience: MailingAudienceInput!) { mailingAudiencePreview(revivalId: $id, audience: $audience) { displayName hasAddress } }""";
    private static final String PLAN = """
            query($id: ID!) { revival(id: $id) { mailingPlan { template { id } event { id } dueDate due mailing { id status } } } }""";
    private static final String TEMPLATE_CREATE = "mutation($input: MailingTemplateInput!) { mailingTemplateCreate(input: $input) { id eventType { id } } }";

    @Autowired
    private MailingReminders reminders;

    private long revival;
    private long participant;
    private long helper;
    private long weekend;

    private static Map<String, Object> audience(final String kind) {
        return Map.of("kind", kind);
    }

    private static String partOf(final Part part, final String type) throws Exception {
        if (part.isMimeType(type)) {
            return part.getContent().toString();
        }
        if (part.getContent() instanceof final Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                final BodyPart body = multipart.getBodyPart(index);
                final String found = partOf(body, type);

                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @BeforeEach
    void openRevivalWithParticipants() {
        revival = organisingRevival();
        setStatus(revival, "OPEN");
        SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing("query Tasks"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"taskPaged": {"edges": [{"node": {"id": "BAND", "name": "Orkester", "category": {"id": "2", "name": "Orkester", "actorPresent": false}}}],
                          "totalCount": 1}}}""")));
        participant = pending("PARTICIPANT", "Pia", "Deltagare", PARTICIPANT);
        helper = pending("HELPER", "Hugo", "Springare", HELPER);
        pending("PARTICIPANT", "Nils", "Nej", "nils@example.org");
        execute(STEERING_GROUP, INVITE, Map.of("input", Map.of("revivalId", revival, "participantIds", List.of(participant, helper), "language", "sv")))
                .path("participantsInvite.invited").entityList(Object.class).hasSize(2);
        login(PARTICIPANT);
        execute(PARTICIPANT, "mutation($input: ParticipationRespondInput!) { participationRespond(input: $input) { participant { response } } }",
                Map.of("input", Map.of("revivalId", revival, "response", "YES", "termsAccepted", true, "registerConsent", false)));
        weekend = Long.parseLong(execute(DIRECTEUR, "mutation($input: EventCreateInput!) { eventCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "title", "Föreställningshelg", "startDate", "2028-05-05", "endDate", "2028-05-06")))
                .path("eventCreate.id").entity(String.class).get());
        MAIL.reset();
    }

    private long pending(final String kind, final String firstName, final String lastName, final String email) {
        return Long.parseLong(execute(STEERING_GROUP, ADD, Map.of("input", Map.of("revivalId", revival, "kind", kind, "firstName", firstName,
                "lastName", lastName, "email", email))).path("participantAdd.id").entity(String.class).get());
    }

    private void giveSteeringGroup(final String... capabilities) {
        final Map<String, Object> member = jdbcClient.sql("SELECT id, version FROM revival_member WHERE revival_id = ? AND role = 'STEERING_GROUP'")
                .param(revival).query().singleRow();

        execute(DIRECTEUR, "mutation($input: RevivalMemberCapabilitiesInput!) { revivalMemberCapabilitiesSet(input: $input) { id } }",
                Map.of("input", Map.of("id", member.get("id"), "version", member.get("version"), "capabilities", List.of(capabilities))));
    }

    private long draft(final String user, final Map<String, Object> audience, final String subject) {
        return Long.parseLong(execute(user, CREATE, Map.of("input", Map.of("revivalId", revival, "audience", audience, "subject", subject,
                "body", "Hej **alla**!\n\n- Biljettkontroll\n- Bålvakt\n\n<script>alert(1)</script>"))).path("mailingCreate.id").entity(String.class).get());
    }

    private long mailingVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM mailing WHERE id = ?").param(id).query(Long.class).single();
    }

    private List<String> previewNames(final Map<String, Object> audience) {
        return execute(STEERING_GROUP, PREVIEW, Map.of("id", revival, "audience", audience))
                .path("mailingAudiencePreview[*].displayName").entityList(String.class).get();
    }

    @Test
    void audiencesResolvePerKind() {
        final long version = jdbcClient.sql("SELECT version FROM participant WHERE id = ?").param(participant).query(Long.class).single();

        execute(STEERING_GROUP, "mutation($input: ParticipantUpdateInput!) { participantUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", participant, "version", version, "positionId", "BAND")));
        login(HELPER);

        assertThat(previewNames(audience("PARTICIPANTS"))).containsExactly("Pia Deltagare");
        assertThat(previewNames(audience("CONFIRMED"))).containsExactly("Pia Deltagare");
        assertThat(previewNames(Map.of("kind", "POSITION", "positionId", "BAND"))).containsExactly("Pia Deltagare");
        assertThat(previewNames(audience("HELPERS"))).containsExactly("Hugo Springare");
        assertThat(previewNames(audience("NON_RESPONDERS"))).containsExactly("Hugo Springare");
        assertThat(previewNames(audience("STEERING_GROUP"))).containsExactly("Dana Directeur", "Stina Styrgrupp");
        assertThat(previewNames(Map.of("kind", "EVENT_ATTENDEES", "eventId", weekend))).isEmpty();
        execute(STEERING_GROUP, PREVIEW, Map.of("id", revival, "audience", audience("CONFIRMED")))
                .path("mailingAudiencePreview[0].hasAddress").entity(Boolean.class).isEqualTo(true);

        // Attendees: available on a day of the event, or holding a shift in it
        execute(PARTICIPANT, "mutation($input: AvailabilityInput!) { availabilitySet(input: $input) { participant { id } } }",
                Map.of("input", Map.of("revivalId", revival, "eventId", weekend, "date", "2028-05-06", "answer", "MAYBE")));
        final long slot = Long.parseLong(execute(DIRECTEUR, "mutation($input: ShiftSlotInput!) { shiftSlotCreate(input: $input) { id } }",
                Map.of("input", Map.of("eventId", weekend, "date", "2028-05-05", "startTime", "17:00", "endTime", "19:00", "task", "Kassa", "needed", 1)))
                .path("shiftSlotCreate.id").entity(String.class).get());

        execute(DIRECTEUR, "mutation($slot: ID!, $p: ID!) { shiftAssign(slotId: $slot, participantId: $p) { id } }", Map.of("slot", slot, "p", helper));
        assertThat(previewNames(Map.of("kind", "EVENT_ATTENDEES", "eventId", weekend))).containsExactly("Hugo Springare", "Pia Deltagare");

        // What a kind needs
        expectError(execute(STEERING_GROUP, PREVIEW, Map.of("id", revival, "audience", Map.of("kind", "POSITION"))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, PREVIEW, Map.of("id", revival, "audience", Map.of("kind", "POSITION", "positionId", "NOPE"))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, PREVIEW, Map.of("id", revival, "audience", audience("EVENT_ATTENDEES"))), "BAD_REQUEST");
        expectError(execute(OBSERVER, PREVIEW, Map.of("id", revival, "audience", audience("CONFIRMED"))), "FORBIDDEN");
        expectError(execute(PARTICIPANT, PREVIEW, Map.of("id", revival, "audience", audience("CONFIRMED"))), "FORBIDDEN");
    }

    @Test
    void theSteeringGroupDraftsAndCommsSends() throws Exception {
        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "audience", audience("CONFIRMED"), "subject", " ",
                "body", "Hej"))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "audience", audience("CONFIRMED"), "subject", "Hej",
                "body", "x".repeat(20_001)))), "BAD_REQUEST");
        expectError(execute(PARTICIPANT, CREATE, Map.of("input", Map.of("revivalId", revival, "audience", audience("CONFIRMED"), "subject", "Hej",
                "body", "Hej"))), "FORBIDDEN");

        final long id = draft(STEERING_GROUP, audience("CONFIRMED"), "Schema för helgen");

        execute(STEERING_GROUP, "query($id: ID!) { mailing(id: $id) { canSend status createdBy { displayName } } }", Map.of("id", id))
                .path("mailing.canSend").entity(Boolean.class).isEqualTo(false)
                .path("mailing.status").entity(String.class).isEqualTo("DRAFT")
                .path("mailing.createdBy.displayName").entity(String.class).isEqualTo("Stina Styrgrupp");
        expectError(execute(STEERING_GROUP, SEND, Map.of("id", id, "v", mailingVersion(id))), "FORBIDDEN");
        execute(STEERING_GROUP, "mutation($id: ID!, $v: Int!, $input: MailingInput!) { mailingUpdate(id: $id, version: $v, input: $input) { subject } }",
                Map.of("id", id, "v", mailingVersion(id), "input", Map.of("audience", audience("CONFIRMED"), "subject", "Schema för föreställningshelgen",
                        "body", "Hej **alla**!\n\n- Biljettkontroll\n\n<script>alert(1)</script>")))
                .path("mailingUpdate.subject").entity(String.class).isEqualTo("Schema för föreställningshelgen");

        // With COMMS: send, refused on an old version
        giveSteeringGroup("COMMS");
        execute(STEERING_GROUP, "query($id: ID!) { mailing(id: $id) { canSend } }", Map.of("id", id)).path("mailing.canSend").entity(Boolean.class)
                .isEqualTo(true);
        expectError(execute(STEERING_GROUP, SEND, Map.of("id", id, "v", 0)), "CONFLICT");
        execute(STEERING_GROUP, SEND, Map.of("id", id, "v", mailingVersion(id)))
                .path("mailingSend.status").entity(String.class).isEqualTo("SENT")
                .path("mailingSend.sentBy.displayName").entity(String.class).isEqualTo("Stina Styrgrupp")
                .path("mailingSend.canSend").entity(Boolean.class).isEqualTo(false)
                .path("mailingSend.recipients[*].status").entityList(String.class).containsExactly("SENT");

        final MimeMessage[] messages = MAIL.getReceivedMessages();

        assertThat(messages).hasSize(1);
        assertThat(messages[0].getAllRecipients()[0].toString()).isEqualTo(PARTICIPANT);
        assertThat(messages[0].getReplyTo()[0].toString()).isEqualTo(STEERING_GROUP);
        assertThat(messages[0].getSubject()).isEqualTo("Schema för föreställningshelgen");
        assertThat(partOf(messages[0], "text/plain")).contains("Hej **alla**!").contains("Stina Styrgrupp");
        assertThat(partOf(messages[0], "text/html")).contains("<strong>alla</strong>").contains("<li>Biljettkontroll</li>")
                .contains("&lt;script&gt;").doesNotContain("<script>").contains("Ali Baba").contains("Stina Styrgrupp");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM mailing_recipient").query(Integer.class).single()).isEqualTo(1);

        // Sent mailings are not changed or sent again
        expectError(execute(STEERING_GROUP, SEND, Map.of("id", id, "v", mailingVersion(id))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, "mutation($id: ID!, $v: Int!) { mailingCancel(id: $id, version: $v) { status } }",
                Map.of("id", id, "v", mailingVersion(id))), "BAD_REQUEST");

        // Retry: only the failed recipients, once more
        expectError(execute(STEERING_GROUP, "mutation($id: ID!) { mailingRetry(id: $id) { status } }", Map.of("id", id)), "BAD_REQUEST");
        jdbcClient.sql("UPDATE mailing_recipient SET status = 'FAILED'").update();
        MAIL.reset();
        execute(DIRECTEUR, "mutation($id: ID!) { mailingRetry(id: $id) { recipients { status } } }", Map.of("id", id))
                .path("mailingRetry.recipients[*].status").entityList(String.class).containsExactly("SENT");
        assertThat(MAIL.getReceivedMessages()).hasSize(1);
        assertThat(jdbcClient.sql("SELECT attempt FROM mailing_recipient").query(Integer.class).single()).isEqualTo(2);

        // Lists: newest first, and never an address
        final long cancelled = draft(DIRECTEUR, audience("HELPERS"), "Till springarna");

        execute(DIRECTEUR, "mutation($id: ID!, $v: Int!) { mailingCancel(id: $id, version: $v) { status } }",
                Map.of("id", cancelled, "v", mailingVersion(cancelled))).path("mailingCancel.status").entity(String.class).isEqualTo("CANCELLED");
        final String listed = execute(SUPER_ADMIN, "query($id: ID!) { mailings(revivalId: $id) { id status recipients { displayName status } } }",
                Map.of("id", revival)).path("mailings[*].id").entityList(String.class).containsExactly(String.valueOf(cancelled), String.valueOf(id))
                .path("mailings").entityList(Object.class).get().toString();

        assertThat(listed).doesNotContain("@");
        expectError(execute(OBSERVER, "query($id: ID!) { mailings(revivalId: $id) { id } }", Map.of("id", revival)), "FORBIDDEN");
        expectError(execute(PARTICIPANT, "query($id: ID!) { mailing(id: $id) { id } }", Map.of("id", id)), "NOT_FOUND");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM mailing_recipient_audit").query(Integer.class).single()).isPositive();
    }

    @Test
    void withTheMailServerDownNothingIsSentOrRecorded() {
        final long id = draft(DIRECTEUR, audience("STEERING_GROUP"), "Möte");

        MAIL.stop();
        try {
            expectError(execute(DIRECTEUR, SEND, Map.of("id", id, "v", mailingVersion(id))), "MAIL_UNAVAILABLE");
        } finally {
            MAIL.start();
        }
        assertThat(jdbcClient.sql("SELECT status FROM mailing WHERE id = ?").param(id).query(String.class).single()).isEqualTo("DRAFT");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM mailing_recipient").query(Integer.class).single()).isZero();

        // An audience without addresses is recorded as such
        execute(DIRECTEUR, SEND, Map.of("id", id, "v", mailingVersion(id)))
                .path("mailingSend.recipients[*].status").entityList(String.class).containsExactly("SENT", "SENT");
        assertThat(Arrays.stream(MAIL.getReceivedMessages()).map(message -> {
            try {
                return message.getAllRecipients()[0].toString();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        })).containsExactlyInAnyOrder(DIRECTEUR, STEERING_GROUP);
    }

    @Test
    void thePlanShowsDueMailingsDraftsFromThemAndRemindsTheOwnersOnce() {
        final long rehearsalType = jdbcClient.sql("SELECT id FROM event_type WHERE name_sv = 'Rephelg 2'").query(Long.class).single();
        final Map<String, Object> input = new HashMap<>(Map.of("name", Map.of("sv", "Påminn om allergier", "en", "Remind about allergies"),
                "subject", Map.of("sv", "Allergier"), "body", Map.of("sv", "Fyll i dina allergier."), "audience", "CONFIRMED",
                "eventTypeId", rehearsalType, "due", Map.of("kind", "DAYS", "amount", -2)));

        expectError(execute(DIRECTEUR, TEMPLATE_CREATE, Map.of("input", input)), "FORBIDDEN");

        final String template = execute(SUPER_ADMIN, TEMPLATE_CREATE, Map.of("input", input))
                .path("mailingTemplateCreate.eventType.id").entity(String.class).isEqualTo(String.valueOf(rehearsalType))
                .path("mailingTemplateCreate.id").entity(String.class).get();
        final Map<String, Object> premiere = new HashMap<>(input);

        premiere.remove("eventTypeId");
        premiere.put("name", Map.of("sv", "Biljettsläpp"));
        premiere.put("due", Map.of("kind", "WEEKDAY_WEEK_BEFORE", "weekday", "MONDAY"));
        execute(SUPER_ADMIN, TEMPLATE_CREATE, Map.of("input", premiere));
        execute(STEERING_GROUP, "{ mailingTemplates { name { sv } due { kind amount weekday } } }", Map.of())
                .path("mailingTemplates[*].name.sv").entityList(String.class).containsExactly("Påminn om allergier", "Biljettsläpp");
        expectError(execute(PARTICIPANT, "{ mailingTemplates { id } }", Map.of()), "FORBIDDEN");

        // An event of the type two days from now: due tomorrow; the revival has no premiere, so that template has no entry
        final LocalDate start = LocalDate.now().plusDays(3);
        final long event = Long.parseLong(execute(DIRECTEUR, "mutation($input: EventCreateInput!) { eventCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "typeId", rehearsalType, "startDate", start.toString()))).path("eventCreate.id")
                .entity(String.class).get());

        execute(STEERING_GROUP, PLAN, Map.of("id", revival))
                .path("revival.mailingPlan").entityList(Object.class).hasSize(1)
                .path("revival.mailingPlan[0].event.id").entity(String.class).isEqualTo(String.valueOf(event))
                .path("revival.mailingPlan[0].dueDate").entity(String.class).isEqualTo(start.minusDays(2).toString())
                .path("revival.mailingPlan[0].due").entity(Boolean.class).isEqualTo(true);
        execute(OBSERVER, PLAN, Map.of("id", revival)).path("revival.mailingPlan").entityList(Object.class).hasSize(0);

        // The daily job reminds the directeur and COMMS once
        giveSteeringGroup("COMMS");
        reminders.daily();
        reminders.daily();
        assertThat(MAIL.getReceivedMessages()).hasSize(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM mailing_reminder").query(Integer.class).single()).isEqualTo(1);

        // A draft from the entry; sending it ends the entry being due
        final long id = Long.parseLong(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "audience", audience("CONFIRMED"),
                "subject", "Allergier", "body", "Fyll i dina allergier.", "templateId", template, "eventId", event)))
                .path("mailingCreate.plannedFor.event.id").entity(String.class).isEqualTo(String.valueOf(event))
                .path("mailingCreate.id").entity(String.class).get());

        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "audience", audience("CONFIRMED"),
                "subject", "Allergier", "body", "Fyll i.", "templateId", template, "eventId", weekend))), "BAD_REQUEST");
        execute(STEERING_GROUP, PLAN, Map.of("id", revival)).path("revival.mailingPlan[0].mailing.id").entity(String.class).isEqualTo(String.valueOf(id));
        execute(STEERING_GROUP, SEND, Map.of("id", id, "v", mailingVersion(id)));
        execute(STEERING_GROUP, PLAN, Map.of("id", revival)).path("revival.mailingPlan[0].due").entity(Boolean.class).isEqualTo(false)
                .path("revival.mailingPlan[0].mailing.status").entity(String.class).isEqualTo("SENT");

        // With a premiere, that template has an entry too
        jdbcClient.sql("UPDATE revival SET premiere_date = ? WHERE id = ?").params(LocalDate.of(2028, 5, 5), revival).update();
        execute(STEERING_GROUP, PLAN, Map.of("id", revival)).path("revival.mailingPlan[*].dueDate").entityList(String.class)
                .contains("2028-04-24");
    }

    @Test
    void mcpDraftsButNeverSends() {
        final JsonNode drafted = mcpRpc(mcpToken(STEERING_GROUP), "tools/call", Map.of("name", "draft_mailing", "arguments", Map.of("revivalId", revival,
                "subject", "Hej", "body", "Välkomna!", "audience", "CONFIRMED")));

        assertThat(drafted.path("result").path("isError").asBoolean(false)).as(drafted.toString()).isFalse();
        assertThat(jdbcClient.sql("SELECT status FROM mailing").query(String.class).single()).isEqualTo("DRAFT");
        assertThat(MAIL.getReceivedMessages()).isEmpty();
        assertThat(jdbcClient.sql("SELECT r.source FROM mailing_audit a JOIN revinfo r ON r.id = a.rev").query(String.class).single()).isEqualTo("MCP");

        final List<String> refused = new ArrayList<>();

        for (final String user : List.of(PARTICIPANT, OBSERVER)) {
            final JsonNode response = mcpRpc(mcpToken(user), "tools/call", Map.of("name", "draft_mailing", "arguments", Map.of("revivalId", revival,
                    "subject", "Hej", "body", "Hej", "audience", "CONFIRMED")));

            if (response.has("error") || response.path("result").path("isError").asBoolean(false)) {
                refused.add(user);
            }
        }
        assertThat(refused).containsExactly(PARTICIPANT, OBSERVER);
    }
}
