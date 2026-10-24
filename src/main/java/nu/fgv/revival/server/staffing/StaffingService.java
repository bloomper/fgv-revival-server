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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantAccess;
import nu.fgv.revival.server.participant.ParticipantDirectory;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.participant.ParticipantResponse;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.staffing.StaffingDtos.AvailabilityDay;
import nu.fgv.revival.server.staffing.StaffingDtos.ParticipantAvailability;
import nu.fgv.revival.server.staffing.StaffingDtos.ParticipantRef;
import nu.fgv.revival.server.staffing.StaffingDtos.ShiftAssignmentDto;
import nu.fgv.revival.server.staffing.StaffingDtos.ShiftSlotDto;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Availability and staffing (product spec 5.7, M6B-FR-1–4, M6B-FR-7). Readers of participants read everyone's
 * availability; readers of staffing read the slots; writers of staffing plan slots and assign people. A participant or
 * helper sets their own availability and, as a participant who said yes or maybe, signs up for slots with room and
 * withdraws from those they signed up for. Every method asks {@link AccessPolicy} first (constitution III).
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Service
public class StaffingService {

    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<ParticipantResponse> COMING = EnumSet.of(ParticipantResponse.YES, ParticipantResponse.MAYBE);
    private static final Comparator<ShiftSlot> ORDER = Comparator.comparing(ShiftSlot::getDay)
            .thenComparing(ShiftSlot::getStartTime)
            .thenComparing(ShiftSlot::getEndTime)
            .thenComparing(ShiftSlot::getTask)
            .thenComparing(ShiftSlot::getId);

    private final AvailabilityRepository availabilityRepository;
    private final ShiftSlotRepository slotRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final ParticipantRepository participantRepository;
    private final EventRepository eventRepository;
    private final RevivalRepository revivalRepository;
    private final ParticipantAccess participantAccess;
    private final ParticipantDirectory directory;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public StaffingService(final AvailabilityRepository availabilityRepository,
                           final ShiftSlotRepository slotRepository,
                           final ShiftAssignmentRepository assignmentRepository,
                           final ParticipantRepository participantRepository,
                           final EventRepository eventRepository,
                           final RevivalRepository revivalRepository,
                           final ParticipantAccess participantAccess,
                           final ParticipantDirectory directory,
                           final CallerService callerService,
                           final AccessPolicy accessPolicy,
                           final Clock clock) {
        this.availabilityRepository = availabilityRepository;
        this.slotRepository = slotRepository;
        this.assignmentRepository = assignmentRepository;
        this.participantRepository = participantRepository;
        this.eventRepository = eventRepository;
        this.revivalRepository = revivalRepository;
        this.participantAccess = participantAccess;
        this.directory = directory;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /**
     * A slot as the writer gives it. The event is read only on create.
     */
    public record SlotInput(@Nullable Long eventId, LocalDate date, String startTime, String endTime, String task, int needed,
                            @Nullable String comment) {
    }

    /**
     * The caller's answer for an event day; a null answer clears it.
     */
    public record AvailabilityInput(Long revivalId, Long eventId, LocalDate date, @Nullable AvailabilityAnswer answer, @Nullable String comment) {
    }

    // --- Reading ----------------------------------------------------------------------------------------------------

    /**
     * Everyone's answers for the revival's events that are not cancelled, or one of them, by participant name. Readers
     * of participants.
     */
    @Transactional(readOnly = true)
    public List<ParticipantAvailability> availability(final Long revivalId, final @Nullable Long eventId, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = participantAccess.visible(caller, revivalId);

        accessPolicy.requireReadParticipants(caller, revival);

        final Map<Long, Event> events = events(revivalId, eventId);
        final Map<Long, List<Availability>> answers = (events.isEmpty() ? List.<Availability>of() : availabilityRepository.findByEventIdIn(events.keySet()))
                .stream().collect(Collectors.groupingBy(Availability::getParticipantId));
        final List<Participant> participants = participantRepository.findAllById(answers.keySet()).stream()
                .filter(participant -> !participant.isRemoved() && participant.getRevivalId().equals(revivalId))
                .toList();
        final Map<Long, ParticipantDirectory.Named> names = directory.named(participants, ParticipantDirectory.actingPerson(caller), locale);

        return participants.stream()
                .map(participant -> new ParticipantAvailability(ParticipantRef.of(names.get(participant.getId())),
                        answers.get(participant.getId()).stream()
                                .sorted(Comparator.comparing(Availability::getDay).thenComparing(Availability::getEventId))
                                .map(AvailabilityDay::of)
                                .toList()))
                .sorted(Comparator.comparing((ParticipantAvailability row) -> row.participant().displayName().toLowerCase(Locale.ROOT))
                        .thenComparing(row -> row.participant().id()))
                .toList();
    }

    /**
     * The slots of the revival's events that are not cancelled, or of one of them, by date and time. Readers of
     * staffing.
     */
    @Transactional(readOnly = true)
    public List<ShiftSlotDto> slots(final Long revivalId, final @Nullable Long eventId, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = participantAccess.visible(caller, revivalId);

        accessPolicy.requireReadStaffing(caller, revival);

        final Map<Long, Event> events = events(revivalId, eventId);

        return toDtos(events.isEmpty() ? List.of() : slotRepository.findByEventIdIn(events.keySet()), revival, events, caller, locale);
    }

    /**
     * The revival's events that are not cancelled, by id; only {@code eventId} when given, which must be one of them.
     */
    private Map<Long, Event> events(final Long revivalId, final @Nullable Long eventId) {
        final Map<Long, Event> events = eventRepository.findByRevivalId(revivalId).stream()
                .filter(event -> !event.isCancelled())
                .filter(event -> eventId == null || event.getId().equals(eventId))
                .collect(Collectors.toMap(Event::getId, Function.identity()));

        if (eventId != null && events.isEmpty()) {
            throw new ResourceNotFoundException(Event.class, eventId);
        }
        return events;
    }

    // --- The participant's own --------------------------------------------------------------------------------------

    /**
     * The events a participant sees: the revival's that are not cancelled; for a helper only those they hold shifts in
     * (M6B-FR-7). By start date.
     */
    @Transactional(readOnly = true)
    public List<EventDto> eventsOf(final Long participantId) {
        final Participant participant = participant(participantId);
        final Revival revival = revivalRepository.findById(participant.getRevivalId()).orElseThrow();

        return ownEvents(participant).stream()
                .sorted(Comparator.comparing(Event::getStartDate).thenComparing(Event::getStartTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Event::getId))
                .map(event -> EventDto.of(event, revival.getPremiereDate()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AvailabilityDay> availabilityOf(final Long participantId) {
        final Participant participant = participant(participantId);
        final Set<Long> events = ownEvents(participant).stream().map(Event::getId).collect(Collectors.toSet());

        return availabilityRepository.findByParticipantId(participantId).stream()
                .filter(availability -> events.contains(availability.getEventId()))
                .sorted(Comparator.comparing(Availability::getDay).thenComparing(Availability::getEventId))
                .map(AvailabilityDay::of)
                .toList();
    }

    /**
     * The slots the participant holds in events that are not cancelled.
     */
    @Transactional(readOnly = true)
    public List<ShiftSlotDto> shiftsOf(final Long participantId, final Locale locale) {
        final Participant participant = participant(participantId);
        final Revival revival = revivalRepository.findById(participant.getRevivalId()).orElseThrow();
        final Map<Long, Event> events = activeEvents(revival.getId());
        final List<Long> held = assignmentRepository.findByParticipantId(participantId).stream().map(ShiftAssignment::getSlotId).toList();

        return toDtos(slotRepository.findAllById(held).stream().filter(slot -> events.containsKey(slot.getEventId())).toList(), revival, events,
                callerService.current(), locale);
    }

    /**
     * Slots with room that a participant who said yes or maybe may sign up for: not held by them, in events that are
     * not cancelled, from today on. None for helpers and for anyone else.
     */
    @Transactional(readOnly = true)
    public List<ShiftSlotDto> openShiftsOf(final Long participantId, final Locale locale) {
        final Participant participant = participant(participantId);

        if (!maySignUp(participant)) {
            return List.of();
        }

        final Revival revival = revivalRepository.findById(participant.getRevivalId()).orElseThrow();
        final Map<Long, Event> events = activeEvents(revival.getId());
        final LocalDate today = LocalDate.now(clock);
        final List<ShiftSlot> slots = events.isEmpty() ? List.of()
                : slotRepository.findByEventIdIn(events.keySet()).stream().filter(slot -> !slot.getDay().isBefore(today)).toList();

        return toDtos(slots, revival, events, callerService.current(), locale).stream()
                .filter(slot -> slot.open() > 0 && slot.assigned().stream().noneMatch(assigned -> assigned.participant().id().equals(participantId)))
                .toList();
    }

    private Participant participant(final Long participantId) {
        return participantRepository.findById(participantId).orElseThrow(() -> new ResourceNotFoundException(Participant.class, participantId));
    }

    private static boolean maySignUp(final Participant participant) {
        return participant.getKind() == ParticipantKind.PARTICIPANT && !participant.isRemoved() && COMING.contains(participant.getResponse());
    }

    private Map<Long, Event> activeEvents(final Long revivalId) {
        return eventRepository.findByRevivalId(revivalId).stream().filter(event -> !event.isCancelled())
                .collect(Collectors.toMap(Event::getId, Function.identity()));
    }

    /**
     * A participant's events that are not cancelled: all of the revival's, a helper only those they hold shifts in.
     */
    private List<Event> ownEvents(final Participant participant) {
        final List<Event> events = eventRepository.findByRevivalId(participant.getRevivalId()).stream().filter(event -> !event.isCancelled()).toList();

        if (participant.getKind() != ParticipantKind.HELPER) {
            return events;
        }

        final Set<Long> withShifts = slotRepository.findAllById(assignmentRepository.findByParticipantId(participant.getId()).stream()
                .map(ShiftAssignment::getSlotId).toList()).stream().map(ShiftSlot::getEventId).collect(Collectors.toSet());

        return events.stream().filter(event -> withShifts.contains(event.getId())).toList();
    }

    /**
     * The caller's answer for a day of an event they see (M6B-FR-1): revival {@code OPEN}, not after answering no, the
     * day within the event. Clearing it deletes the answer.
     */
    @Transactional
    public Long setAvailability(final AvailabilityInput input) {
        final Caller caller = callerService.current();
        final ParticipantAccess.Own own = participantAccess.own(caller, input.revivalId());
        final Participant participant = own.participant();
        final Event event = ownEvents(participant).stream().filter(candidate -> candidate.getId().equals(input.eventId())).findFirst()
                .orElseThrow(() -> new BadRequestException("error.availability.event"));

        if (participant.getResponse() == ParticipantResponse.NO) {
            throw new BadRequestException("error.availability.declined");
        }
        if (input.date().isBefore(event.getStartDate()) || input.date().isAfter(event.getEndDate())) {
            throw new BadRequestException("error.availability.day");
        }

        final String comment = text(input.comment(), Availability.COMMENT_MAX_LENGTH, "error.availability.comment.tooLong");
        final Availability existing = availabilityRepository.findByParticipantIdAndEventIdAndDay(participant.getId(), event.getId(), input.date())
                .orElse(null);

        AuditContext.operation("availabilitySet");
        if (input.answer() == null) {
            if (existing != null) {
                availabilityRepository.delete(existing);
                availabilityRepository.flush();
            }
            return participant.getId();
        }

        final Availability availability = existing != null ? existing : new Availability();

        availability.setParticipantId(participant.getId());
        availability.setEventId(event.getId());
        availability.setDay(input.date());
        availability.setAnswer(input.answer());
        availability.setComment(comment);
        availabilityRepository.saveAndFlush(availability);
        return participant.getId();
    }

    /**
     * The caller, a participant who said yes or maybe, signs up for a slot with room (M6B-FR-4). Revival {@code OPEN}.
     */
    @Transactional
    public ShiftSlotDto signUp(final Long slotId, final Locale locale) {
        final Caller caller = callerService.current();
        final ShiftSlot slot = slotRepository.findById(slotId).orElseThrow(() -> new ResourceNotFoundException(ShiftSlot.class, slotId));
        final ParticipantAccess.Own own = participantAccess.own(caller, slot.getRevivalId());
        final Participant participant = own.participant();
        final Event event = activeEvent(slot);

        if (participant.getKind() != ParticipantKind.PARTICIPANT) {
            throw new AccessDeniedException("Not permitted");
        }
        if (!COMING.contains(participant.getResponse())) {
            throw new BadRequestException("error.shift.signUp.response");
        }
        if (assignmentRepository.findBySlotIdAndParticipantId(slotId, participant.getId()).isEmpty()) {
            if (holders(slot) >= slot.getNeeded()) {
                throw new BadRequestException("error.shift.full");
            }
            requireNoOverlap(slot, participant);
            AuditContext.operation("shiftSignUp");
            save(slot, participant, true, null);
        }
        return toDto(slot, own.revival(), event, caller, locale);
    }

    /**
     * The caller withdraws from a slot they signed up for themselves; one they were assigned to stays (M6B-FR-4).
     */
    @Transactional
    public ShiftSlotDto withdraw(final Long slotId, final Locale locale) {
        final Caller caller = callerService.current();
        final ShiftSlot slot = slotRepository.findById(slotId).orElseThrow(() -> new ResourceNotFoundException(ShiftSlot.class, slotId));
        final ParticipantAccess.Own own = participantAccess.own(caller, slot.getRevivalId());
        final ShiftAssignment assignment = assignmentRepository.findBySlotIdAndParticipantId(slotId, own.participant().getId())
                .orElseThrow(() -> new BadRequestException("error.shift.withdraw.notHeld"));

        if (!assignment.isSelfSignedUp()) {
            throw new BadRequestException("error.shift.withdraw.assigned");
        }
        AuditContext.operation("shiftWithdraw");
        assignmentRepository.delete(assignment);
        assignmentRepository.flush();
        return toDto(slot, own.revival(), eventRepository.findById(slot.getEventId()).orElseThrow(), caller, locale);
    }

    // --- Writers of staffing ----------------------------------------------------------------------------------------

    /**
     * A new slot in an event that is not cancelled; the event's revival is the slot's.
     */
    @Transactional
    public ShiftSlotDto create(final SlotInput input, final Locale locale) {
        final Caller caller = callerService.current();

        if (input.eventId() == null) {
            throw new BadRequestException("error.shift.event");
        }

        final Long eventId = input.eventId();
        final Event event = eventRepository.findById(eventId).orElseThrow(() -> new ResourceNotFoundException(Event.class, eventId));
        final Revival revival = writable(caller, event.getRevivalId());

        if (event.isCancelled()) {
            throw new BadRequestException("error.shift.eventCancelled");
        }

        final ShiftSlot slot = new ShiftSlot();

        AuditContext.operation("shiftSlotCreate");
        slot.setRevivalId(revival.getId());
        slot.setEventId(event.getId());
        define(slot, event, input);
        return toDto(slotRepository.saveAndFlush(slot), revival, event, caller, locale);
    }

    @Transactional
    public ShiftSlotDto update(final Long id, final Long version, final SlotInput input, final Locale locale) {
        final Caller caller = callerService.current();
        final ShiftSlot slot = changeable(caller, id, version);
        final Revival revival = revivalRepository.findById(slot.getRevivalId()).orElseThrow();
        final Event event = activeEvent(slot);

        AuditContext.operation("shiftSlotUpdate");
        define(slot, event, input);
        return toDto(slotRepository.saveAndFlush(slot), revival, event, caller, locale);
    }

    /**
     * Removes a slot nobody holds: an empty planning row (M6B-FR-2).
     */
    @Transactional
    public void remove(final Long id, final Long version) {
        final ShiftSlot slot = changeable(callerService.current(), id, version);

        if (assignmentRepository.existsBySlotId(id)) {
            throw new BadRequestException("error.shift.notEmpty");
        }
        AuditContext.operation("shiftSlotRemove");
        slotRepository.delete(slot);
        slotRepository.flush();
    }

    /**
     * Assigns a participant who said yes or maybe, or a helper who did not say no, to a slot (M6B-FR-3). Writers may
     * assign beyond the number needed; nobody holds two overlapping slots.
     */
    @Transactional
    public ShiftSlotDto assign(final Long slotId, final Long participantId, final Locale locale) {
        final Caller caller = callerService.current();
        final ShiftSlot slot = writableSlot(caller, slotId);
        final Revival revival = revivalRepository.findById(slot.getRevivalId()).orElseThrow();
        final Event event = activeEvent(slot);
        final Participant participant = participantRepository.findById(participantId)
                .filter(candidate -> candidate.getRevivalId().equals(slot.getRevivalId()))
                .orElseThrow(() -> new ResourceNotFoundException(Participant.class, participantId));

        if (participant.isRemoved()) {
            throw new BadRequestException("error.participant.removed");
        }

        final boolean eligible = participant.getKind() == ParticipantKind.HELPER ? participant.getResponse() != ParticipantResponse.NO
                : COMING.contains(participant.getResponse());

        if (!eligible) {
            throw new BadRequestException("error.shift.assign.response");
        }
        if (assignmentRepository.findBySlotIdAndParticipantId(slotId, participantId).isEmpty()) {
            requireNoOverlap(slot, participant);
            AuditContext.operation("shiftAssign");
            save(slot, participant, false, caller.person().getId());
        }
        return toDto(slot, revival, event, caller, locale);
    }

    @Transactional
    public ShiftSlotDto unassign(final Long slotId, final Long participantId, final Locale locale) {
        final Caller caller = callerService.current();
        final ShiftSlot slot = writableSlot(caller, slotId);
        final Revival revival = revivalRepository.findById(slot.getRevivalId()).orElseThrow();

        assignmentRepository.findBySlotIdAndParticipantId(slotId, participantId).ifPresent(assignment -> {
            AuditContext.operation("shiftUnassign");
            assignmentRepository.delete(assignment);
            assignmentRepository.flush();
        });
        return toDto(slot, revival, eventRepository.findById(slot.getEventId()).orElseThrow(), caller, locale);
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    private Revival writable(final Caller caller, final Long revivalId) {
        final Revival revival = participantAccess.visible(caller, revivalId);

        accessPolicy.requireReadStaffing(caller, revival);
        accessPolicy.requireWriteStaffing(caller, revival);
        return revival;
    }

    /**
     * A slot of a revival whose staffing the caller writes; missing when they cannot read it.
     */
    private ShiftSlot writableSlot(final Caller caller, final Long id) {
        final ShiftSlot slot = slotRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(ShiftSlot.class, id));
        final Revival revival = revivalRepository.findById(slot.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canSee(caller, revival) || !accessPolicy.canReadStaffing(caller, revival)) {
            throw new ResourceNotFoundException(ShiftSlot.class, id);
        }
        accessPolicy.requireWriteStaffing(caller, revival);
        return slot;
    }

    private ShiftSlot changeable(final Caller caller, final Long id, final Long version) {
        final ShiftSlot slot = writableSlot(caller, id);

        if (!Objects.equals(version, slot.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return slot;
    }

    private Event activeEvent(final ShiftSlot slot) {
        final Event event = eventRepository.findById(slot.getEventId()).orElseThrow();

        if (event.isCancelled()) {
            throw new BadRequestException("error.shift.eventCancelled");
        }
        return event;
    }

    private void define(final ShiftSlot slot, final Event event, final SlotInput input) {
        if (input.date().isBefore(event.getStartDate()) || input.date().isAfter(event.getEndDate())) {
            throw new BadRequestException("error.shift.day");
        }

        final LocalTime start = time(input.startTime());
        final LocalTime end = time(input.endTime());

        if (!start.isBefore(end)) {
            throw new BadRequestException("error.shift.endBeforeStart");
        }

        final String task = input.task() == null ? "" : input.task().strip();

        if (task.isEmpty() || task.length() > ShiftSlot.TASK_MAX_LENGTH) {
            throw new BadRequestException("error.shift.task", ShiftSlot.TASK_MAX_LENGTH);
        }
        if (input.needed() < 1 || input.needed() > ShiftSlot.MAX_NEEDED) {
            throw new BadRequestException("error.shift.needed", ShiftSlot.MAX_NEEDED);
        }

        final boolean moved = slot.getId() != null && (!input.date().equals(slot.getDay()) || !start.equals(slot.getStartTime())
                || !end.equals(slot.getEndTime()));

        slot.setDay(input.date());
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setTask(task);
        slot.setNeeded(input.needed());
        slot.setComment(text(input.comment(), ShiftSlot.COMMENT_MAX_LENGTH, "error.shift.comment.tooLong"));
        if (moved) {
            final List<Long> holders = assignmentRepository.findBySlotIdIn(List.of(slot.getId())).stream().map(ShiftAssignment::getParticipantId).toList();

            participantRepository.findAllById(holders).forEach(participant -> requireNoOverlap(slot, participant));
        }
    }

    private static LocalTime time(final @Nullable String time) {
        final String trimmed = time == null ? "" : time.strip();

        if (!TIME.matcher(trimmed).matches()) {
            throw new BadRequestException("error.shift.time");
        }
        return LocalTime.parse(trimmed, TIME_FORMAT);
    }

    private static @Nullable String text(final @Nullable String text, final int maxLength, final String tooLong) {
        if (text == null || text.isBlank()) {
            return null;
        }

        final String trimmed = text.strip();

        if (trimmed.length() > maxLength) {
            throw new BadRequestException(tooLong, maxLength);
        }
        return trimmed;
    }

    /**
     * Refuses a slot that overlaps another the participant already holds on the same day, in any of the revival's
     * events (M6B-FR-3).
     */
    private void requireNoOverlap(final ShiftSlot slot, final Participant participant) {
        final List<Long> held = assignmentRepository.findByParticipantId(participant.getId()).stream()
                .map(ShiftAssignment::getSlotId)
                .filter(id -> !id.equals(slot.getId()))
                .toList();

        if (slotRepository.findAllById(held).stream().anyMatch(slot::overlaps)) {
            throw new BadRequestException("error.shift.overlap");
        }
    }

    private int holders(final ShiftSlot slot) {
        final List<Long> holders = assignmentRepository.findBySlotIdIn(List.of(slot.getId())).stream().map(ShiftAssignment::getParticipantId).toList();

        return (int) participantRepository.findAllById(holders).stream().filter(participant -> !participant.isRemoved()).count();
    }

    private void save(final ShiftSlot slot, final Participant participant, final boolean selfSignedUp, final @Nullable Long assignedBy) {
        final ShiftAssignment assignment = new ShiftAssignment();

        assignment.setSlotId(slot.getId());
        assignment.setParticipantId(participant.getId());
        assignment.setSelfSignedUp(selfSignedUp);
        assignment.setAssignedBy(assignedBy);
        assignmentRepository.saveAndFlush(assignment);
    }

    private ShiftSlotDto toDto(final ShiftSlot slot, final Revival revival, final Event event, final Caller caller, final Locale locale) {
        return toDtos(List.of(slot), revival, Map.of(event.getId(), event), caller, locale).getFirst();
    }

    /**
     * Slots with whoever holds them who is not removed, by name only.
     */
    List<ShiftSlotDto> toDtos(final Collection<ShiftSlot> slots, final Revival revival, final Map<Long, Event> events, final Caller caller,
                              final Locale locale) {
        if (slots.isEmpty()) {
            return List.of();
        }

        final List<ShiftAssignment> assignments = assignmentRepository.findBySlotIdIn(slots.stream().map(ShiftSlot::getId).toList());
        final List<Participant> participants = participantRepository.findAllById(assignments.stream().map(ShiftAssignment::getParticipantId)
                .collect(Collectors.toSet())).stream().filter(participant -> !participant.isRemoved()).toList();
        final Set<Long> active = participants.stream().map(Participant::getId).collect(Collectors.toCollection(HashSet::new));
        final Map<Long, ParticipantDirectory.Named> names = directory.named(participants, ParticipantDirectory.actingPerson(caller), locale);
        final Map<Long, List<ShiftAssignment>> bySlot = assignments.stream().filter(assignment -> active.contains(assignment.getParticipantId()))
                .collect(Collectors.groupingBy(ShiftAssignment::getSlotId));
        final List<ShiftSlotDto> dtos = new ArrayList<>();

        for (final ShiftSlot slot : slots.stream().sorted(ORDER).toList()) {
            final List<ShiftAssignmentDto> assigned = bySlot.getOrDefault(slot.getId(), List.of()).stream()
                    .map(assignment -> new ShiftAssignmentDto(ParticipantRef.of(names.get(assignment.getParticipantId())), assignment.isSelfSignedUp()))
                    .sorted(Comparator.comparing((ShiftAssignmentDto dto) -> dto.participant().displayName().toLowerCase(Locale.ROOT))
                            .thenComparing(dto -> dto.participant().id()))
                    .toList();
            final Event event = events.containsKey(slot.getEventId()) ? events.get(slot.getEventId())
                    : eventRepository.findById(slot.getEventId()).orElseThrow();

            dtos.add(new ShiftSlotDto(slot.getId(), EventDto.of(event, revival.getPremiereDate()), slot.getDay(), slot.getStartTime().format(TIME_FORMAT),
                    slot.getEndTime().format(TIME_FORMAT), slot.getTask(), slot.getNeeded(), slot.getComment(), assigned,
                    Math.max(0, slot.getNeeded() - assigned.size()), slot.getVersion() == null ? 0L : slot.getVersion()));
        }
        return dtos;
    }
}
