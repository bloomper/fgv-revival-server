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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryService;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.PERSON_ID;
import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * A meeting's lifecycle and its closing rule (FR-016–FR-020).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final long MEETING_ID = 5L;

    @Mock
    private MeetingRepository repository;
    @Mock
    private MeetingEntryChangeRepository changeRepository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private LogEntryService logEntryService;
    @Mock
    private CallerService callerService;
    @Mock
    private OwnerResolver ownerResolver;
    @Mock
    private OwnerViews ownerViews;
    @Mock
    private PersonNames personNames;

    private MeetingService service;
    private Revival revival;
    private Meeting meeting;

    @BeforeEach
    void setUp() {
        service = new MeetingService(repository, changeRepository, revivalRepository, logEntryService, callerService, new AccessPolicy(),
                ownerResolver, ownerViews, personNames, TestClocks.at(TODAY));
        revival = TestCallers.revival(RevivalStatus.ORGANISING);
        meeting = new Meeting();
        meeting.setId(MEETING_ID);
        meeting.setRevivalId(REVIVAL_ID);
        meeting.setDate(TODAY);
        meeting.setVersion(1L);
        lenient().when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(repository.findById(MEETING_ID)).thenReturn(Optional.of(meeting));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(ownerResolver.activeMembers(REVIVAL_ID)).thenReturn(new LinkedHashMap<>(Map.of(PERSON_ID, "Kim", 12L, "Robin")));
        lenient().when(personNames.byIds(anyCollection(), any())).thenReturn(Map.of());
    }

    @Nested
    class Transitions {

        @Test
        void aMeetingGoesFromPlannedThroughInProgressToClosed() {
            final MeetingDto started = service.start(MEETING_ID, 1L, Locale.ENGLISH);

            assertThat(started.status()).isEqualTo(MeetingStatus.IN_PROGRESS);
            assertThat(meeting.getRecorderPersonId()).isEqualTo(PERSON_ID);
            assertThat(meeting.getStartedAt()).isNotNull();

            final MeetingDto closed = service.close(MEETING_ID, 1L, Locale.ENGLISH);

            assertThat(closed.status()).isEqualTo(MeetingStatus.CLOSED);
            assertThat(meeting.getMinutes()).isNotNull();
            assertThat(meeting.getClosedBy()).isEqualTo(PERSON_ID);
        }

        @Test
        void onlyAPlannedMeetingIsCancelled() {
            assertThat(service.cancel(MEETING_ID, 1L, Locale.ENGLISH).status()).isEqualTo(MeetingStatus.CANCELLED);
            assertThatThrownBy(() -> service.start(MEETING_ID, 1L, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @ParameterizedTest
        @EnumSource(value = MeetingStatus.class, names = {"IN_PROGRESS", "CLOSED", "CANCELLED"})
        void otherTransitionsAreRefused(final MeetingStatus status) {
            meeting.setStatus(status);

            assertThatThrownBy(() -> service.start(MEETING_ID, 1L, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.cancel(MEETING_ID, 1L, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
            if (status != MeetingStatus.IN_PROGRESS) {
                assertThatThrownBy(() -> service.close(MEETING_ID, 1L, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
            }
        }

        @Test
        void aSecondMeetingInProgressIsAConflict() {
            when(repository.findFirstByRevivalIdAndStatus(REVIVAL_ID, MeetingStatus.IN_PROGRESS)).thenReturn(Optional.of(new Meeting()));

            assertThatThrownBy(() -> service.start(MEETING_ID, 1L, Locale.ENGLISH)).isInstanceOf(ConflictException.class);
        }

        @Test
        void aStaleVersionIsAConflict() {
            assertThatThrownBy(() -> service.start(MEETING_ID, 0L, Locale.ENGLISH)).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class Closing {

        @Test
        void isRefusedWhileOpenOrWaitingToDosHaveNoOwnerListingThem() {
            final LogEntry first = new LogEntry();
            final LogEntry second = new LogEntry();

            first.setNumber(4);
            second.setNumber(9);
            meeting.setStatus(MeetingStatus.IN_PROGRESS);
            when(logEntryService.unownedOpenTodos(REVIVAL_ID)).thenReturn(List.of(first, second));

            assertThatThrownBy(() -> service.close(MEETING_ID, 1L, Locale.ENGLISH)).isInstanceOfSatisfying(BadRequestException.class, e -> {
                assertThat(e.getDetailMessageCode()).isEqualTo("error.meeting.unownedTodos");
                assertThat(e.getDetailMessageArguments()).containsExactly("4, 9");
            });
            assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);
        }
    }

    @Nested
    class Planning {

        @Test
        void attendeesMustBeActiveDirecteursOrSteeringGroupMembers() {
            final MeetingService.Plan plan = new MeetingService.Plan(REVIVAL_ID, TODAY, null, List.of(PERSON_ID, 99L), null, null);

            assertThatThrownBy(() -> service.create(plan, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void agendaPointsAreTrimmedAndKeptInOrder() {
            final MeetingDto created = service.create(new MeetingService.Plan(REVIVAL_ID, TODAY, " Möte 4 ", List.of(PERSON_ID), "  ",
                    List.of(" Budget ", "Lokaler")), Locale.ENGLISH);

            assertThat(created.agendaPoints()).containsExactly("Budget", "Lokaler");
            assertThat(created.title()).isEqualTo("Möte 4");
            assertThat(created.othersPresent()).isNull();
            assertThat(created.status()).isEqualTo(MeetingStatus.PLANNED);
        }

        @Test
        void aBlankOrTooLongAgendaPointIsRefused() {
            assertThatThrownBy(() -> service.create(new MeetingService.Plan(REVIVAL_ID, TODAY, null, null, null, List.of(" ")), Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.create(new MeetingService.Plan(REVIVAL_ID, TODAY, null, null, null, List.of("x".repeat(1001))),
                    Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void aClosedMeetingIsFrozen() {
            meeting.setStatus(MeetingStatus.CLOSED);

            assertThatThrownBy(() -> service.update(new MeetingService.Change(MEETING_ID, 1L, null, "Ny", null, null, null), Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    class Refusals {

        @Test
        void observersAndSuperAdminsNeverChangeMeetings() {
            for (final Caller caller : List.of(TestCallers.observer(), TestCallers.superAdmin())) {
                when(callerService.current()).thenReturn(caller);
                assertThatThrownBy(() -> service.start(MEETING_ID, 1L, Locale.ENGLISH)).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> service.create(new MeetingService.Plan(REVIVAL_ID, TODAY, null, null, null, null), Locale.ENGLISH))
                        .isInstanceOf(AccessDeniedException.class);
            }
        }

        @Test
        void anArchivedRevivalIsReadOnly() {
            revival.setStatus(RevivalStatus.ARCHIVED);
            when(callerService.current()).thenReturn(TestCallers.superAdmin());

            assertThatThrownBy(() -> service.cancel(MEETING_ID, 1L, Locale.ENGLISH)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        void someoneWhoCannotSeeTheRevivalGetsNotFound() {
            when(callerService.current()).thenReturn(TestCallers.nobody());

            assertThatThrownBy(() -> service.get(MEETING_ID, Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.list(REVIVAL_ID)).isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
