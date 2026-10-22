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


package nu.fgv.revival.server.decisionlog;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision log, meetings and checklists work while Spexregister is down, and never call it (FR-007, SC-008).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class LogSpexregisterUnavailableIntegrationTest extends AbstractIntegrationTest {

    private long revival;

    @BeforeEach
    void revivalThenSpexregisterDown() {
        publishedGuide();
        revival = organisingRevival();
        spexregisterDown(false);
    }

    @Test
    void theLogWorks() {
        final int before = spexregisterCalls();
        final long id = Long.parseLong(execute(STEERING_GROUP,
                "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "type", "QUESTION", "text", "Var spelar vi?", "tags", List.of("lokal")))
        ).errors().verify().path("logEntryCreate.id").entity(String.class).get());
        final long version = jdbcClient.sql("SELECT version FROM log_entry WHERE id = ?").param(id).query(Long.class).single();

        execute(STEERING_GROUP, "mutation($input: LogEntryUpdateInput!) { logEntryUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", id, "version", version, "comment", "RunAn eller Kårhuset"))).errors().verify();
        execute(STEERING_GROUP, "mutation($input: LogEntryAnswerInput!) { logEntryAnswer(input: $input) { decision { id } } }",
                Map.of("input", Map.of("questionId", id, "questionVersion", version + 1, "text", "RunAn"))).errors().verify();
        execute(STEERING_GROUP, "query($id: ID!) { logEntries(revivalId: $id, filter: {open: true}) { number owner { name } } logTags(revivalId: $id) }",
                Map.of("id", revival)).errors().verify();
        assertThat(spexregisterCalls()).isEqualTo(before);
    }

    @Test
    void meetingsWork() {
        final int before = spexregisterCalls();
        final long meeting = Long.parseLong(execute(STEERING_GROUP, "mutation($input: MeetingCreateInput!) { meetingCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "date", "2026-11-01", "attendeeIds", List.of(personId(DIRECTEUR)))))
                .errors().verify().path("meetingCreate.id").entity(String.class).get());
        final long cancelled = Long.parseLong(execute(STEERING_GROUP, "mutation($input: MeetingCreateInput!) { meetingCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "date", "2026-11-08"))).errors().verify().path("meetingCreate.id").entity(String.class).get());

        execute(STEERING_GROUP, "mutation($input: MeetingUpdateInput!) { meetingUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", meeting, "version", 0, "date", "2026-11-01", "title", "Direktionsmöte 4", "agendaPoints", List.of("Budget"))))
                .errors().verify();
        execute(STEERING_GROUP, "mutation($id: ID!) { meetingCancel(id: $id, version: 0) { id } }", Map.of("id", cancelled)).errors().verify();
        execute(STEERING_GROUP, "mutation($id: ID!) { meetingStart(id: $id, version: 1) { id } }", Map.of("id", meeting)).errors().verify();
        execute(STEERING_GROUP, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "meetingId", meeting, "type", "DECISION", "text", "Vi spelar i RunAn"))).errors().verify();
        execute(STEERING_GROUP, "mutation($input: LogEntryUpdateInput!) { logEntryUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", jdbcClient.sql("SELECT id FROM log_entry WHERE revival_id = ? AND type = 'QUESTION'").param(revival)
                        .query(Long.class).single(), "version", 0, "meetingId", meeting, "status", "DONE"))).errors().verify();
        execute(STEERING_GROUP, "mutation($id: ID!) { meetingClose(id: $id, version: 2) { id } }", Map.of("id", meeting)).errors().verify();
        execute(STEERING_GROUP, "mutation($input: MinutesEmailSendInput!) { minutesEmailSend(input: $input) { id } }",
                Map.of("input", Map.of("meetingId", meeting, "language", "sv"))).errors().verify();
        execute(OBSERVER, "query($id: ID!, $meeting: ID!) { meetings(revivalId: $id) { id status } meeting(id: $meeting) { attendees { displayName } "
                + "minutes { decisions { number owner { name } } recorder { displayName } } emails { recipients { person { displayName } status } } } }",
                Map.of("id", revival, "meeting", meeting)).errors().verify();
        assertThat(spexregisterCalls()).isEqualTo(before);
    }

    @Test
    void checklistsWork() {
        final int before = spexregisterCalls();
        final long phaseA = jdbcClient.sql("SELECT id FROM checklist WHERE revival_id = ? AND phase = 'A'").param(revival).query(Long.class).single();
        final long required = jdbcClient.sql("SELECT id FROM checklist_item WHERE checklist_id = ?").param(phaseA).query(Long.class).single();
        final long event = Long.parseLong(execute(STEERING_GROUP, "mutation($input: EventChecklistCreateInput!) { eventChecklistCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "name", "Rephelg 2", "date", "2027-11-20"))).errors().verify()
                .path("eventChecklistCreate.id").entity(String.class).get());
        final long item = Long.parseLong(execute(STEERING_GROUP, "mutation($input: ChecklistItemCreateInput!) { checklistItemCreate(input: $input) { id } }",
                Map.of("input", Map.of("checklistId", event, "text", "Fika", "owner", Map.of("personId", personId(STEERING_GROUP)))))
                .errors().verify().path("checklistItemCreate.id").entity(String.class).get());

        execute(STEERING_GROUP, "mutation($input: ChecklistItemUpdateInput!) { checklistItemUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", item, "version", 0, "deadline", "2027-11-19"))).errors().verify();
        execute(DIRECTEUR, "mutation($input: ChecklistItemSetStateInput!) { checklistItemSetState(input: $input) { id } }",
                Map.of("input", Map.of("id", required, "version", jdbcClient.sql("SELECT version FROM checklist_item WHERE id = ?").param(required)
                        .query(Long.class).single(), "state", "NOT_APPLICABLE", "reason", "Redan godkänt"))).errors().verify();
        execute(STEERING_GROUP, "mutation($input: EventChecklistUpdateInput!) { eventChecklistUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", event, "version", 0, "date", "2027-11-27"))).errors().verify();
        execute(OBSERVER, "query($id: ID!, $checklist: ID!) { checklists(revivalId: $id) { id openCount } checklist(id: $checklist) { items { "
                + "text { sv } owner { person { displayName } } stateChangedBy { displayName } } } checklistItems(revivalId: $id, filter: {open: true}) { id } }", Map.of("id", revival, "checklist", phaseA)).errors().verify();
        execute(STEERING_GROUP, "mutation($id: ID!) { eventChecklistCancel(id: $id, version: 1) { id } }", Map.of("id", event)).errors().verify();
        assertThat(spexregisterCalls()).isEqualTo(before);
    }
}
