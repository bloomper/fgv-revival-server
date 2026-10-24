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

package nu.fgv.revival.server.staffing;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Availability and staffing end to end (M6B-FR-1–7, M6B-FR-14): the directeur plans slots, a participant answers for
 * the event's days and signs up, a helper is assigned and sees only their shifts, helpers' compensation reaches the
 * budget, and shifts reach the calendar feed. Participants are pending people, so Spexregister is not needed.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
class StaffingGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ADD = "mutation($input: ParticipantAddInput!) { participantAdd(input: $input) { id version } }";
    private static final String INVITE = "mutation($input: ParticipantsInviteInput!) { participantsInvite(input: $input) { invited { id } } }";
    private static final String SLOT_FIELDS = "id date startTime endTime task needed open version event { id title } assigned { participant { id displayName kind } selfSignedUp }";
    private static final String CREATE = "mutation($input: ShiftSlotInput!) { shiftSlotCreate(input: $input) { " + SLOT_FIELDS + " } }";
    private static final String SLOTS = "query($id: ID!) { shiftSlots(revivalId: $id) { " + SLOT_FIELDS + " } }";
    private static final String ASSIGN = "mutation($slot: ID!, $p: ID!) { shiftAssign(slotId: $slot, participantId: $p) { " + SLOT_FIELDS + " } }";
    private static final String SIGN_UP = "mutation($slot: ID!) { shiftSignUp(slotId: $slot) { " + SLOT_FIELDS + " } }";
    private static final String WITHDRAW = "mutation($slot: ID!) { shiftWithdraw(slotId: $slot) { " + SLOT_FIELDS + " } }";
    private static final String AVAILABILITY_SET = """
            mutation($input: AvailabilityInput!) { availabilitySet(input: $input) { availability { eventId date answer comment } } }""";
    private static final String MINE = """
            query($id: ID!) {
                myParticipation(revivalId: $id) {
                    events { id title } availability { eventId date answer } shifts { id task } openShifts { id task open }
                    revival { permissions { canReadStaffing canWriteStaffing canReadMailings } }
                }
            }""";
    private static final String RESPOND = "mutation($input: ParticipationRespondInput!) { participationRespond(input: $input) { participant { response } } }";

    private long revival;
    private long weekend;
    private long rehearsal;
    private long participant;
    private long helper;

    @BeforeEach
    void openRevivalWithAParticipantAndAHelper() {
        revival = organisingRevival();
        setStatus(revival, "OPEN");
        weekend = event("Föreställningshelg", "2028-05-05", "2028-05-06", "Kårhuset");
        rehearsal = event("Rephelg", "2028-03-10", "2028-03-12", null);
        participant = pending("PARTICIPANT", "Pia", "Deltagare", PARTICIPANT);
        helper = pending("HELPER", "Hugo", "Springare", HELPER);
        execute(STEERING_GROUP, INVITE, Map.of("input", Map.of("revivalId", revival, "participantIds", List.of(participant, helper), "language", "sv")))
                .path("participantsInvite.invited").entityList(Object.class).hasSize(2);
        login(PARTICIPANT);
        login(HELPER);
    }

    private long event(final String title, final String start, final String end, final String location) {
        final Map<String, Object> input = new HashMap<>(Map.of("revivalId", revival, "title", title, "startDate", start, "endDate", end));

        if (location != null) {
            input.put("location", location);
        }
        return Long.parseLong(execute(DIRECTEUR, "mutation($input: EventCreateInput!) { eventCreate(input: $input) { id } }", Map.of("input", input))
                .path("eventCreate.id").entity(String.class).get());
    }

    private long pending(final String kind, final String firstName, final String lastName, final String email) {
        return Long.parseLong(execute(STEERING_GROUP, ADD, Map.of("input", Map.of("revivalId", revival, "kind", kind, "firstName", firstName,
                "lastName", lastName, "email", email))).path("participantAdd.id").entity(String.class).get());
    }

    private Map<String, Object> slot(final long event, final String date, final String start, final String end, final String task, final int needed) {
        return Map.of("eventId", event, "date", date, "startTime", start, "endTime", end, "task", task, "needed", needed);
    }

    private long createSlot(final Map<String, Object> input) {
        return Long.parseLong(execute(DIRECTEUR, CREATE, Map.of("input", input)).path("shiftSlotCreate.id").entity(String.class).get());
    }

    private void answerYes(final String user) {
        execute(user, RESPOND, Map.of("input", Map.of("revivalId", revival, "response", "YES", "termsAccepted", true, "registerConsent", false)))
                .path("participationRespond.participant.response").entity(String.class).isEqualTo("YES");
    }

    private long slotVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM shift_slot WHERE id = ?").param(id).query(Long.class).single();
    }

    @Test
    void writersPlanSlotsAndTheyAreValidated() {
        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", slot(weekend, "2028-05-05", "17:00", "19:00", "Biljettkontroll", 2))), "FORBIDDEN");
        expectError(execute(DIRECTEUR, CREATE, Map.of("input", slot(weekend, "2028-05-07", "17:00", "19:00", "Biljettkontroll", 2))), "BAD_REQUEST");
        expectError(execute(DIRECTEUR, CREATE, Map.of("input", slot(weekend, "2028-05-05", "19:00", "17:00", "Biljettkontroll", 2))), "BAD_REQUEST");
        expectError(execute(DIRECTEUR, CREATE, Map.of("input", slot(weekend, "2028-05-05", "17:00", "25:00", "Biljettkontroll", 2))), "BAD_REQUEST");
        expectError(execute(DIRECTEUR, CREATE, Map.of("input", slot(weekend, "2028-05-05", "17:00", "19:00", " ", 2))), "BAD_REQUEST");
        expectError(execute(DIRECTEUR, CREATE, Map.of("input", slot(weekend, "2028-05-05", "17:00", "19:00", "Biljettkontroll", 0))), "BAD_REQUEST");
        expectError(execute(DIRECTEUR, CREATE, Map.of("input", slot(weekend, "2028-05-05", "17:00", "19:00", "Biljettkontroll", 101))), "BAD_REQUEST");

        final long id = createSlot(slot(weekend, "2028-05-05", "17:00", "19:00", "Biljettkontroll", 2));

        // A steering group member with STAFFING writes too
        final Map<String, Object> member = jdbcClient.sql("SELECT id, version FROM revival_member WHERE revival_id = ? AND role = 'STEERING_GROUP'")
                .param(revival).query().singleRow();

        execute(DIRECTEUR, "mutation($input: RevivalMemberCapabilitiesInput!) { revivalMemberCapabilitiesSet(input: $input) { id } }",
                Map.of("input", Map.of("id", member.get("id"), "version", member.get("version"), "capabilities", List.of("STAFFING"))));
        execute(STEERING_GROUP, "mutation($id: ID!, $v: Int!, $input: ShiftSlotInput!) { shiftSlotUpdate(id: $id, version: $v, input: $input) { task needed } }",
                Map.of("id", id, "v", slotVersion(id), "input", slot(weekend, "2028-05-06", "16:00", "18:00", "Sälja program", 3)))
                .path("shiftSlotUpdate.task").entity(String.class).isEqualTo("Sälja program")
                .path("shiftSlotUpdate.needed").entity(Integer.class).isEqualTo(3);

        // Readers: super admin and the steering group; never observers or participants
        execute(SUPER_ADMIN, SLOTS, Map.of("id", revival)).path("shiftSlots[0].date").entity(String.class).isEqualTo("2028-05-06");
        expectError(execute(OBSERVER, SLOTS, Map.of("id", revival)), "FORBIDDEN");
        expectError(execute(PARTICIPANT, SLOTS, Map.of("id", revival)), "FORBIDDEN");
        expectError(execute(HELPER, SLOTS, Map.of("id", revival)), "FORBIDDEN");

        // A slot is removed only while nobody holds it
        execute(DIRECTEUR, ASSIGN, Map.of("slot", id, "p", helper)).path("shiftAssign.assigned").entityList(Object.class).hasSize(1);
        expectError(execute(DIRECTEUR, "mutation($id: ID!, $v: Int!) { shiftSlotRemove(id: $id, version: $v) }", Map.of("id", id, "v", slotVersion(id))),
                "BAD_REQUEST");
        execute(DIRECTEUR, "mutation($slot: ID!, $p: ID!) { shiftUnassign(slotId: $slot, participantId: $p) { open } }", Map.of("slot", id, "p", helper))
                .path("shiftUnassign.open").entity(Integer.class).isEqualTo(3);
        execute(DIRECTEUR, "mutation($id: ID!, $v: Int!) { shiftSlotRemove(id: $id, version: $v) }", Map.of("id", id, "v", slotVersion(id)));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM shift_slot").query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM shift_slot_audit WHERE revtype = 2").query(Integer.class).single()).isEqualTo(1);

        // The permissions say who writes
        execute(DIRECTEUR, "query($id: ID!) { revival(id: $id) { permissions { canReadStaffing canWriteStaffing } } }", Map.of("id", revival))
                .path("revival.permissions.canWriteStaffing").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void aParticipantAnswersForTheDaysAndSignsUp() {
        final long tickets = createSlot(slot(weekend, "2028-05-05", "17:00", "19:00", "Biljettkontroll", 1));
        final long drinks = createSlot(slot(weekend, "2028-05-05", "18:00", "20:00", "Sälja dryckesbiljetter", 2));

        // Before answering yes or maybe there is nothing to sign up for
        execute(PARTICIPANT, MINE, Map.of("id", revival)).path("myParticipation.openShifts").entityList(Object.class).hasSize(0)
                .path("myParticipation.events[*].title").entityList(String.class).containsExactly("Rephelg", "Föreställningshelg")
                .path("myParticipation.revival.permissions.canReadStaffing").entity(Boolean.class).isEqualTo(false);
        expectError(execute(PARTICIPANT, SIGN_UP, Map.of("slot", tickets)), "BAD_REQUEST");
        answerYes(PARTICIPANT);

        // Availability: days of the event only, with a comment; clearing deletes the answer
        expectError(execute(PARTICIPANT, AVAILABILITY_SET, Map.of("input", Map.of("revivalId", revival, "eventId", weekend, "date", "2028-05-07",
                "answer", "YES"))), "BAD_REQUEST");
        expectError(execute(PARTICIPANT, AVAILABILITY_SET, Map.of("input", Map.of("revivalId", revival, "eventId", weekend, "date", "2028-05-05",
                "answer", "YES", "comment", "x".repeat(256)))), "BAD_REQUEST");
        execute(PARTICIPANT, AVAILABILITY_SET, Map.of("input", Map.of("revivalId", revival, "eventId", weekend, "date", "2028-05-05", "answer", "YES",
                "comment", "Dåligt knä, men kan sitta i kassan")))
                .path("availabilitySet.availability[0].answer").entity(String.class).isEqualTo("YES");
        execute(PARTICIPANT, AVAILABILITY_SET, Map.of("input", Map.of("revivalId", revival, "eventId", weekend, "date", "2028-05-06", "answer", "NO")))
                .path("availabilitySet.availability").entityList(Object.class).hasSize(2);
        final Map<String, Object> clear = new HashMap<>(Map.of("revivalId", revival, "eventId", weekend, "date", "2028-05-06"));

        clear.put("answer", null);
        execute(PARTICIPANT, AVAILABILITY_SET, Map.of("input", clear)).path("availabilitySet.availability").entityList(Object.class).hasSize(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM availability_audit WHERE revtype = 2").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM availability_audit a JOIN availability b ON a.id = b.id").query(Integer.class).single())
                .isPositive();
        execute(STEERING_GROUP, "query($id: ID!) { availability(revivalId: $id) { participant { displayName } days { date answer comment } } }",
                        Map.of("id", revival))
                .path("availability[0].participant.displayName").entity(String.class).isEqualTo("Pia Deltagare")
                .path("availability[0].days[0].comment").entity(String.class).isEqualTo("Dåligt knä, men kan sitta i kassan");
        expectError(execute(OBSERVER, "query($id: ID!) { availability(revivalId: $id) { days { date } } }", Map.of("id", revival)), "FORBIDDEN");

        // Signing up: open slots, then the slot is full, and overlapping slots are refused
        execute(PARTICIPANT, MINE, Map.of("id", revival)).path("myParticipation.openShifts[*].task").entityList(String.class)
                .containsExactly("Biljettkontroll", "Sälja dryckesbiljetter");
        execute(PARTICIPANT, SIGN_UP, Map.of("slot", tickets))
                .path("shiftSignUp.open").entity(Integer.class).isEqualTo(0)
                .path("shiftSignUp.assigned[0].selfSignedUp").entity(Boolean.class).isEqualTo(true);
        expectError(execute(PARTICIPANT, SIGN_UP, Map.of("slot", drinks)), "BAD_REQUEST");
        execute(PARTICIPANT, MINE, Map.of("id", revival))
                .path("myParticipation.shifts[*].task").entityList(String.class).containsExactly("Biljettkontroll")
                .path("myParticipation.openShifts[*].task").entityList(String.class).containsExactly("Sälja dryckesbiljetter");

        // The directeur may fill a slot beyond what it needs; sign-up may not
        execute(DIRECTEUR, ASSIGN, Map.of("slot", tickets, "p", helper)).path("shiftAssign.assigned").entityList(Object.class).hasSize(2)
                .path("shiftAssign.open").entity(Integer.class).isEqualTo(0);
        expectError(execute(DIRECTEUR, ASSIGN, Map.of("slot", drinks, "p", participant)), "BAD_REQUEST");

        // Withdrawing: only what they signed up for themselves
        execute(PARTICIPANT, WITHDRAW, Map.of("slot", tickets)).path("shiftWithdraw.assigned").entityList(Object.class).hasSize(1);
        execute(DIRECTEUR, ASSIGN, Map.of("slot", tickets, "p", participant));
        expectError(execute(PARTICIPANT, WITHDRAW, Map.of("slot", tickets)), "BAD_REQUEST");
        expectError(execute(PARTICIPANT, WITHDRAW, Map.of("slot", drinks)), "BAD_REQUEST");
    }

    @Test
    void aHelperSeesOnlyTheirShiftsAndIsPaidForThem() {
        final long tickets = createSlot(slot(weekend, "2028-05-05", "17:00", "19:00", "Biljettkontroll", 2));
        final long punch = createSlot(slot(weekend, "2028-05-06", "21:00", "23:00", "Bålvakt", 1));

        execute(HELPER, MINE, Map.of("id", revival)).path("myParticipation.events").entityList(Object.class).hasSize(0)
                .path("myParticipation.openShifts").entityList(Object.class).hasSize(0);
        expectError(execute(HELPER, AVAILABILITY_SET, Map.of("input", Map.of("revivalId", revival, "eventId", weekend, "date", "2028-05-05",
                "answer", "YES"))), "BAD_REQUEST");
        execute(DIRECTEUR, ASSIGN, Map.of("slot", tickets, "p", helper));
        execute(DIRECTEUR, ASSIGN, Map.of("slot", punch, "p", helper));
        expectError(execute(HELPER, SIGN_UP, Map.of("slot", tickets)), "FORBIDDEN");
        execute(HELPER, MINE, Map.of("id", revival))
                .path("myParticipation.events[*].title").entityList(String.class).containsExactly("Föreställningshelg")
                .path("myParticipation.shifts[*].task").entityList(String.class).containsExactly("Biljettkontroll", "Bålvakt");
        execute(HELPER, AVAILABILITY_SET, Map.of("input", Map.of("revivalId", revival, "eventId", weekend, "date", "2028-05-05", "answer", "MAYBE")))
                .path("availabilitySet.availability[0].answer").entity(String.class).isEqualTo("MAYBE");
        expectError(execute(HELPER, AVAILABILITY_SET, Map.of("input", Map.of("revivalId", revival, "eventId", rehearsal, "date", "2028-03-10",
                "answer", "YES"))), "BAD_REQUEST");

        // M6B-FR-5: 2 shifts × 350 kr is a budgeted cost of the helper category, never a stored line
        final long version = jdbcClient.sql("SELECT version FROM participant WHERE id = ?").param(helper).query(Long.class).single();

        execute(STEERING_GROUP, "mutation($input: ParticipantUpdateInput!) { participantUpdate(input: $input) { compensationPerShift } }",
                Map.of("input", Map.of("id", helper, "version", version, "compensationPerShift", 350)));
        execute(DIRECTEUR, "query($id: ID!) { revival(id: $id) { budget { totals { budgetedCost } categories { category { id } budgeted } } } }",
                        Map.of("id", revival))
                .path("revival.budget.totals.budgetedCost").entity(BigDecimal.class).satisfies(cost -> assertThat(cost).isEqualByComparingTo("700"))
                .path("revival.budget.categories[?(@.category.id == 'HELPERS')].budgeted").entityList(BigDecimal.class)
                .satisfies(budgeted -> assertThat(budgeted).singleElement().satisfies(amount -> assertThat(amount).isEqualByComparingTo("700")));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM budget_line").query(Integer.class).single()).isZero();
        execute(DIRECTEUR, "query($id: ID!) { budgetScenarioCalculate(revivalId: $id, input: {}) { cost } }", Map.of("id", revival))
                .path("budgetScenarioCalculate.cost").entity(BigDecimal.class).satisfies(cost -> assertThat(cost).isEqualByComparingTo("700"));

        // M6B-FR-6: the helper's calendar holds their shifts at the event's place, and the shows; no events
        final String url = execute(HELPER, "mutation($id: ID!) { calendarFeedCreate(revivalId: $id) { url } }", Map.of("id", revival))
                .path("calendarFeedCreate.url").entity(String.class).get();
        final String ics = new String(Objects.requireNonNull(restTestClient().get().uri(url.substring(url.indexOf("/api/calendar/"))).exchange()
                .expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody()), StandardCharsets.UTF_8);

        assertThat(ics).contains("SUMMARY:Biljettkontroll").contains("SUMMARY:Bålvakt").contains("LOCATION:Kårhuset")
                .contains("DTSTART:20280505T150000Z").doesNotContain("SUMMARY:Föreställningshelg").doesNotContain("SUMMARY:Rephelg");
    }

    @Test
    void mcpListsTheStaffingByNameAndTheRevivalCanStillBeDeleted() {
        final long tickets = createSlot(slot(weekend, "2028-05-05", "17:00", "19:00", "Biljettkontroll", 2));

        answerYes(PARTICIPANT);
        execute(PARTICIPANT, SIGN_UP, Map.of("slot", tickets));
        execute(PARTICIPANT, AVAILABILITY_SET, Map.of("input", Map.of("revivalId", revival, "eventId", weekend, "date", "2028-05-05", "answer", "YES")));

        final JsonNode staffing = mcpRpc(mcpToken(STEERING_GROUP), "tools/call", Map.of("name", "get_staffing", "arguments", Map.of("revivalId", revival)));

        assertThat(staffing.path("result").path("isError").asBoolean(false)).isFalse();
        assertThat(staffing.toString()).contains("Biljettkontroll").contains("Pia Deltagare").doesNotContain("@");

        final JsonNode refused = mcpRpc(mcpToken(PARTICIPANT), "tools/call", Map.of("name", "get_staffing", "arguments", Map.of("revivalId", revival)));

        assertThat(refused.has("error") || refused.path("result").path("isError").asBoolean(false)).isTrue();

        setStatus(revival, "CLOSING");
        execute(SUPER_ADMIN, "mutation($id: ID!, $v: Int!) { revivalDelete(id: $id, version: $v) }", Map.of("id", revival, "v", version(revival)))
                .path("revivalDelete").entity(String.class).isEqualTo(String.valueOf(revival));
        for (final String table : List.of("shift_slot", "shift_assignment", "availability", "participant", "event")) {
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single()).as(table).isZero();
        }
    }
}
