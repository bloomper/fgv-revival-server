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

import nu.fgv.revival.server.calendar.CalendarEntry;
import nu.fgv.revival.server.calendar.CalendarSource;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A participant's or helper's own shifts in their calendar feed (M6B-FR-6): the task as the summary, from the start to
 * the end time on the day, at the event's location. Only shifts in events that are not cancelled. A helper's feed then
 * holds their shifts and the shows, nothing else.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Component
class ShiftCalendarSource implements CalendarSource {

    private final ShiftSlotRepository slotRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final ParticipantRepository participantRepository;
    private final RevivalMemberRepository memberRepository;
    private final EventRepository eventRepository;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    ShiftCalendarSource(final ShiftSlotRepository slotRepository, final ShiftAssignmentRepository assignmentRepository,
                        final ParticipantRepository participantRepository, final RevivalMemberRepository memberRepository,
                        final EventRepository eventRepository, final AccessPolicy accessPolicy, final Clock clock) {
        this.slotRepository = slotRepository;
        this.assignmentRepository = assignmentRepository;
        this.participantRepository = participantRepository;
        this.memberRepository = memberRepository;
        this.eventRepository = eventRepository;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    public List<CalendarEntry> entries(final Revival revival, final Caller person, final Locale locale) {
        if (!accessPolicy.canParticipate(person, revival)) {
            return List.of();
        }

        final Participant participant = memberRepository.findByRevivalIdAndPersonIdAndRemovedAtIsNull(revival.getId(), person.person().getId())
                .flatMap(member -> participantRepository.findByMemberIdAndRemovedAtIsNull(member.getId()))
                .orElse(null);

        if (participant == null) {
            return List.of();
        }

        final Map<Long, Event> events = eventRepository.findByRevivalId(revival.getId()).stream().filter(event -> !event.isCancelled())
                .collect(Collectors.toMap(Event::getId, Function.identity()));

        return slotRepository.findAllById(assignmentRepository.findByParticipantId(participant.getId()).stream()
                        .map(ShiftAssignment::getSlotId).toList()).stream()
                .filter(slot -> events.containsKey(slot.getEventId()))
                .sorted(Comparator.comparing(ShiftSlot::getDay).thenComparing(ShiftSlot::getStartTime).thenComparing(ShiftSlot::getId))
                .map(slot -> entry(slot, events.get(slot.getEventId())))
                .toList();
    }

    private CalendarEntry entry(final ShiftSlot slot, final Event event) {
        final Instant start = slot.getDay().atTime(slot.getStartTime()).atZone(clock.getZone()).toInstant();
        final Instant end = slot.getDay().atTime(slot.getEndTime()).atZone(clock.getZone()).toInstant();

        return new CalendarEntry(CalendarEntry.uid("shift", slot.getId()), slot.getTask(), event.getLocation(),
                slot.getComment() == null ? event.getTitle() : event.getTitle() + "\n" + slot.getComment(), new CalendarEntry.Timed(start, end),
                slot.getLastModifiedAt() != null ? slot.getLastModifiedAt() : slot.getCreatedAt());
    }
}
