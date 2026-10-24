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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryService;
import nu.fgv.revival.server.decisionlog.LogEntrySummaryDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Steering group meetings run from the decision log (FR-016–FR-020): planned, started by whoever records, closed once
 * every open or waiting to-do has an owner, producing minutes that never change. Never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Service
public class MeetingService {

    private final MeetingRepository repository;
    private final MeetingEntryChangeRepository changeRepository;
    private final RevivalRepository revivalRepository;
    private final LogEntryService logEntryService;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final OwnerResolver ownerResolver;
    private final OwnerViews ownerViews;
    private final PersonNames personNames;
    private final Clock clock;

    public MeetingService(final MeetingRepository repository,
                          final MeetingEntryChangeRepository changeRepository,
                          final RevivalRepository revivalRepository,
                          final LogEntryService logEntryService,
                          final CallerService callerService,
                          final AccessPolicy accessPolicy,
                          final OwnerResolver ownerResolver,
                          final OwnerViews ownerViews,
                          final PersonNames personNames,
                          final Clock clock) {
        this.repository = repository;
        this.changeRepository = changeRepository;
        this.revivalRepository = revivalRepository;
        this.logEntryService = logEntryService;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.ownerResolver = ownerResolver;
        this.ownerViews = ownerViews;
        this.personNames = personNames;
        this.clock = clock;
    }

    private static @Nullable String validTitle(final @Nullable String title) {
        if (title == null || title.isBlank()) {
            return null;
        }

        final String trimmed = title.strip();

        if (trimmed.length() > Meeting.TITLE_MAX_LENGTH) {
            throw new BadRequestException("error.meeting.title.tooLong", Meeting.TITLE_MAX_LENGTH);
        }
        return trimmed;
    }

    private static @Nullable String validOthersPresent(final @Nullable String othersPresent) {
        if (othersPresent == null || othersPresent.isBlank()) {
            return null;
        }

        final String trimmed = othersPresent.strip();

        if (trimmed.length() > Meeting.OTHERS_PRESENT_MAX_LENGTH) {
            throw new BadRequestException("error.meeting.othersPresent.tooLong", Meeting.OTHERS_PRESENT_MAX_LENGTH);
        }
        return trimmed;
    }

    private static List<String> validAgendaPoints(final @Nullable List<String> points) {
        final List<String> valid = new ArrayList<>();

        for (final String point : points == null ? List.<String>of() : points) {
            final String trimmed = point.strip();

            if (trimmed.isEmpty() || trimmed.length() > Meeting.AGENDA_POINT_MAX_LENGTH) {
                throw new BadRequestException("error.meeting.agendaPoint", Meeting.AGENDA_POINT_MAX_LENGTH);
            }
            valid.add(trimmed);
        }
        return valid;
    }

    private static Owner owner(final Minutes.Row row) {
        return row.ownerPersonId() != null ? Owner.member(row.ownerPersonId()) : row.ownerName() != null ? Owner.named(row.ownerName()) : Owner.none();
    }

    @Transactional(readOnly = true)
    public List<MeetingSummaryDto> list(final Long revivalId) {
        visible(callerService.current(), revivalId);
        return repository.findByRevivalIdOrderByDateDescIdDesc(revivalId).stream().map(MeetingSummaryDto::of).toList();
    }

    @Transactional(readOnly = true)
    public MeetingDto get(final Long id, final Locale locale) {
        return toDto(readable(callerService.current(), id), locale);
    }

    /**
     * The open or waiting to-dos without an owner, which block closing while the meeting is in progress (FR-019).
     */
    @Transactional(readOnly = true)
    public List<LogEntrySummaryDto> blocking(final Long meetingId) {
        final Meeting meeting = readable(callerService.current(), meetingId);

        return meeting.getStatus() != MeetingStatus.IN_PROGRESS ? List.of()
                : logEntryService.unownedOpenTodos(meeting.getRevivalId()).stream().map(LogEntrySummaryDto::of).toList();
    }

    @Transactional
    public MeetingDto create(final Plan plan, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = writable(caller, plan.revivalId());

        AuditContext.operation("meetingCreate");

        final Meeting meeting = new Meeting();

        meeting.setRevivalId(revival.getId());
        meeting.setDate(plan.date());
        meeting.setTitle(validTitle(plan.title()));
        meeting.setOthersPresent(validOthersPresent(plan.othersPresent()));
        meeting.setAttendeeIds(validAttendees(revival.getId(), plan.attendeeIds()));
        meeting.setAgendaPoints(validAgendaPoints(plan.agendaPoints()));
        return toDto(repository.saveAndFlush(meeting), locale);
    }

    @Transactional
    public MeetingDto update(final Change change, final Locale locale) {
        final Caller caller = callerService.current();
        final Meeting meeting = changeable(caller, change.id(), change.version());

        if (meeting.getStatus() != MeetingStatus.PLANNED && meeting.getStatus() != MeetingStatus.IN_PROGRESS) {
            throw new BadRequestException("error.meeting.closed");
        }
        AuditContext.operation("meetingUpdate");
        if (change.date() != null) {
            meeting.setDate(change.date());
        }
        if (change.title() != null) {
            meeting.setTitle(validTitle(change.title()));
        }
        if (change.othersPresent() != null) {
            meeting.setOthersPresent(validOthersPresent(change.othersPresent()));
        }
        if (change.attendeeIds() != null) {
            meeting.setAttendeeIds(validAttendees(meeting.getRevivalId(), change.attendeeIds()));
        }
        if (change.agendaPoints() != null) {
            meeting.setAgendaPoints(validAgendaPoints(change.agendaPoints()));
        }
        return toDto(repository.saveAndFlush(meeting), locale);
    }

    @Transactional
    public MeetingDto start(final Long id, final Long version, final Locale locale) {
        final Caller caller = callerService.current();
        final Meeting meeting = changeable(caller, id, version);

        if (meeting.getStatus() != MeetingStatus.PLANNED) {
            throw new BadRequestException("error.meeting.notPlanned");
        }
        if (repository.findFirstByRevivalIdAndStatus(meeting.getRevivalId(), MeetingStatus.IN_PROGRESS).isPresent()) {
            throw new ConflictException("error.meeting.inProgress");
        }
        AuditContext.operation("meetingStart");
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        meeting.setRecorderPersonId(caller.person().getId());
        meeting.setStartedAt(clock.instant());
        try {
            return toDto(repository.saveAndFlush(meeting), locale);
        } catch (final DataIntegrityViolationException e) {
            throw new ConflictException("error.meeting.inProgress");
        }
    }

    @Transactional
    public MeetingDto close(final Long id, final Long version, final Locale locale) {
        final Caller caller = callerService.current();
        final Meeting meeting = changeable(caller, id, version);

        if (meeting.getStatus() != MeetingStatus.IN_PROGRESS) {
            throw new BadRequestException("error.meeting.notInProgress");
        }

        final List<LogEntry> unowned = logEntryService.unownedOpenTodos(meeting.getRevivalId());

        if (!unowned.isEmpty()) {
            throw new BadRequestException("error.meeting.unownedTodos",
                    unowned.stream().map(entry -> String.valueOf(entry.getNumber())).collect(Collectors.joining(", ")));
        }
        AuditContext.operation("meetingClose");

        final List<MeetingEntryChange> changes = changeRepository.findByMeetingIdOrderByIdAsc(meeting.getId());
        final Map<Long, LogEntry> entries = logEntryService.byIds(changes.stream().map(MeetingEntryChange::getEntryId).collect(Collectors.toSet()));

        meeting.setMinutes(MinutesBuilder.build(meeting, changes, entries));
        meeting.setStatus(MeetingStatus.CLOSED);
        meeting.setClosedAt(clock.instant());
        meeting.setClosedBy(caller.person().getId());
        return toDto(repository.saveAndFlush(meeting), locale);
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    @Transactional
    public MeetingDto cancel(final Long id, final Long version, final Locale locale) {
        final Caller caller = callerService.current();
        final Meeting meeting = changeable(caller, id, version);

        if (meeting.getStatus() != MeetingStatus.PLANNED) {
            throw new BadRequestException("error.meeting.notPlanned");
        }
        AuditContext.operation("meetingCancel");
        meeting.setStatus(MeetingStatus.CANCELLED);
        return toDto(repository.saveAndFlush(meeting), locale);
    }

    /**
     * The meeting, for the minutes email. Throws unless the caller can read it.
     */
    @Transactional(readOnly = true)
    public Meeting readable(final Long id) {
        return readable(callerService.current(), id);
    }

    private Revival visible(final Caller caller, final Long revivalId) {
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireReadRevivalWork(caller, revival);
        return revival;
    }

    private Revival writable(final Caller caller, final Long revivalId) {
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireWriteRevivalWork(caller, revival);
        return revival;
    }

    private Meeting readable(final Caller caller, final Long id) {
        final Meeting meeting = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(Meeting.class, id));
        final Revival revival = revivalRepository.findById(meeting.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canReadRevivalWork(caller, revival)) {
            throw new ResourceNotFoundException(Meeting.class, id);
        }
        return meeting;
    }

    private Meeting changeable(final Caller caller, final Long id, final Long version) {
        final Meeting meeting = readable(caller, id);

        writable(caller, meeting.getRevivalId());
        if (!Objects.equals(version, meeting.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return meeting;
    }

    private Set<Long> validAttendees(final Long revivalId, final @Nullable List<Long> attendeeIds) {
        final Set<Long> attendees = new LinkedHashSet<>(attendeeIds == null ? List.of() : attendeeIds);

        if (!ownerResolver.activeMembers(revivalId).keySet().containsAll(attendees)) {
            throw new BadRequestException("error.meeting.attendee");
        }
        return attendees;
    }

    MeetingDto toDto(final Meeting meeting, final Locale locale) {
        final Minutes minutes = meeting.getMinutes();
        final Set<Long> people = new HashSet<>(meeting.getAttendeeIds());

        if (meeting.getRecorderPersonId() != null) {
            people.add(meeting.getRecorderPersonId());
        }
        if (minutes != null) {
            people.addAll(minutes.attendeeIds());
            if (minutes.recorderId() != null) {
                people.add(minutes.recorderId());
            }
        }

        final Map<Long, String> names = people.isEmpty() ? Map.of() : personNames.byIds(people, locale);
        final Function<Long, PersonRefDto> person = id -> new PersonRefDto(id, names.get(id));

        return new MeetingDto(meeting.getId(), meeting.getRevivalId(), meeting.getDate(), meeting.getTitle(), meeting.getStatus(),
                meeting.getAttendeeIds().stream().map(person).toList(), meeting.getOthersPresent(), List.copyOf(meeting.getAgendaPoints()),
                meeting.getRecorderPersonId() == null ? null : person.apply(meeting.getRecorderPersonId()), meeting.getStartedAt(),
                meeting.getClosedAt(), minutes == null ? null : minutes(meeting.getRevivalId(), minutes, person, locale), meeting.getVersion());
    }

    private MeetingDto.MinutesDto minutes(final Long revivalId, final Minutes minutes, final Function<Long, PersonRefDto> person, final Locale locale) {
        final Collection<OwnerViews.Ref> refs = Stream.of(minutes.decisions(), minutes.todos(), minutes.questions(), minutes.closed())
                .flatMap(List::stream)
                .map(row -> new OwnerViews.Ref(revivalId, owner(row)))
                .collect(Collectors.toSet());
        final Map<OwnerViews.Ref, @Nullable OwnerDto> owners = ownerViews.toDtos(refs, locale);
        final Function<List<Minutes.Row>, List<MeetingDto.RowDto>> rows = list -> list.stream()
                .map(row -> new MeetingDto.RowDto(row.entryId(), row.number(), row.type(), row.text(),
                        owners.get(new OwnerViews.Ref(revivalId, owner(row))), row.deadline(), row.status()))
                .toList();

        return new MeetingDto.MinutesDto(rows.apply(minutes.decisions()), rows.apply(minutes.todos()), rows.apply(minutes.questions()),
                rows.apply(minutes.closed()), minutes.agendaPoints(), minutes.attendeeIds().stream().map(person).toList(),
                minutes.othersPresent(), minutes.recorderId() == null ? null : person.apply(minutes.recorderId()));
    }

    public record Plan(Long revivalId, LocalDate date, @Nullable String title, @Nullable List<Long> attendeeIds,
                       @Nullable String othersPresent, @Nullable List<String> agendaPoints) {
    }

    /**
     * Fields left null are unchanged; lists replace.
     */
    public record Change(Long id, Long version, @Nullable LocalDate date, @Nullable String title,
                         @Nullable List<Long> attendeeIds,
                         @Nullable String othersPresent, @Nullable List<String> agendaPoints) {
    }
}
