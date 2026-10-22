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

import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryOrigin;
import nu.fgv.revival.server.decisionlog.LogEntryStatus;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.member.Owner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The minutes list exactly what was done in the meeting (FR-020, SC-003).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class MinutesTest {

    private static final long MEETING = 5L;
    private static final long KIM = 11L;

    private static LogEntry entry(final long id, final LogEntryType type, final LogEntryStatus status) {
        final LogEntry entry = new LogEntry();

        entry.setId(id);
        entry.setNumber((int) id);
        entry.setRevivalId(1L);
        entry.setDate(LocalDate.of(2026, 10, 21));
        entry.setType(type);
        entry.setText("Rad " + id);
        entry.setStatus(status);
        entry.setOrigin(LogEntryOrigin.MANUAL);
        return entry;
    }

    private static MeetingEntryChange change(final long entryId, final MeetingEntryChangeKind kind) {
        return new MeetingEntryChange(MEETING, entryId, kind, KIM, Instant.now());
    }

    private static Meeting meeting() {
        final Meeting meeting = new Meeting();

        meeting.setId(MEETING);
        meeting.setAgendaPoints(new ArrayList<>(List.of("Budget")));
        meeting.setAttendeeIds(new LinkedHashSet<>(List.of(KIM, 12L)));
        meeting.setOthersPresent("Sam från lokalen");
        meeting.setRecorderPersonId(KIM);
        return meeting;
    }

    @Test
    void listsOneChangeOfEachKindAndNothingElse() {
        final LogEntry decision = entry(7, LogEntryType.DECISION, LogEntryStatus.OPEN);
        final LogEntry todo = entry(8, LogEntryType.TODO, LogEntryStatus.OPEN);
        final LogEntry assigned = entry(3, LogEntryType.TODO, LogEntryStatus.OPEN);
        final LogEntry question = entry(9, LogEntryType.QUESTION, LogEntryStatus.OPEN);
        final LogEntry closed = entry(2, LogEntryType.TODO, LogEntryStatus.DONE);
        final LogEntry updated = entry(4, LogEntryType.DECISION, LogEntryStatus.OPEN);
        final LogEntry answered = entry(1, LogEntryType.QUESTION, LogEntryStatus.DONE);
        final LogEntry answer = entry(10, LogEntryType.DECISION, LogEntryStatus.OPEN);

        todo.setOwner(Owner.member(KIM));
        todo.setDeadline(LocalDate.of(2026, 11, 1));
        assigned.setOwner(Owner.named("Sam"));
        answer.setAnswersEntryId(1L);

        final Minutes minutes = MinutesBuilder.build(meeting(), List.of(
                change(7, MeetingEntryChangeKind.CREATED),
                change(8, MeetingEntryChangeKind.CREATED),
                change(3, MeetingEntryChangeKind.ASSIGNED),
                change(9, MeetingEntryChangeKind.CREATED),
                change(2, MeetingEntryChangeKind.CLOSED),
                change(2, MeetingEntryChangeKind.UPDATED),
                change(4, MeetingEntryChangeKind.UPDATED),
                change(1, MeetingEntryChangeKind.ANSWERED),
                change(1, MeetingEntryChangeKind.CLOSED),
                change(10, MeetingEntryChangeKind.CREATED)
        ), Map.of(7L, decision, 8L, todo, 3L, assigned, 9L, question, 2L, closed, 4L, updated, 1L, answered, 10L, answer));

        assertThat(minutes.decisions()).extracting(Minutes.Row::number).containsExactly(7, 10);
        assertThat(minutes.todos()).extracting(Minutes.Row::number).containsExactly(3, 8);
        assertThat(minutes.questions()).extracting(Minutes.Row::number).containsExactly(9);
        assertThat(minutes.closed()).extracting(Minutes.Row::number).containsExactly(1, 2);
        assertThat(minutes.todos().get(1)).isEqualTo(new Minutes.Row(8L, 8, LogEntryType.TODO, "Rad 8", KIM, null, LocalDate.of(2026, 11, 1),
                LogEntryStatus.OPEN));
        assertThat(minutes.todos().getFirst().ownerName()).isEqualTo("Sam");
        assertThat(minutes.agendaPoints()).containsExactly("Budget");
        assertThat(minutes.attendeeIds()).containsExactly(KIM, 12L);
        assertThat(minutes.othersPresent()).isEqualTo("Sam från lokalen");
        assertThat(minutes.recorderId()).isEqualTo(KIM);
    }

    @Test
    void rowsAppearOncePerListInNumberOrder() {
        final LogEntry todo = entry(8, LogEntryType.TODO, LogEntryStatus.DONE);
        final Minutes minutes = MinutesBuilder.build(meeting(), List.of(
                change(8, MeetingEntryChangeKind.CREATED), change(8, MeetingEntryChangeKind.ASSIGNED),
                change(8, MeetingEntryChangeKind.ASSIGNED), change(8, MeetingEntryChangeKind.CLOSED)), Map.of(8L, todo));

        assertThat(minutes.todos()).hasSize(1);
        assertThat(minutes.closed()).hasSize(1);
        assertThat(minutes.decisions()).isEmpty();
    }

    @Test
    void anEmptyMeetingHasEmptyMinutes() {
        final Minutes minutes = MinutesBuilder.build(meeting(), List.of(), Map.of());

        assertThat(minutes.decisions()).isEmpty();
        assertThat(minutes.todos()).isEmpty();
        assertThat(minutes.questions()).isEmpty();
        assertThat(minutes.closed()).isEmpty();
    }

    @Test
    void theJsonRoundTrips() {
        final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        final Minutes minutes = new Minutes(List.of(new Minutes.Row(1L, 1, LogEntryType.DECISION, "x", null, "Sam", LocalDate.of(2026, 1, 1),
                LogEntryStatus.OPEN)), List.of(), List.of(), List.of(), List.of("Budget"), List.of(11L), null, 11L);

        assertThat(mapper.readValue(mapper.writeValueAsString(minutes), Minutes.class)).isEqualTo(minutes);
        assertThat(Set.copyOf(minutes.attendeeIds())).containsExactly(11L);
    }
}
