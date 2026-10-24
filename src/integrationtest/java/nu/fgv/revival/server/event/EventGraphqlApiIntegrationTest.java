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


package nu.fgv.revival.server.event;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Event types, a revival's events with their checklists, and calendar links over GraphQL and the calendar feed over
 * HTTP, against MySQL and Keycloak (M4).
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
class EventGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String EVENT_FIELDS = "id title startDate endDate startTime endTime location revivalPays daysFromPremiere cancelled version "
            + "type { id name { sv en } } checklist { id name date cancelled totalCount eventId }";
    private static final String CREATE = "mutation($input: EventCreateInput!) { eventCreate(input: $input) { " + EVENT_FIELDS + " } }";
    private static final String UPDATE = "mutation($input: EventUpdateInput!) { eventUpdate(input: $input) { " + EVENT_FIELDS + " } }";
    private static final String CANCEL = "mutation($id: ID!, $version: Int!) { eventCancel(id: $id, version: $version) { " + EVENT_FIELDS + " } }";
    private static final String RESTORE = "mutation($id: ID!, $version: Int!) { eventRestore(id: $id, version: $version) { " + EVENT_FIELDS + " } }";
    private static final String TYPE_FIELDS = "id name { sv en } description { sv } offsetMinDays offsetMaxDays days revivalPays "
            + "checklistTemplate { id } position retired version";
    private static final String TYPE_UPDATE = "mutation($id: ID!, $version: Int!, $input: EventTypeInput!) { eventTypeUpdate(id: $id, version: $version, input: $input) { "
            + TYPE_FIELDS + " } }";
    private static final String CALENDAR_CREATE = "mutation($id: ID!) { calendarFeedCreate(revivalId: $id) { url createdAt } }";

    private long revival;

    @BeforeEach
    void revivalWithAPremiere() {
        revival = organisingRevival();
        execute(SUPER_ADMIN, "mutation($input: RevivalUpdateInput!) { revivalUpdate(input: $input) { premiereDate } }",
                Map.of("input", Map.of("id", revival, "version", version(revival), "premiereDate", "2028-02-10")))
                .path("revivalUpdate.premiereDate").entity(String.class).isEqualTo("2028-02-10");
    }

    private long typeId(final String name) {
        return jdbcClient.sql("SELECT id FROM event_type WHERE name_sv = ?").param(name).query(Long.class).single();
    }

    private long typeVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM event_type WHERE id = ?").param(id).query(Long.class).single();
    }

    private long eventVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM event WHERE id = ?").param(id).query(Long.class).single();
    }

    private long rehearsalTemplate() {
        final long template = Long.parseLong(execute(SUPER_ADMIN, "mutation($input: ChecklistTemplateCreateInput!) { checklistTemplateCreate(input: $input) { id } }",
                Map.of("input", Map.of("kind", "EVENT", "name", Map.of("sv", "Rephelg")))).path("checklistTemplateCreate.id").entity(String.class).get());

        for (final String text : List.of("Boka replokal", "Hämta nycklar")) {
            execute(SUPER_ADMIN, "mutation($input: ChecklistTemplateItemCreateInput!) { checklistTemplateItemCreate(input: $input) { id } }",
                    Map.of("input", Map.of("templateId", template, "text", Map.of("sv", text), "deadline", Map.of("kind", "DAYS", "amount", -14))));
        }
        return template;
    }

    private static Map<String, Object> typeInput(final String sv, final String en, final Integer min, final Integer max, final int days,
                                                 final boolean pays, final Long templateId) {
        final Map<String, Object> input = new HashMap<>();

        input.put("name", Map.of("sv", sv, "en", en));
        input.put("offsetMinDays", min);
        input.put("offsetMaxDays", max);
        input.put("days", days);
        input.put("revivalPays", pays);
        input.put("checklistTemplateId", templateId);
        return input;
    }

    private long createEvent(final String user, final Map<String, Object> fields) {
        final Map<String, Object> input = new HashMap<>(fields);

        input.put("revivalId", revival);
        return Long.parseLong(execute(user, CREATE, Map.of("input", input)).path("eventCreate.id").entity(String.class).get());
    }

    @Test
    void theTimelineIsSeededInOrderAndProposedFromThePremiere() {
        execute(STEERING_GROUP, "{ eventTypes { name { sv en } offsetMinDays offsetMaxDays days revivalPays position } }", Map.of())
                .path("eventTypes[*].name.sv").entityList(String.class)
                .containsExactly("AW", "Kickoff", "Rephelg 1", "Rephelg 2", "Rephelg 3", "Föreställningshelg", "Kortafton")
                .path("eventTypes[2].name.en").entity(String.class).isEqualTo("Rehearsal weekend 1")
                .path("eventTypes[2].offsetMinDays").entity(Integer.class).isEqualTo(-70)
                .path("eventTypes[5].days").entity(Integer.class).isEqualTo(3);

        execute(STEERING_GROUP, "query($id: ID!) { revival(id: $id) { eventProposals { type { name { sv } } startDate endDate earliest latest } "
                + "permissions { canSubscribeCalendar } } }", Map.of("id", revival))
                .path("revival.eventProposals[*].type.name.sv").entityList(String.class)
                .containsExactly("Kickoff", "Rephelg 1", "Rephelg 2", "Rephelg 3", "Föreställningshelg", "Kortafton")
                .path("revival.eventProposals[1].startDate").entity(String.class).isEqualTo("2027-12-11")
                .path("revival.eventProposals[1].endDate").entity(String.class).isEqualTo("2027-12-12")
                .path("revival.eventProposals[1].earliest").entity(String.class).isEqualTo("2027-12-02")
                .path("revival.permissions.canSubscribeCalendar").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void anEventFromATypeWithATemplateGetsAChecklistThatFollowsIt() {
        final long template = rehearsalTemplate();
        final long rehearsal = typeId("Rephelg 1");

        execute(SUPER_ADMIN, TYPE_UPDATE, Map.of("id", rehearsal, "version", typeVersion(rehearsal),
                "input", typeInput("Rephelg 1", "Rehearsal weekend 1", -70, -56, 2, true, template)))
                .path("eventTypeUpdate.checklistTemplate.id").entity(Long.class).isEqualTo(template);

        final long event = createEvent(STEERING_GROUP, Map.of("typeId", rehearsal, "startDate", "2027-12-11", "location", "Kemi"));

        execute(STEERING_GROUP, "query($id: ID!) { event(id: $id) { " + EVENT_FIELDS + " } }", Map.of("id", event))
                .path("event.title").entity(String.class).isEqualTo("Rehearsal weekend 1")
                .path("event.endDate").entity(String.class).isEqualTo("2027-12-12")
                .path("event.revivalPays").entity(Boolean.class).isEqualTo(true)
                .path("event.daysFromPremiere").entity(Integer.class).isEqualTo(-61)
                .path("event.type.name.sv").entity(String.class).isEqualTo("Rephelg 1")
                .path("event.checklist.name").entity(String.class).isEqualTo("Rehearsal weekend 1")
                .path("event.checklist.date").entity(String.class).isEqualTo("2027-12-11")
                .path("event.checklist.totalCount").entity(Integer.class).isEqualTo(2)
                .path("event.checklist.eventId").entity(Long.class).isEqualTo(event);

        final long checklist = jdbcClient.sql("SELECT id FROM checklist WHERE event_id = ?").param(event).query(Long.class).single();

        execute(STEERING_GROUP, "query($id: ID!) { checklist(id: $id) { event { id title } items { deadline } } }", Map.of("id", checklist))
                .path("checklist.event.id").entity(Long.class).isEqualTo(event)
                .path("checklist.items[*].deadline").entityList(String.class).containsExactly("2027-11-27", "2027-11-27");

        execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", event, "version", eventVersion(event), "title", "Rephelg i Kemi",
                "startDate", "2027-12-18", "endDate", "2027-12-19", "startTime", "10:00")))
                .path("eventUpdate.checklist.name").entity(String.class).isEqualTo("Rephelg i Kemi")
                .path("eventUpdate.checklist.date").entity(String.class).isEqualTo("2027-12-18")
                .path("eventUpdate.startTime").entity(String.class).isEqualTo("10:00");
        assertThat(jdbcClient.sql("SELECT deadline FROM checklist_item WHERE checklist_id = ?").param(checklist).query(String.class).list())
                .containsOnly("2027-12-04");

        execute(STEERING_GROUP, CANCEL, Map.of("id", event, "version", eventVersion(event)))
                .path("eventCancel.cancelled").entity(Boolean.class).isEqualTo(true)
                .path("eventCancel.checklist.cancelled").entity(Boolean.class).isEqualTo(true);
        execute(STEERING_GROUP, "query($id: ID!) { events(revivalId: $id) { id } all: events(revivalId: $id, includeCancelled: true) { id } "
                + "revival(id: $id) { eventProposals { type { name { sv } } } } }", Map.of("id", revival))
                .path("events").entityList(Object.class).hasSize(0)
                .path("all[*].id").entityList(Long.class).containsExactly(event)
                .path("revival.eventProposals[*].type.name.sv").entityList(String.class).contains("Rephelg 1");
        execute(STEERING_GROUP, RESTORE, Map.of("id", event, "version", eventVersion(event)))
                .path("eventRestore.cancelled").entity(Boolean.class).isEqualTo(false)
                .path("eventRestore.checklist.cancelled").entity(Boolean.class).isEqualTo(false);

        assertThat(jdbcClient.sql("SELECT r.operation FROM event_audit a JOIN revinfo r ON r.id = a.rev WHERE a.id = ? ORDER BY a.rev")
                .param(event).query(String.class).list()).containsExactly("eventCreate", "eventUpdate", "eventCancel", "eventRestore");
    }

    @Test
    void validationConflictsAndReadersAreRefusedAsTheContractSays() {
        final long event = createEvent(STEERING_GROUP, Map.of("title", "Förrådsbesök", "startDate", "2028-01-10"));

        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "startDate", "2028-01-10"))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "title", "AW", "startDate", "2028-01-10",
                "endDate", "2028-01-09"))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", event, "version", eventVersion(event) + 1, "title", "Ny"))),
                "CONFLICT");
        expectError(execute(OBSERVER, CREATE, Map.of("input", Map.of("revivalId", revival, "title", "AW", "startDate", "2028-01-10"))),
                "FORBIDDEN");
        expectError(execute(SUPER_ADMIN, CANCEL, Map.of("id", event, "version", eventVersion(event))), "FORBIDDEN");
        execute(OBSERVER, "query($id: ID!) { events(revivalId: $id) { title } }", Map.of("id", revival))
                .path("events[*].title").entityList(String.class).containsExactly("Förrådsbesök");
        expectError(execute(PLAIN_USER, "query($id: ID!) { event(id: $id) { id } }", Map.of("id", event)), "NOT_FOUND");

        final long retired = typeId("Kortafton");

        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { eventTypeRetire(id: $id, version: $version) { retired } }",
                Map.of("id", retired, "version", typeVersion(retired))).path("eventTypeRetire.retired").entity(Boolean.class).isEqualTo(true);
        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "typeId", retired, "startDate", "2028-03-01"))),
                "BAD_REQUEST");
    }

    @Test
    void onlyTheSuperAdminKeepsTheTypes() {
        final String create = "mutation($input: EventTypeInput!) { eventTypeCreate(input: $input) { " + TYPE_FIELDS + " } }";
        final Map<String, Object> input = typeInput("Teknikgenomgång", "Technical walkthrough", -10, -7, 1, false, null);

        expectError(execute(DIRECTEUR, create, Map.of("input", input)), "FORBIDDEN");

        final long id = Long.parseLong(execute(SUPER_ADMIN, create, Map.of("input", input))
                .path("eventTypeCreate.position").entity(Integer.class).isEqualTo(7)
                .path("eventTypeCreate.id").entity(String.class).get());

        expectError(execute(SUPER_ADMIN, create, Map.of("input", typeInput("teknikgenomgång", "", null, null, 1, false, null))), "BAD_REQUEST");
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { eventTypeMove(id: $id, version: $version, position: 0) { id position } }",
                Map.of("id", id, "version", typeVersion(id)))
                .path("eventTypeMove[0].id").entity(Long.class).isEqualTo(id)
                .path("eventTypeMove[*].position").entityList(Integer.class).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { eventTypeRetire(id: $id, version: $version) { retired } }",
                Map.of("id", id, "version", typeVersion(id)));
        execute(STEERING_GROUP, "{ eventTypes { id } all: eventTypes(includeRetired: true) { id } }", Map.of())
                .path("eventTypes").entityList(Object.class).hasSize(7)
                .path("all").entityList(Object.class).hasSize(8);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM event_type_audit WHERE id = ?").param(id).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void upcomingEventsAreOnTheRevivalPage() {
        createEvent(STEERING_GROUP, Map.of("title", "Passerat", "startDate", "2026-01-10"));
        for (final String date : List.of("2027-12-11", "2027-11-12", "2028-02-10", "2028-01-01")) {
            createEvent(STEERING_GROUP, Map.of("title", date, "startDate", date));
        }

        execute(DIRECTEUR, "query($id: ID!) { revival(id: $id) { workSummary { upcomingEvents { title } } } }", Map.of("id", revival))
                .path("revival.workSummary.upcomingEvents[*].title").entityList(String.class).containsExactly("2027-11-12", "2027-12-11", "2028-01-01");
    }

    @Test
    void aCalendarLinkServesTheFeedUntilItIsReplacedOrTurnedOff() {
        createEvent(STEERING_GROUP, Map.of("title", "Kickoff; lunch, fest", "startDate", "2027-11-12", "startTime", "12:00", "endTime", "15:00"));
        execute(STEERING_GROUP, "mutation($input: MeetingCreateInput!) { meetingCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "date", "2027-11-01")));

        final String url = execute(STEERING_GROUP, CALENDAR_CREATE, Map.of("id", revival)).path("calendarFeedCreate.url").entity(String.class).get();
        final String path = url.substring(url.indexOf("/api/calendar/"));

        assertThat(url).matches("http://localhost:3001/api/calendar/[A-Za-z0-9_-]{43}\\.ics");
        assertThat(jdbcClient.sql("SELECT token_hash FROM calendar_feed").query(String.class).single()).hasSize(64).isNotEqualTo(path);

        final String ics = new String(Objects.requireNonNull(restTestClient().get().uri(path).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(new MediaType("text", "calendar"))
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=900, private")
                .expectBody(byte[].class).returnResult().getResponseBody()), StandardCharsets.UTF_8);

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n")
                .contains("SUMMARY:Kickoff\\; lunch\\, fest\r\n")
                .contains("DTSTART:20271112T110000Z\r\nDTEND:20271112T140000Z\r\n")
                .contains("SUMMARY:Direktionsmöte\r\n")
                .doesNotContain(DOMAIN);
        assertThat(new String(Objects.requireNonNull(restTestClient().get().uri(path + "?lang=en").exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody()), StandardCharsets.UTF_8)).contains("SUMMARY:Steering group meeting\r\n");

        execute(STEERING_GROUP, "query($id: ID!) { revival(id: $id) { calendarFeed { createdAt } } }", Map.of("id", revival))
                .path("revival.calendarFeed.createdAt").hasValue();
        execute(OBSERVER, "query($id: ID!) { revival(id: $id) { calendarFeed { createdAt } permissions { canSubscribeCalendar } } }", Map.of("id", revival))
                .path("revival.calendarFeed").valueIsNull()
                .path("revival.permissions.canSubscribeCalendar").entity(Boolean.class).isEqualTo(false);
        expectError(execute(OBSERVER, CALENDAR_CREATE, Map.of("id", revival)), "FORBIDDEN");

        final String replaced = execute(STEERING_GROUP, CALENDAR_CREATE, Map.of("id", revival)).path("calendarFeedCreate.url").entity(String.class).get();

        restTestClient().get().uri(path).exchange().expectStatus().isNotFound();
        restTestClient().get().uri(replaced.substring(replaced.indexOf("/api/calendar/"))).exchange().expectStatus().isOk();

        // Losing the role turns the link off too
        setStatus(revival, "SETTLING");
        restTestClient().get().uri(replaced.substring(replaced.indexOf("/api/calendar/"))).exchange().expectStatus().isNotFound();
        setStatus(revival, "OPEN");

        execute(STEERING_GROUP, "mutation($id: ID!) { calendarFeedRevoke(revivalId: $id) }", Map.of("id", revival));
        restTestClient().get().uri(replaced.substring(replaced.indexOf("/api/calendar/"))).exchange().expectStatus().isNotFound();
        restTestClient().get().uri("/api/calendar/" + "A".repeat(43) + ".ics").exchange().expectStatus().isNotFound();
    }

    @Test
    void deletingTheRevivalTakesItsEventsAndLinksAlong() {
        final long template = rehearsalTemplate();
        final long rehearsal = typeId("Rephelg 1");

        execute(SUPER_ADMIN, TYPE_UPDATE, Map.of("id", rehearsal, "version", typeVersion(rehearsal),
                "input", typeInput("Rephelg 1", "Rehearsal weekend 1", -70, -56, 2, true, template)));
        createEvent(STEERING_GROUP, Map.of("typeId", rehearsal, "startDate", "2027-12-11"));
        execute(STEERING_GROUP, CALENDAR_CREATE, Map.of("id", revival));

        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { revivalDelete(id: $id, version: $version) }",
                Map.of("id", revival, "version", version(revival))).path("revivalDelete").hasValue();

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM event").query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM calendar_feed").query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM event_audit WHERE revtype = 2").query(Integer.class).single()).isEqualTo(1);
    }
}
