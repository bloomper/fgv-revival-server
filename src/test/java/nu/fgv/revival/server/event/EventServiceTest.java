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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.checklist.ChecklistService;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A revival's events: defaults from the type, validation, the checklist that follows the event,
 * conflicts and who may write.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 12, 1);
    private static final LocalDate START = LocalDate.of(2027, 12, 11);
    private static final long EVENT_ID = 5L;
    private static final long TYPE_ID = 3L;
    private static final long TEMPLATE_ID = 40L;

    @Mock
    private EventRepository repository;
    @Mock
    private EventTypeRepository typeRepository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private ChecklistService checklistService;
    @Mock
    private CallerService callerService;

    private EventService service;
    private Revival revival;
    private EventType type;
    private Event event;

    private static Event event(final long id, final LocalDate start, final @Nullable Instant cancelledAt) {
        final Event event = new Event();

        event.setId(id);
        event.setRevivalId(REVIVAL_ID);
        event.setTitle("Rephelg 1");
        event.setStartDate(start);
        event.setEndDate(start.plusDays(1));
        event.setVersion(2L);
        event.setCancelledAt(cancelledAt);
        return event;
    }

    private static EventService.NewEvent newEvent(final @Nullable Long typeId, final @Nullable String title) {
        return new EventService.NewEvent(REVIVAL_ID, typeId, title, START, null, null, null, null, null, null);
    }

    private static EventService.Change change() {
        return new EventService.Change(EVENT_ID, 2L, null, null, null, null, false, null, false, null, false, null, false, null);
    }

    @BeforeEach
    void setUp() {
        service = new EventService(repository, typeRepository, revivalRepository, checklistService, callerService, new AccessPolicy(),
                TestClocks.at(TODAY));
        revival = TestCallers.revival(RevivalStatus.ORGANISING);
        type = new EventType();
        type.setId(TYPE_ID);
        type.setNameSv("Rephelg 1");
        type.setNameEn("Rehearsal weekend 1");
        type.setDays(2);
        type.setRevivalPays(true);
        type.setChecklistTemplateId(TEMPLATE_ID);
        event = event(EVENT_ID, START, null);
        lenient().when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(typeRepository.findById(TYPE_ID)).thenReturn(Optional.of(type));
        lenient().when(repository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            final Event saved = invocation.getArgument(0);

            if (saved.getId() == null) {
                saved.setId(99L);
            }
            return saved;
        });
    }

    @Test
    void theRevivalMustBeVisible() {
        when(callerService.current()).thenReturn(TestCallers.nobody());

        assertThatThrownBy(() -> service.list(REVIVAL_ID, false)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(newEvent(null, "AW"), Locale.of("sv"))).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(checklistService, never()).followEvent(eq(EVENT_ID), any(), any());
    }

    @Nested
    class Create {

        @Test
        void aTypedEventTakesItsTitleDaysAndPaymentFromTheType() {
            final EventDto created = service.create(newEvent(TYPE_ID, null), Locale.of("sv"));

            assertThat(created.title()).isEqualTo("Rephelg 1");
            assertThat(created.typeId()).isEqualTo(TYPE_ID);
            assertThat(created.endDate()).isEqualTo(START.plusDays(1));
            assertThat(created.revivalPays()).isTrue();
            assertThat(created.daysFromPremiere()).isEqualTo(-61);
            assertThat(service.create(newEvent(TYPE_ID, " "), Locale.ENGLISH).title()).isEqualTo("Rehearsal weekend 1");
        }

        @Test
        void aTypedEventGetsAChecklistFromTheTypesTemplate() {
            service.create(newEvent(TYPE_ID, "Rephelg i Kemi"), Locale.of("sv"));

            verify(checklistService).createForEvent(REVIVAL_ID, 99L, "Rephelg i Kemi", START, TEMPLATE_ID, Locale.of("sv"));
        }

        @Test
        void aFreeEventNeedsATitleAndHasNoChecklist() {
            final EventDto created = service.create(newEvent(null, "  Förrådsbesök "), Locale.of("sv"));

            assertThat(created.title()).isEqualTo("Förrådsbesök");
            assertThat(created.endDate()).isEqualTo(START);
            assertThat(created.revivalPays()).isFalse();
            verify(checklistService, never()).createForEvent(anyLong(), anyLong(), any(), any(), anyLong(), any());
            assertThatThrownBy(() -> service.create(newEvent(null, null), Locale.of("sv")))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("error.event.title.blank");
        }

        @Test
        void aRetiredTypeIsRefused() {
            type.setRetiredAt(Instant.EPOCH);

            assertThatThrownBy(() -> service.create(newEvent(TYPE_ID, null), Locale.of("sv")))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("error.event.type.retired");
        }

        @Test
        void theScheduleIsValidated() {
            assertThatThrownBy(() -> service.create(new EventService.NewEvent(REVIVAL_ID, null, "AW", START, START.minusDays(1), null, null, null,
                    null, null), Locale.of("sv"))).hasMessageContaining("error.event.endBeforeStart");
            assertThatThrownBy(() -> service.create(new EventService.NewEvent(REVIVAL_ID, null, "AW", START, START.plusDays(32), null, null, null,
                    null, null), Locale.of("sv"))).hasMessageContaining("error.event.tooLong");
            assertThatThrownBy(() -> service.create(new EventService.NewEvent(REVIVAL_ID, null, "AW", START, null, "25:00", null, null,
                    null, null), Locale.of("sv"))).hasMessageContaining("error.event.time.invalid");
            assertThatThrownBy(() -> service.create(new EventService.NewEvent(REVIVAL_ID, null, "AW", START, null, null, "18:00", null,
                    null, null), Locale.of("sv"))).hasMessageContaining("error.event.endTimeWithoutStart");
            assertThatThrownBy(() -> service.create(new EventService.NewEvent(REVIVAL_ID, null, "AW", START, null, "18:00", "17:00", null,
                    null, null), Locale.of("sv"))).hasMessageContaining("error.event.endTimeBeforeStart");
            assertThatThrownBy(() -> service.create(new EventService.NewEvent(REVIVAL_ID, null, "AW", START, null, null, null, "x".repeat(256),
                    null, null), Locale.of("sv"))).hasMessageContaining("error.event.location.tooLong");
            assertThatThrownBy(() -> service.create(new EventService.NewEvent(REVIVAL_ID, null, "x".repeat(256), START, null, null, null, null,
                    null, null), Locale.of("sv"))).hasMessageContaining("error.event.title.tooLong");
            assertThatThrownBy(() -> service.create(new EventService.NewEvent(REVIVAL_ID, null, "AW", START, null, null, null, null,
                    "x".repeat(4001), null), Locale.of("sv"))).hasMessageContaining("error.event.description.tooLong");
        }

        @Test
        void timesAreKeptAsHoursAndMinutesAndAnOvernightEndIsAllowed() {
            final EventDto created = service.create(new EventService.NewEvent(REVIVAL_ID, null, "Kickoff", START, START.plusDays(1), " 17:30 ",
                    "02:00", "Kårhuset", "Lunch och fest", false), Locale.of("sv"));

            assertThat(created.startTime()).isEqualTo("17:30");
            assertThat(created.endTime()).isEqualTo("02:00");
            assertThat(created.location()).isEqualTo("Kårhuset");
        }

        @Test
        void onlyTheSteeringGroupOfARevivalThatIsNotArchivedWrites() {
            when(callerService.current()).thenReturn(TestCallers.observer());
            assertThatThrownBy(() -> service.create(newEvent(null, "AW"), Locale.of("sv"))).isInstanceOf(AccessDeniedException.class);

            when(callerService.current()).thenReturn(TestCallers.superAdmin());
            assertThatThrownBy(() -> service.create(newEvent(null, "AW"), Locale.of("sv"))).isInstanceOf(AccessDeniedException.class);

            when(callerService.current()).thenReturn(TestCallers.directeur());
            revival.setStatus(RevivalStatus.ARCHIVED);
            assertThatThrownBy(() -> service.create(newEvent(null, "AW"), Locale.of("sv"))).isInstanceOf(ResourceNotFoundException.class);

            when(callerService.current()).thenReturn(TestCallers.superAdmin());
            assertThatThrownBy(() -> service.create(newEvent(null, "AW"), Locale.of("sv"))).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    class Update {

        @Test
        void aNewTitleAndStartDateReachTheChecklist() {
            final EventService.Change change = new EventService.Change(EVENT_ID, 2L, "Rephelg i Kemi", START.plusDays(7), START.plusDays(8), null,
                    false, null, false, null, false, null, false, null);

            final EventDto updated = service.update(change);

            assertThat(updated.title()).isEqualTo("Rephelg i Kemi");
            assertThat(updated.startDate()).isEqualTo(START.plusDays(7));
            verify(checklistService).followEvent(EVENT_ID, "Rephelg i Kemi", START.plusDays(7));
        }

        @Test
        void omittedFieldsAreUnchangedAndClearFlagsRemove() {
            event.setStartTime(LocalTime.of(10, 0));
            event.setEndTime(LocalTime.of(16, 0));
            event.setLocation("Kemi");

            final EventDto updated = service.update(new EventService.Change(EVENT_ID, 2L, null, null, null, null, true, null, true, null, true,
                    "Ta med sovsäck", false, null));

            assertThat(updated.title()).isEqualTo("Rephelg 1");
            assertThat(updated.startTime()).isNull();
            assertThat(updated.endTime()).isNull();
            assertThat(updated.location()).isNull();
            assertThat(updated.description()).isEqualTo("Ta med sovsäck");
        }

        @Test
        void clearingTheStartTimeAloneLeavesAnEndTimeWithoutOne() {
            event.setStartTime(LocalTime.of(10, 0));
            event.setEndTime(LocalTime.of(16, 0));

            assertThatThrownBy(() -> service.update(new EventService.Change(EVENT_ID, 2L, null, null, null, null, true, null, false, null, false,
                    null, false, null))).hasMessageContaining("error.event.endTimeWithoutStart");
        }

        @Test
        void aStaleVersionIsAConflict() {
            assertThatThrownBy(() -> service.update(new EventService.Change(EVENT_ID, 1L, "Ny", null, null, null, false, null, false, null, false,
                    null, false, null))).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> service.cancel(EVENT_ID, 1L)).isInstanceOf(ConflictException.class);
        }

        @Test
        void aCancelledEventIsNotChanged() {
            event.setCancelledAt(Instant.EPOCH);

            assertThatThrownBy(() -> service.update(change())).hasMessageContaining("error.event.cancelled");
        }

        @Test
        void anEventInAnotherRevivalIsNotFound() {
            event.setRevivalId(2L);

            assertThatThrownBy(() -> service.update(change())).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.get(EVENT_ID)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class CancelAndRestore {

        @Test
        void cancellingCancelsTheChecklistAndRestoringRestoresIt() {
            assertThat(service.cancel(EVENT_ID, 2L).cancelled()).isTrue();
            verify(checklistService).setCancelledWithEvent(EVENT_ID, true);

            assertThat(service.restore(EVENT_ID, 2L).cancelled()).isFalse();
            verify(checklistService).setCancelledWithEvent(EVENT_ID, false);
        }

        @Test
        void eachOnlyOnce() {
            assertThatThrownBy(() -> service.restore(EVENT_ID, 2L)).hasMessageContaining("error.event.notCancelled");
            event.setCancelledAt(Instant.EPOCH);
            assertThatThrownBy(() -> service.cancel(EVENT_ID, 2L)).hasMessageContaining("error.event.cancelled");
        }
    }

    @Nested
    class Reading {

        @Test
        void eventsAreInDateAndTimeOrderWithoutCancelledOnes() {
            final Event later = event(6L, START.plusDays(7), null);
            final Event timed = event(7L, START, null);
            final Event cancelled = event(8L, START.minusDays(1), Instant.EPOCH);

            timed.setStartTime(LocalTime.of(9, 0));
            when(repository.findByRevivalId(REVIVAL_ID)).thenReturn(List.of(later, timed, cancelled, event));

            assertThat(service.list(REVIVAL_ID, false)).extracting(EventDto::id).containsExactly(EVENT_ID, 7L, 6L);
            assertThat(service.list(REVIVAL_ID, true)).extracting(EventDto::id).containsExactly(8L, EVENT_ID, 7L, 6L);
        }

        @Test
        void observersReadEvents() {
            when(callerService.current()).thenReturn(TestCallers.observer());
            when(repository.findByRevivalId(REVIVAL_ID)).thenReturn(List.of(event));

            assertThat(service.list(REVIVAL_ID, false)).hasSize(1);
            assertThat(service.get(EVENT_ID).id()).isEqualTo(EVENT_ID);
        }

        @Test
        void upcomingAreTheNextThreeNotEndedSoonestFirst() {
            final List<Event> events = List.of(event(1L, TODAY.minusDays(3), null), event(2L, TODAY.minusDays(1), null),
                    event(3L, TODAY.plusDays(2), Instant.EPOCH), event(4L, TODAY.plusDays(9), null), event(6L, TODAY.plusDays(20), null),
                    event(7L, TODAY.plusDays(30), null));

            when(repository.findByRevivalId(REVIVAL_ID)).thenReturn(events);

            // Event 2 started yesterday and ends today, so it has not ended
            assertThat(service.upcoming(REVIVAL_ID)).extracting(EventDto::id).containsExactly(2L, 4L, 6L);
        }

        @Test
        void proposalsLeaveOutTypesWithAnEventThatIsNotCancelled() {
            final EventType show = new EventType();

            show.setId(6L);
            show.setNameSv("Föreställningshelg");
            show.setOffsetMinDays(0);
            show.setOffsetMaxDays(0);
            show.setDays(3);
            show.setPosition(5);
            type.setOffsetMinDays(-70);
            type.setOffsetMaxDays(-56);
            event.setTypeId(TYPE_ID);
            when(typeRepository.findAllByOrderByPositionAscIdAsc()).thenReturn(List.of(type, show));
            when(repository.findByRevivalId(REVIVAL_ID)).thenReturn(List.of(event));

            assertThat(service.proposals(REVIVAL_ID)).extracting(proposal -> proposal.type().id()).containsExactly(6L);

            event.setCancelledAt(Instant.EPOCH);
            assertThat(service.proposals(REVIVAL_ID)).extracting(proposal -> proposal.type().id()).containsExactly(TYPE_ID, 6L);
        }
    }
}
