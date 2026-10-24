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


package nu.fgv.revival.server.calendar;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.meeting.Meeting;
import nu.fgv.revival.server.meeting.MeetingRepository;
import nu.fgv.revival.server.meeting.MeetingStatus;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalShow;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static nu.fgv.revival.server.TestCallers.PERSON_ID;
import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Secret calendar links and their feed (M4-FR-5, M4-FR-6).
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@ExtendWith(MockitoExtension.class)
class CalendarFeedServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 12, 1);
    private static final String WEB_URL = "https://revival.example.org/";

    @Mock
    private CalendarFeedRepository repository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private PersonRepository personRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private RevivalTitles titles;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private MeetingRepository meetingRepository;

    private CalendarFeedService service;
    private MeetingCalendarSource meetings;
    private Revival revival;
    private Person person;

    @BeforeEach
    void setUp() {
        final Clock clock = TestClocks.at(TODAY);
        final AccessPolicy accessPolicy = new AccessPolicy();
        final StaticMessageSource messages = new StaticMessageSource();

        messages.addMessage("calendar.show", Locale.of("sv"), "{0}: Föreställning");
        messages.addMessage("calendar.show", Locale.ENGLISH, "{0}: Show");
        messages.addMessage("calendar.meeting", Locale.of("sv"), "Direktionsmöte");
        messages.addMessage("calendar.meeting", Locale.ENGLISH, "Steering group meeting");
        meetings = new MeetingCalendarSource(meetingRepository, accessPolicy, messages);
        service = new CalendarFeedService(repository, revivalRepository, personRepository, callerService, accessPolicy, titles,
                List.of(new EventCalendarSource(eventRepository, accessPolicy, clock), new ShowCalendarSource(titles, messages, clock), meetings), clock, WEB_URL);
        revival = TestCallers.revival(RevivalStatus.ORGANISING);
        revival.setCreatedAt(Instant.parse("2027-01-01T00:00:00Z"));
        person = TestCallers.steeringGroup().person();
        lenient().when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(person));
        lenient().when(callerService.forPerson(person)).thenReturn(TestCallers.steeringGroup());
        lenient().when(titles.ofCached(any(), any())).thenReturn("Ali Baba 2028");
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CalendarFeed feed(final String token) {
        final CalendarFeed feed = new CalendarFeed();

        feed.setId(1L);
        feed.setRevivalId(REVIVAL_ID);
        feed.setPersonId(PERSON_ID);
        feed.setTokenHash(CalendarFeedService.hash(token));
        feed.setCreatedAt(Instant.parse("2027-11-01T00:00:00Z"));
        lenient().when(repository.findByTokenHashAndRevokedAtIsNull(CalendarFeedService.hash(token))).thenReturn(Optional.of(feed));
        return feed;
    }

    private static String token() {
        return "A".repeat(43);
    }

    private static Event event(final long id, final String title, final LocalDate date, final LocalTime time, final boolean cancelled) {
        final Event event = new Event();

        event.setId(id);
        event.setRevivalId(REVIVAL_ID);
        event.setTitle(title);
        event.setStartDate(date);
        event.setEndDate(date.plusDays(1));
        event.setStartTime(time);
        event.setCreatedAt(Instant.parse("2027-10-01T00:00:00Z"));
        event.setCancelledAt(cancelled ? Instant.EPOCH : null);
        return event;
    }

    private static Meeting meeting(final long id, final MeetingStatus status) {
        final Meeting meeting = new Meeting();

        meeting.setId(id);
        meeting.setRevivalId(REVIVAL_ID);
        meeting.setDate(TODAY.plusDays(id));
        meeting.setStatus(status);
        meeting.setCreatedAt(Instant.parse("2027-10-01T00:00:00Z"));
        return meeting;
    }

    @Test
    void aNewLinkStoresOnlyTheHashAndRevokesThePreviousOne() {
        final CalendarFeed previous = new CalendarFeed();

        when(repository.findByRevivalIdAndPersonIdAndRevokedAtIsNull(REVIVAL_ID, PERSON_ID)).thenReturn(Optional.of(previous));

        final CalendarFeedLinkDto link = service.create(REVIVAL_ID);
        final ArgumentCaptor<CalendarFeed> saved = ArgumentCaptor.forClass(CalendarFeed.class);

        assertThat(link.url()).matches("https://revival\\.example\\.org/api/calendar/[A-Za-z0-9_-]{43}\\.ics");
        assertThat(previous.getRevokedAt()).isNotNull();
        verify(repository, times(2)).saveAndFlush(saved.capture());

        final String token = link.url().substring(link.url().lastIndexOf('/') + 1, link.url().length() - ".ics".length());
        final CalendarFeed created = saved.getAllValues().getLast();

        assertThat(created.getTokenHash()).isEqualTo(CalendarFeedService.hash(token)).hasSize(64).doesNotContain(token);
        assertThat(created.getPersonId()).isEqualTo(PERSON_ID);
        assertThat(service.create(REVIVAL_ID).url()).isNotEqualTo(link.url());
    }

    @Test
    void onlyThoseWithARoleGetALink() {
        when(callerService.current()).thenReturn(TestCallers.observer());
        assertThatThrownBy(() -> service.create(REVIVAL_ID)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.current(REVIVAL_ID)).isNull();

        when(callerService.current()).thenReturn(TestCallers.nobody());
        assertThatThrownBy(() -> service.create(REVIVAL_ID)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theCurrentLinkHasNoToken() {
        final CalendarFeed active = feed(token());

        when(repository.findByRevivalIdAndPersonIdAndRevokedAtIsNull(REVIVAL_ID, PERSON_ID)).thenReturn(Optional.of(active));

        assertThat(service.current(REVIVAL_ID)).isEqualTo(new CalendarFeedDto(active.getCreatedAt()));

        service.revoke(REVIVAL_ID);
        assertThat(active.isRevoked()).isTrue();
    }

    @Test
    void anUnknownRevokedOrMalformedTokenIsNotFound() {
        assertThat(service.render(token(), Locale.of("sv"))).isEmpty();
        assertThat(service.render("not-a-token", Locale.of("sv"))).isEmpty();
        assertThat(service.render("../" + token(), Locale.of("sv"))).isEmpty();
        verify(repository, times(1)).findByTokenHashAndRevokedAtIsNull(anyString());
    }

    @Test
    void aPersonWhoLostTheirRoleOrTheRevivalGetsNothing() {
        feed(token());

        when(callerService.forPerson(person)).thenReturn(new Caller(person, Set.of(), Map.of()));
        assertThat(service.render(token(), Locale.of("sv"))).isEmpty();

        when(callerService.forPerson(person)).thenReturn(TestCallers.steeringGroup());
        revival.setStatus(RevivalStatus.SETTLING);
        assertThat(service.render(token(), Locale.of("sv"))).isEmpty();
    }

    @Test
    void theSteeringGroupGetsEventsShowsAndMeetings() {
        final RevivalShow show = new RevivalShow();

        show.setId(3L);
        show.setRevival(revival);
        show.setDate(LocalDate.of(2028, 2, 10));
        show.setTime(LocalTime.of(19, 0));
        show.setVenue("Kårhuset");
        revival.getShows().add(show);
        feed(token());
        when(eventRepository.findByRevivalId(REVIVAL_ID)).thenReturn(List.of(
                event(12L, "Rephelg 1", LocalDate.of(2027, 12, 11), null, false),
                event(13L, "Kickoff", LocalDate.of(2027, 11, 12), LocalTime.of(12, 0), false),
                event(14L, "Inställd", LocalDate.of(2027, 12, 20), null, true)));
        when(meetingRepository.findByRevivalIdOrderByDateDescIdDesc(REVIVAL_ID)).thenReturn(List.of(
                meeting(2L, MeetingStatus.PLANNED), meeting(1L, MeetingStatus.CANCELLED)));

        final String ics = service.render(token(), Locale.of("sv")).orElseThrow();

        assertThat(ics).contains("X-WR-CALNAME:Ali Baba 2028\r\n")
                .contains("UID:event-12@nyuppsattning.fgv.nu\r\nDTSTAMP:20271001T000000Z\r\nDTSTART;VALUE=DATE:20271211\r\nDTEND;VALUE=DATE:20271213\r\n")
                // 12:00 in Stockholm is 11:00 UTC, and an event without an end time lasts two hours
                .contains("UID:event-13@nyuppsattning.fgv.nu\r\nDTSTAMP:20271001T000000Z\r\nDTSTART:20271112T110000Z\r\nDTEND:20271112T130000Z\r\n")
                .doesNotContain("event-14@")
                .contains("UID:show-3@nyuppsattning.fgv.nu").contains("DTSTART:20280210T180000Z\r\nDTEND:20280210T203000Z\r\n")
                .contains("SUMMARY:Ali Baba 2028: Föreställning\r\nLOCATION:Kårhuset\r\n")
                .contains("UID:meeting-2@nyuppsattning.fgv.nu").contains("SUMMARY:Direktionsmöte\r\n")
                .doesNotContain("meeting-1@")
                .doesNotContain("kim@example.org").doesNotContain("Kim");
        assertThat(ics.indexOf("event-13@")).isLessThan(ics.indexOf("event-12@"));
        assertThat(service.render(token(), Locale.ENGLISH).orElseThrow()).contains("SUMMARY:Ali Baba 2028: Show\r\n")
                .contains("SUMMARY:Steering group meeting\r\n");
    }

    /**
     * M6-FR-1: participants and helpers have a link while their revival is open; participants read its events, helpers
     * get only their shifts (M6b), never meetings.
     */
    @Test
    void participantsGetEventsAndHelpersDoNot() {
        final Caller participant = new Caller(person, Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.PARTICIPANT, Set.of())));
        final Caller helper = new Caller(person, Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.HELPER, Set.of())));
        final EventCalendarSource events = new EventCalendarSource(eventRepository, new AccessPolicy(), TestClocks.at(TODAY));

        revival.setStatus(RevivalStatus.OPEN);
        when(eventRepository.findByRevivalId(REVIVAL_ID)).thenReturn(List.of(event(12L, "Rephelg 1", LocalDate.of(2027, 12, 11), null, false)));

        assertThat(new AccessPolicy().canSubscribeCalendar(participant, revival)).isTrue();
        assertThat(new AccessPolicy().canSubscribeCalendar(helper, revival)).isTrue();
        assertThat(events.entries(revival, participant, Locale.of("sv"))).hasSize(1);
        assertThat(events.entries(revival, helper, Locale.of("sv"))).isEmpty();

        revival.setStatus(RevivalStatus.CLOSING);
        assertThat(new AccessPolicy().canSubscribeCalendar(participant, revival)).isFalse();
        assertThat(events.entries(revival, participant, Locale.of("sv"))).isEmpty();
    }

    @Test
    void participantsAndHelpersGetNoMeetings() {
        final Caller participant = new Caller(person, Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.PARTICIPANT, Set.of())));
        final Caller helper = new Caller(person, Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.HELPER, Set.of())));

        assertThat(meetings.entries(revival, participant, Locale.of("sv"))).isEmpty();
        assertThat(meetings.entries(revival, helper, Locale.of("sv"))).isEmpty();
        verify(meetingRepository, never()).findByRevivalIdOrderByDateDescIdDesc(any());

        when(meetingRepository.findByRevivalIdOrderByDateDescIdDesc(REVIVAL_ID)).thenReturn(List.of(meeting(2L, MeetingStatus.CLOSED)));
        assertThat(meetings.entries(revival, TestCallers.directeur(), Locale.of("sv"))).hasSize(1);
    }
}
