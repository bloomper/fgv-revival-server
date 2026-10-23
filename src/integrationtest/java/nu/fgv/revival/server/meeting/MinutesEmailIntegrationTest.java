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


package nu.fgv.revival.server.meeting;

import jakarta.mail.internet.MimeMessage;
import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minutes by email with GreenMail (US3, FR-021, SC-011): to every directeur and steering group member who can log in
 * and see the revival, and nobody else; no address is stored.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class MinutesEmailIntegrationTest extends AbstractIntegrationTest {

    private static final String SEND = "mutation($input: MinutesEmailSendInput!) { minutesEmailSend(input: $input) { id language waitingLeftOut "
            + "sentBy { displayName } recipients { person { displayName } status } } }";
    private static final String RETRY = "mutation($id: ID!) { minutesEmailRetry(emailId: $id) { id recipients { person { displayName } status } } }";

    private long revival;
    private long meeting;

    @BeforeEach
    void closedMeeting() {
        revival = organisingRevival();
        addMember(revival, "waiting" + DOMAIN, "STEERING_GROUP", List.of());
        final long removed = addMember(revival, OBSERVER, "STEERING_GROUP", List.of());

        graphQl(SUPER_ADMIN).document("mutation($id: ID!) { revivalMemberRemove(id: $id) }").variable("id", removed).execute();
        meeting = Long.parseLong(execute(STEERING_GROUP, MeetingGraphqlApiIntegrationTest.CREATE,
                Map.of("input", Map.of("revivalId", revival, "date", LocalDate.now().toString(), "title", "Direktionsmöte 4")))
                .path("meetingCreate.id").entity(String.class).get());
        execute(STEERING_GROUP, MeetingGraphqlApiIntegrationTest.START, Map.of("id", meeting, "version", version()));
        execute(STEERING_GROUP, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "type", "DECISION", "text", "Vi spelar i RunAn", "meetingId", meeting)));
        execute(STEERING_GROUP, MeetingGraphqlApiIntegrationTest.CLOSE, Map.of("id", meeting, "version", version()));
        // Adding the waiting member emails Keycloak's invitation and tells the super admins
        MAIL.reset();
    }

    private long version() {
        return jdbcClient.sql("SELECT version FROM meeting WHERE id = ?").param(meeting).query(Long.class).single();
    }

    private static List<String> recipients() throws Exception {
        return Arrays.stream(MAIL.getReceivedMessages()).map(message -> {
            try {
                return message.getAllRecipients()[0].toString();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }).sorted().toList();
    }

    @Test
    void reachesEveryDirecteurAndSteeringGroupMemberWhoCanLogInAndNobodyElse() throws Exception {
        execute(STEERING_GROUP, "query($id: ID!) { meeting(id: $id) { canEmailMinutes } }", Map.of("id", meeting))
                .path("meeting.canEmailMinutes").entity(Boolean.class).isEqualTo(true);
        execute(DIRECTEUR, SEND, Map.of("input", Map.of("meetingId", meeting, "language", "en")))
                .path("minutesEmailSend.language").entity(String.class).isEqualTo("en")
                .path("minutesEmailSend.waitingLeftOut").entity(Integer.class).isEqualTo(1)
                .path("minutesEmailSend.sentBy.displayName").entity(String.class).isEqualTo("Dana Directeur")
                .path("minutesEmailSend.recipients[*].status").entityList(String.class).containsExactly("SENT", "SENT");

        assertThat(recipients()).containsExactly(DIRECTEUR, STEERING_GROUP);

        final MimeMessage message = MAIL.getReceivedMessages()[0];

        assertThat(message.getSubject()).isEqualTo("Minutes: Ali Baba 2028, Direktionsmöte 4");
        assertThat(message.getContent().toString()).contains("Decisions").contains("#1 Vi spelar i RunAn")
                .contains("http://localhost:3001/revivals/%d/meetings/%d".formatted(revival, meeting));
        assertThat(message.getRecipients(jakarta.mail.Message.RecipientType.CC)).isNull();
        assertThat(message.getRecipients(jakarta.mail.Message.RecipientType.BCC)).isNull();
        execute(STEERING_GROUP, "query($id: ID!) { meeting(id: $id) { emails { recipients { status } } } }", Map.of("id", meeting))
                .path("meeting.emails").entityList(Object.class).hasSize(1);
    }

    @Test
    void aSteeringGroupMemberWhoNoLongerSeesTheRevivalIsNotSentTo() throws Exception {
        setStatus(revival, "SETTLING");
        execute(DIRECTEUR, SEND, Map.of("input", Map.of("meetingId", meeting, "language", "sv")));

        assertThat(recipients()).containsExactly(DIRECTEUR);
    }

    @Test
    void aFailedRecipientIsShownAndCanBeSentToAgain() throws Exception {
        final long stina = personId(STEERING_GROUP);

        jdbcClient.sql("UPDATE person SET email = 'inte en adress' WHERE id = ?").param(stina).update();

        final String emailId = execute(DIRECTEUR, SEND, Map.of("input", Map.of("meetingId", meeting, "language", "sv")))
                .path("minutesEmailSend.recipients[?(@.status == 'FAILED')].person.displayName").entityList(String.class)
                .containsExactly("Stina Styrgrupp")
                .path("minutesEmailSend.id").entity(String.class).get();

        assertThat(recipients()).containsExactly(DIRECTEUR);
        jdbcClient.sql("UPDATE person SET email = ? WHERE id = ?").param(STEERING_GROUP).param(stina).update();
        MAIL.reset();
        expectError(execute(OBSERVER, RETRY, Map.of("id", emailId)), "FORBIDDEN");
        expectError(execute(SUPER_ADMIN, RETRY, Map.of("id", emailId)), "FORBIDDEN");
        execute(DIRECTEUR, RETRY, Map.of("id", emailId))
                .path("minutesEmailRetry.recipients[*].person.displayName").entityList(String.class).containsExactly("Stina Styrgrupp");
        assertThat(recipients()).containsExactly(STEERING_GROUP);

        MAIL.reset();
        expectError(execute(DIRECTEUR, RETRY, Map.of("id", emailId)), "BAD_REQUEST");
        assertThat(recipients()).isEmpty();
    }

    @Test
    void aRetryNeedsSomeoneToSendToAndRevealsNothingToOutsiders() {
        final long stina = personId(STEERING_GROUP);

        jdbcClient.sql("UPDATE person SET email = 'inte en adress' WHERE id = ?").param(stina).update();

        final String emailId = execute(DIRECTEUR, SEND, Map.of("input", Map.of("meetingId", meeting, "language", "sv")))
                .path("minutesEmailSend.id").entity(String.class).get();

        jdbcClient.sql("UPDATE revival_member SET removed_at = NOW() WHERE revival_id = ? AND person_id = ?").params(revival, stina).update();
        expectError(execute(DIRECTEUR, RETRY, Map.of("id", emailId)), "BAD_REQUEST");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM minutes_email").query(Integer.class).single()).isEqualTo(1);

        login(PLAIN_USER);
        final List<String> messages = new ArrayList<>();

        for (final String id : List.of(emailId, "999999")) {
            execute(PLAIN_USER, RETRY, Map.of("id", id)).errors().satisfy(errors -> messages.add(errors.getFirst().getMessage()));
        }
        assertThat(messages).hasSize(2).allSatisfy(message -> assertThat(message).isEqualTo(messages.getFirst()));
    }

    @Test
    void withTheMailServerDownNothingIsRecorded() {
        MAIL.stop();
        try {
            expectError(execute(DIRECTEUR, SEND, Map.of("input", Map.of("meetingId", meeting, "language", "sv"))), "MAIL_UNAVAILABLE");
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM minutes_email").query(Integer.class).single()).isZero();
        } finally {
            MAIL.start();
        }
    }

    @Test
    void noAddressIsStoredOrLogged() throws Exception {
        execute(DIRECTEUR, SEND, Map.of("input", Map.of("meetingId", meeting, "language", "sv")));

        final List<String> columns = jdbcClient.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name IN ('minutes_email', 'minutes_email_recipient')""")
                .query(String.class).list();

        assertThat(columns).noneMatch(column -> column.toLowerCase().contains("mail") && !column.equalsIgnoreCase("email_id"));
        // The application's own loggers only; GreenMail logs the addresses it receives in the same JVM
        assertThat(Files.readAllLines(Path.of("build/integrationtest-data/log_data/spring.log")))
                .filteredOn(line -> line.contains(" n.f.r.") || line.contains(" nu.fgv."))
                .noneMatch(line -> line.contains(DIRECTEUR) || line.contains(STEERING_GROUP));
    }

    @Test
    void onlyAClosedMeetingIsSentAndOnlyByThoseWhoWriteTheLog() {
        final long planned = Long.parseLong(execute(STEERING_GROUP, MeetingGraphqlApiIntegrationTest.CREATE,
                Map.of("input", Map.of("revivalId", revival, "date", LocalDate.now().toString()))).path("meetingCreate.id").entity(String.class).get());

        expectError(execute(DIRECTEUR, SEND, Map.of("input", Map.of("meetingId", planned, "language", "sv"))), "BAD_REQUEST");
        expectError(execute(SUPER_ADMIN, SEND, Map.of("input", Map.of("meetingId", meeting, "language", "sv"))), "FORBIDDEN");
        expectError(execute(DIRECTEUR, SEND, Map.of("input", Map.of("meetingId", meeting, "language", "de"))), "BAD_REQUEST");
        execute(SUPER_ADMIN, "query($id: ID!) { meeting(id: $id) { canEmailMinutes } }", Map.of("id", meeting))
                .path("meeting.canEmailMinutes").entity(Boolean.class).isEqualTo(false);
    }
}
