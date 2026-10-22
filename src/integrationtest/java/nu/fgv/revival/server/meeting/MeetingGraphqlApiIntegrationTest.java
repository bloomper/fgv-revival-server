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

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A full meeting over GraphQL against MySQL and Keycloak (US3, FR-016–FR-020, SC-003).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class MeetingGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    static final String FIELDS = "id status version title agendaPoints othersPresent attendees { displayName } recorder { displayName } "
            + "openEntries { number } entries { number } blockingEntries { number } canEmailMinutes "
            + "minutes { decisions { number text owner { name person { displayName } } } todos { number owner { name person { displayName } } deadline } "
            + "questions { number } closed { number status } agendaPoints attendees { displayName } othersPresent recorder { displayName } }";
    static final String CREATE = "mutation($input: MeetingCreateInput!) { meetingCreate(input: $input) { " + FIELDS + " } }";
    static final String START = "mutation($id: ID!, $version: Int!) { meetingStart(id: $id, version: $version) { " + FIELDS + " } }";
    static final String CLOSE = "mutation($id: ID!, $version: Int!) { meetingClose(id: $id, version: $version) { " + FIELDS + " } }";
    static final String CANCEL = "mutation($id: ID!, $version: Int!) { meetingCancel(id: $id, version: $version) { status } }";
    private static final String ROW = "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id number } }";
    private static final String UPDATE = "mutation($input: LogEntryUpdateInput!) { logEntryUpdate(input: $input) { id } }";

    private long revival;

    @BeforeEach
    void revival() {
        revival = organisingRevival();
    }

    long createMeeting(final String user, final Map<String, Object> fields) {
        final Map<String, Object> input = new HashMap<>(fields);

        input.put("revivalId", revival);
        input.putIfAbsent("date", LocalDate.now().toString());
        return Long.parseLong(execute(user, CREATE, Map.of("input", input)).path("meetingCreate.id").entity(String.class).get());
    }

    long meetingVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM meeting WHERE id = ?").param(id).query(Long.class).single();
    }

    private long row(final Map<String, Object> fields) {
        final Map<String, Object> input = new HashMap<>(fields);

        input.put("revivalId", revival);
        return Long.parseLong(execute(STEERING_GROUP, ROW, Map.of("input", input)).path("logEntryCreate.id").entity(String.class).get());
    }

    private long rowVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM log_entry WHERE id = ?").param(id).query(Long.class).single();
    }

    @Test
    void aFullMeetingProducesMinutesOfWhatHappenedInIt() {
        final long stina = personId(STEERING_GROUP);
        final long dana = personId(DIRECTEUR);
        final long toClose = row(Map.of("type", "TODO", "text", "Boka RunAn", "owner", Map.of("personId", stina)));
        final long toAssign = row(Map.of("type", "TODO", "text", "Ring kåren", "owner", Map.of("name", "Sam")));
        final long untouched = row(Map.of("type", "DECISION", "text", "Spexpengen är 1500 kr", "status", "DONE"));
        final long meeting = createMeeting(STEERING_GROUP, Map.of("title", "Direktionsmöte 4", "attendeeIds", List.of(stina, dana),
                "othersPresent", "Sam från lokalen", "agendaPoints", List.of("Budget")));

        execute(STEERING_GROUP, "query($id: ID!) { meeting(id: $id) { openEntries { number } agendaPoints status } }", Map.of("id", meeting))
                .path("meeting.openEntries[*].number").entityList(Integer.class).containsExactly(1, 2)
                .path("meeting.agendaPoints").entityList(String.class).containsExactly("Budget");

        execute(STEERING_GROUP, START, Map.of("id", meeting, "version", meetingVersion(meeting)))
                .path("meetingStart.status").entity(String.class).isEqualTo("IN_PROGRESS")
                .path("meetingStart.recorder.displayName").entity(String.class).isEqualTo("Stina Styrgrupp");

        execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", toClose, "version", rowVersion(toClose), "meetingId", meeting, "status", "DONE")));
        execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", toAssign, "version", rowVersion(toAssign), "meetingId", meeting,
                "owner", Map.of("personId", dana))));
        row(Map.of("type", "DECISION", "text", "Vi spelar i RunAn", "meetingId", meeting, "owner", Map.of("personId", dana)));
        row(Map.of("type", "QUESTION", "text", "Var har vi kalaset?", "meetingId", meeting));
        final long unowned = row(Map.of("type", "TODO", "text", "Skicka offert", "meetingId", meeting, "deadline", "2026-11-01"));
        // Changed outside the meeting: not in the minutes
        execute(DIRECTEUR, UPDATE, Map.of("input", Map.of("id", untouched, "version", rowVersion(untouched), "comment", "Beslutat i maj")));

        execute(STEERING_GROUP, "query($id: ID!) { meeting(id: $id) { blockingEntries { number } entries { number } } }", Map.of("id", meeting))
                .path("meeting.blockingEntries[*].number").entityList(Integer.class).containsExactly(6)
                .path("meeting.entries[*].number").entityList(Integer.class).containsExactly(4, 5, 6);
        expectError(execute(STEERING_GROUP, CLOSE, Map.of("id", meeting, "version", meetingVersion(meeting))), "BAD_REQUEST");

        execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", unowned, "version", rowVersion(unowned), "meetingId", meeting,
                "owner", Map.of("name", "Sam på lokalen"))));
        execute(STEERING_GROUP, CLOSE, Map.of("id", meeting, "version", meetingVersion(meeting)))
                .path("meetingClose.status").entity(String.class).isEqualTo("CLOSED")
                .path("meetingClose.minutes.decisions[*].number").entityList(Integer.class).containsExactly(4)
                .path("meetingClose.minutes.decisions[0].owner.person.displayName").entity(String.class).isEqualTo("Dana Directeur")
                .path("meetingClose.minutes.todos[*].number").entityList(Integer.class).containsExactly(2, 6)
                .path("meetingClose.minutes.todos[1].owner.name").entity(String.class).isEqualTo("Sam på lokalen")
                .path("meetingClose.minutes.todos[1].deadline").entity(String.class).isEqualTo("2026-11-01")
                .path("meetingClose.minutes.questions[*].number").entityList(Integer.class).containsExactly(5)
                .path("meetingClose.minutes.closed[*].number").entityList(Integer.class).containsExactly(1)
                .path("meetingClose.minutes.attendees[*].displayName").entityList(String.class).hasSize(2).contains("Stina Styrgrupp", "Dana Directeur")
                .path("meetingClose.minutes.othersPresent").entity(String.class).isEqualTo("Sam från lokalen")
                .path("meetingClose.minutes.recorder.displayName").entity(String.class).isEqualTo("Stina Styrgrupp")
                .path("meetingClose.openEntries").entityList(Object.class).hasSize(0);

        // The minutes do not change when the rows do
        execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", unowned, "version", rowVersion(unowned), "text", "Annan text")));
        execute(OBSERVER, "query($id: ID!) { meeting(id: $id) { minutes { todos { number } } } }", Map.of("id", meeting))
                .path("meeting.minutes.todos[*].number").entityList(Integer.class).containsExactly(2, 6);
        execute(STEERING_GROUP, "query($id: ID!) { logEntries(revivalId: $id, filter: {meetingId: %d}) { number } }".formatted(meeting),
                Map.of("id", revival)).path("logEntries[*].number").entityList(Integer.class).containsExactly(4, 5, 6);
    }

    @Test
    void aWaitingToDoWithoutOwnerBlocksClosingButADoneOneDoesNot() {
        final long meeting = createMeeting(STEERING_GROUP, Map.of());

        row(Map.of("type", "TODO", "text", "Väntar på svar", "status", "WAITING"));
        row(Map.of("type", "TODO", "text", "Klar", "status", "DONE"));
        execute(STEERING_GROUP, START, Map.of("id", meeting, "version", meetingVersion(meeting)));

        execute(STEERING_GROUP, "query($id: ID!) { meeting(id: $id) { blockingEntries { number } } }", Map.of("id", meeting))
                .path("meeting.blockingEntries[*].number").entityList(Integer.class).containsExactly(1);
    }

    @Test
    void rowChangesNeedAMeetingInProgressInTheSameRevival() {
        final long planned = createMeeting(STEERING_GROUP, Map.of());

        expectError(execute(STEERING_GROUP, ROW, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "x", "meetingId", planned))),
                "BAD_REQUEST");
    }

    @Test
    void onlyOneMeetingIsInProgressEvenWhenStartedAtTheSameTime() throws Exception {
        final long first = createMeeting(STEERING_GROUP, Map.of());
        final long second = createMeeting(DIRECTEUR, Map.of());
        final CountDownLatch start = new CountDownLatch(1);
        final List<Callable<Boolean>> tasks = new ArrayList<>();

        token(STEERING_GROUP);
        token(DIRECTEUR);
        for (final long meeting : List.of(first, second)) {
            final long version = meetingVersion(meeting);

            tasks.add(() -> {
                start.await();
                return execute(meeting == first ? STEERING_GROUP : DIRECTEUR, START, Map.of("id", meeting, "version", version))
                        .returnResponse().getErrors().isEmpty();
            });
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            final List<Future<Boolean>> futures = tasks.stream().map(executor::submit).toList();

            start.countDown();
            assertThat(futures.stream().filter(future -> {
                try {
                    return future.get();
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
            }).count()).isEqualTo(1);
        }
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM meeting WHERE status = 'IN_PROGRESS'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aPlannedMeetingIsCancelledAndKept() {
        final long meeting = createMeeting(STEERING_GROUP, Map.of());

        execute(DIRECTEUR, CANCEL, Map.of("id", meeting, "version", meetingVersion(meeting)))
                .path("meetingCancel.status").entity(String.class).isEqualTo("CANCELLED");
        execute(STEERING_GROUP, "query($id: ID!) { meetings(revivalId: $id) { status } }", Map.of("id", revival))
                .path("meetings[*].status").entityList(String.class).containsExactly("CANCELLED");
    }

    @Test
    void superAdminsAndObserversReadButNeverChange() {
        final long meeting = createMeeting(STEERING_GROUP, Map.of());

        for (final String user : List.of(SUPER_ADMIN, OBSERVER)) {
            execute(user, "query($id: ID!) { meeting(id: $id) { status } }", Map.of("id", meeting)).path("meeting.status").entity(String.class)
                    .isEqualTo("PLANNED");
            expectError(execute(user, CREATE, Map.of("input", Map.of("revivalId", revival, "date", "2026-11-01"))), "FORBIDDEN");
            expectError(execute(user, START, Map.of("id", meeting, "version", meetingVersion(meeting))), "FORBIDDEN");
            expectError(execute(user, CANCEL, Map.of("id", meeting, "version", meetingVersion(meeting))), "FORBIDDEN");
        }
        login(PLAIN_USER);
        expectError(execute(PLAIN_USER, "query($id: ID!) { meeting(id: $id) { status } }", Map.of("id", meeting)), "NOT_FOUND");
    }
}
