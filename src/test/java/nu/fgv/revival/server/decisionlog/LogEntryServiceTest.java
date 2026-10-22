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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.checklist.ChecklistItemRepository;
import nu.fgv.revival.server.meeting.MeetingEntryChangeKind;
import nu.fgv.revival.server.meeting.MeetingRecorder;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerInputDto;
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
import nu.fgv.revival.server.util.jpa.SpecificationQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static nu.fgv.revival.server.TestCallers.PERSON_ID;
import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The decision log's rules (FR-008–FR-015, FR-018): numbering, validation, statuses, answering, overdue and who may
 * write.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@ExtendWith(MockitoExtension.class)
class LogEntryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    @Mock
    private LogEntryRepository repository;
    @Mock
    private LogEntryCounterRepository counterRepository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private ChecklistItemRepository checklistItemRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private OwnerResolver ownerResolver;
    @Mock
    private OwnerViews ownerViews;
    @Mock
    private PersonNames personNames;
    @Mock
    private MeetingRecorder meetingRecorder;
    @Mock
    private SpecificationQueries specificationQueries;

    private final LogEntryCounter counter = new LogEntryCounter(REVIVAL_ID);
    private LogEntryService service;
    private Revival revival;

    @BeforeEach
    void setUp() {
        service = new LogEntryService(repository, new LogEntryCounters(counterRepository), revivalRepository, checklistItemRepository,
                callerService, new AccessPolicy(), ownerResolver, ownerViews, personNames, meetingRecorder, specificationQueries,
                TestClocks.at(TODAY));
        revival = TestCallers.revival(RevivalStatus.ORGANISING);
        lenient().when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(counterRepository.findForUpdate(REVIVAL_ID)).thenReturn(Optional.of(counter));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            final LogEntry entry = invocation.getArgument(0);

            if (entry.getId() == null) {
                entry.setId(100L + entry.getNumber());
            }
            return entry;
        });
        lenient().when(ownerResolver.validate(eq(REVIVAL_ID), any())).thenReturn(Owner.none());
    }

    private static LogEntryService.Draft draft(final LogEntryType type, final String text) {
        return new LogEntryService.Draft(REVIVAL_ID, null, null, type, text, null, null, null, null, null, null, null);
    }

    private static LogEntry entry(final long id, final LogEntryType type, final LogEntryStatus status) {
        final LogEntry entry = new LogEntry();

        entry.setId(id);
        entry.setRevivalId(REVIVAL_ID);
        entry.setNumber((int) id);
        entry.setDate(TODAY);
        entry.setType(type);
        entry.setText("Text " + id);
        entry.setStatus(status);
        entry.setOrigin(LogEntryOrigin.MANUAL);
        entry.setVersion(2L);
        entry.setCreatedBy("subject");
        return entry;
    }

    private static LogEntryService.Change change(final long id) {
        return new LogEntryService.Change(id, 2L, null, null, null, null, null, null, false, null, false, null, false, null, null, null, false);
    }

    @Nested
    class Create {

        @Test
        void takesTheNextNumberTodayOpenAndManual() {
            counter.setLastNumber(12);

            final LogEntryDto created = service.create(draft(LogEntryType.TODO, "  Boka RunAn  "), Locale.ENGLISH);

            assertThat(created.number()).isEqualTo(13);
            assertThat(created.date()).isEqualTo(TODAY);
            assertThat(created.status()).isEqualTo(LogEntryStatus.OPEN);
            assertThat(created.origin()).isEqualTo(LogEntryOrigin.MANUAL);
            assertThat(created.text()).isEqualTo("Boka RunAn");
            assertThat(counter.getLastNumber()).isEqualTo(13);
        }

        @Test
        void textMustBeNonBlankAndAtMost5000Characters() {
            assertThatThrownBy(() -> service.create(draft(LogEntryType.DECISION, "   "), Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.create(draft(LogEntryType.DECISION, "x".repeat(5001)), Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
            assertThat(service.create(draft(LogEntryType.DECISION, "x".repeat(5000)), Locale.ENGLISH).text()).hasSize(5000);
        }

        @Test
        void aCommentIsAtMost5000CharactersAndBlankIsNone() {
            final LogEntryService.Draft tooLong = new LogEntryService.Draft(REVIVAL_ID, null, null, LogEntryType.TODO, "x", null, null,
                    null, "c".repeat(5001), null, null, null);
            final LogEntryService.Draft blank = new LogEntryService.Draft(REVIVAL_ID, null, null, LogEntryType.TODO, "x", null, null,
                    null, "  ", null, null, null);

            assertThatThrownBy(() -> service.create(tooLong, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
            assertThat(service.create(blank, Locale.ENGLISH).comment()).isNull();
        }

        @Test
        void tagsAreTrimmedDeduplicatedAndAtMost50Characters() {
            final LogEntryService.Draft tagged = new LogEntryService.Draft(REVIVAL_ID, null, null, LogEntryType.TODO, "x", null, null,
                    null, null, List.of(" lokal ", "lokal", "budget"), null, null);
            final LogEntryService.Draft tooLong = new LogEntryService.Draft(REVIVAL_ID, null, null, LogEntryType.TODO, "x", null, null,
                    null, null, List.of("t".repeat(51)), null, null);

            assertThat(service.create(tagged, Locale.ENGLISH).tags()).containsExactly("budget", "lokal");
            assertThatThrownBy(() -> service.create(tooLong, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void ownersGoThroughTheResolver() {
            final OwnerInputDto input = new OwnerInputDto(null, "Sam");

            when(ownerResolver.validate(REVIVAL_ID, input)).thenReturn(Owner.named("Sam"));

            final LogEntryService.Draft owned = new LogEntryService.Draft(REVIVAL_ID, null, null, LogEntryType.TODO, "x", null, input,
                    null, null, null, null, null);
            final ArgumentCaptor<LogEntry> saved = ArgumentCaptor.forClass(LogEntry.class);

            service.create(owned, Locale.ENGLISH);
            verify(repository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getOwner()).isEqualTo(Owner.named("Sam"));
        }

        @Test
        void aChecklistItemInAnotherRevivalIsRefused() {
            final ChecklistItem item = new ChecklistItem();

            item.setRevivalId(2L);
            when(checklistItemRepository.findById(7L)).thenReturn(Optional.of(item));

            final LogEntryService.Draft linked = new LogEntryService.Draft(REVIVAL_ID, null, null, LogEntryType.TODO, "x", null, null,
                    null, null, null, null, 7L);

            assertThatThrownBy(() -> service.create(linked, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void aRowFromAMeetingIsRecordedAsCreatedInIt() {
            final LogEntryService.Draft inMeeting = new LogEntryService.Draft(REVIVAL_ID, 5L, null, LogEntryType.DECISION, "x", null,
                    null, null, null, null, null, null);
            final LogEntryDto created = service.create(inMeeting, Locale.ENGLISH);

            verify(meetingRecorder).requireInProgress(5L, REVIVAL_ID);
            verify(meetingRecorder).record(5L, created.id(), EnumSet.of(MeetingEntryChangeKind.CREATED), PERSON_ID);
            assertThat(created.meetingId()).isEqualTo(5L);
        }
    }

    @Nested
    class Update {

        @ParameterizedTest
        @EnumSource(LogEntryStatus.class)
        void anyStatusMayFollowAnyOther(final LogEntryStatus to) {
            for (final LogEntryStatus from : LogEntryStatus.values()) {
                final LogEntry entry = entry(1L, LogEntryType.TODO, from);

                when(repository.findById(1L)).thenReturn(Optional.of(entry));
                assertThat(service.update(new LogEntryService.Change(1L, 2L, null, null, null, null, to, null, false, null, false,
                        null, false, null, null, null, false), Locale.ENGLISH).status()).isEqualTo(to);
            }
        }

        @Test
        void aStaleVersionIsAConflict() {
            when(repository.findById(1L)).thenReturn(Optional.of(entry(1L, LogEntryType.TODO, LogEntryStatus.OPEN)));

            assertThatThrownBy(() -> service.update(new LogEntryService.Change(1L, 1L, null, null, null, "y", null, null, false, null,
                    false, null, false, null, null, null, false), Locale.ENGLISH)).isInstanceOf(ConflictException.class);
        }

        @Test
        void theNumberNeverChanges() {
            final LogEntry entry = entry(4L, LogEntryType.TODO, LogEntryStatus.OPEN);

            when(repository.findById(4L)).thenReturn(Optional.of(entry));
            assertThat(service.update(new LogEntryService.Change(4L, 2L, null, TODAY.minusDays(3), LogEntryType.DECISION, "Ny text",
                    LogEntryStatus.DONE, null, true, TODAY, false, "k", false, List.of("t"), "key", null, false), Locale.ENGLISH).number())
                    .isEqualTo(4);
        }

        @Test
        void aChangeInAMeetingRecordsWhatHappened() {
            final LogEntry entry = entry(1L, LogEntryType.TODO, LogEntryStatus.OPEN);
            final OwnerInputDto owner = new OwnerInputDto(PERSON_ID, null);

            when(repository.findById(1L)).thenReturn(Optional.of(entry));
            when(ownerResolver.validate(REVIVAL_ID, owner)).thenReturn(Owner.member(PERSON_ID));

            service.update(new LogEntryService.Change(1L, 2L, 5L, null, null, "Ny text", LogEntryStatus.DONE, owner, false, null, false,
                    null, false, null, null, null, false), Locale.ENGLISH);

            verify(meetingRecorder).record(5L, 1L, EnumSet.of(MeetingEntryChangeKind.ASSIGNED, MeetingEntryChangeKind.CLOSED,
                    MeetingEntryChangeKind.UPDATED), PERSON_ID);
        }

        @Test
        void anUnchangedRowRecordsNothing() {
            when(repository.findById(1L)).thenReturn(Optional.of(entry(1L, LogEntryType.TODO, LogEntryStatus.OPEN)));

            service.update(new LogEntryService.Change(1L, 2L, 5L, null, null, null, null, null, false, null, false, null, false, null,
                    null, null, false), Locale.ENGLISH);

            verify(meetingRecorder, never()).record(any(), any(), anyCollection(), any());
        }
    }

    @Nested
    class AnswerQuestion {

        @Test
        void createsALinkedDecisionAndClosesTheQuestion() {
            final LogEntry question = entry(3L, LogEntryType.QUESTION, LogEntryStatus.OPEN);

            when(repository.findById(3L)).thenReturn(Optional.of(question));
            when(repository.findByAnswersEntryId(3L)).thenReturn(Optional.empty());

            final LogEntryService.Answered answered = service.answer(new LogEntryService.Answer(3L, 2L, null, "Vi spelar i RunAn", null,
                    null, null), Locale.ENGLISH);

            assertThat(answered.question().status()).isEqualTo(LogEntryStatus.DONE);
            assertThat(answered.decision().type()).isEqualTo(LogEntryType.DECISION);
            assertThat(answered.decision().answersEntryId()).isEqualTo(3L);
        }

        @Test
        void onlyAnOpenQuestionCanBeAnswered() {
            when(repository.findById(3L)).thenReturn(Optional.of(entry(3L, LogEntryType.TODO, LogEntryStatus.OPEN)));
            when(repository.findById(4L)).thenReturn(Optional.of(entry(4L, LogEntryType.QUESTION, LogEntryStatus.DONE)));

            assertThatThrownBy(() -> service.answer(new LogEntryService.Answer(3L, 2L, null, "x", null, null, null), Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.answer(new LogEntryService.Answer(4L, 2L, null, "x", null, null, null), Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void aQuestionIsAnsweredOnce() {
            when(repository.findById(3L)).thenReturn(Optional.of(entry(3L, LogEntryType.QUESTION, LogEntryStatus.WAITING)));
            when(repository.findByAnswersEntryId(3L)).thenReturn(Optional.of(entry(9L, LogEntryType.DECISION, LogEntryStatus.OPEN)));

            assertThatThrownBy(() -> service.answer(new LogEntryService.Answer(3L, 2L, null, "x", null, null, null), Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    class Overdue {

        @Test
        void openAndWaitingRowsWithAPastDeadlineAreOverdue() {
            final LogEntry entry = entry(1L, LogEntryType.TODO, LogEntryStatus.OPEN);

            entry.setDeadline(TODAY.minusDays(1));
            assertThat(entry.isOverdue(TODAY)).isTrue();
            entry.setStatus(LogEntryStatus.WAITING);
            assertThat(entry.isOverdue(TODAY)).isTrue();
            entry.setDeadline(TODAY);
            assertThat(entry.isOverdue(TODAY)).isFalse();
        }

        @Test
        void doneAndCancelledRowsAreNeverOverdue() {
            for (final LogEntryStatus status : Set.of(LogEntryStatus.DONE, LogEntryStatus.CANCELLED)) {
                final LogEntry entry = entry(1L, LogEntryType.TODO, status);

                entry.setDeadline(TODAY.minusDays(10));
                assertThat(entry.isOverdue(TODAY)).isFalse();
            }
        }
    }

    @Nested
    class Refusals {

        private void as(final Caller caller) {
            when(callerService.current()).thenReturn(caller);
        }

        @Test
        void observersAndSuperAdminsReadButNeverWrite() {
            for (final Caller caller : List.of(TestCallers.observer(), TestCallers.superAdmin())) {
                as(caller);
                assertThatThrownBy(() -> service.create(draft(LogEntryType.TODO, "x"), Locale.ENGLISH)).isInstanceOf(AccessDeniedException.class);
            }
        }

        @Test
        void anArchivedRevivalIsReadOnly() {
            revival.setStatus(RevivalStatus.ARCHIVED);
            for (final Caller caller : List.of(TestCallers.superAdmin(), TestCallers.observer())) {
                as(caller);
                assertThatThrownBy(() -> service.create(draft(LogEntryType.TODO, "x"), Locale.ENGLISH)).isInstanceOf(AccessDeniedException.class);
            }
            // Directeurs no longer see an archived revival at all
            as(TestCallers.directeur());
            assertThatThrownBy(() -> service.create(draft(LogEntryType.TODO, "x"), Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void someoneWhoCannotSeeTheRevivalGetsNotFound() {
            as(TestCallers.nobody());
            assertThatThrownBy(() -> service.create(draft(LogEntryType.TODO, "x"), Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.tags(REVIVAL_ID)).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void aRowOfARevivalTheCallerCannotSeeIsNotFound() {
            as(TestCallers.nobody());
            when(repository.findById(1L)).thenReturn(Optional.of(entry(1L, LogEntryType.TODO, LogEntryStatus.OPEN)));

            assertThatThrownBy(() -> service.get(1L, Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
