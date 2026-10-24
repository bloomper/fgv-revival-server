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

import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.util.Amounts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the revival's helpers are paid for the shifts they hold (M6B-FR-5, the M5 seam): the sum, over every shift a
 * helper who is not removed holds in an event that is not cancelled, of that helper's compensation per shift.
 * <p>
 * The budget treats this as a budgeted cost of the category with the {@code HELPERS} role. It is computed whenever the
 * budget is read and never stored as a budget line, so it always follows the staffing. No caller check: the budget
 * has checked that the caller reads it.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Component
public class HelperCompensation {

    private final ShiftSlotRepository slotRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final ParticipantRepository participantRepository;
    private final EventRepository eventRepository;

    public HelperCompensation(final ShiftSlotRepository slotRepository, final ShiftAssignmentRepository assignmentRepository,
                              final ParticipantRepository participantRepository, final EventRepository eventRepository) {
        this.slotRepository = slotRepository;
        this.assignmentRepository = assignmentRepository;
        this.participantRepository = participantRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * The total, rounded to öre; zero without helpers' shifts.
     */
    @Transactional(readOnly = true)
    public BigDecimal total(final Long revivalId) {
        final Set<Long> cancelled = eventRepository.findByRevivalId(revivalId).stream().filter(Event::isCancelled).map(Event::getId)
                .collect(Collectors.toSet());
        final List<Long> slots = slotRepository.findByRevivalId(revivalId).stream()
                .filter(slot -> !cancelled.contains(slot.getEventId()))
                .map(ShiftSlot::getId)
                .toList();

        if (slots.isEmpty()) {
            return Amounts.zero();
        }

        final List<ShiftAssignment> assignments = assignmentRepository.findBySlotIdIn(slots);
        final Map<Long, Participant> participants = participantRepository.findAllById(assignments.stream().map(ShiftAssignment::getParticipantId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Participant::getId, Function.identity()));

        return Amounts.round(assignments.stream()
                .map(assignment -> participants.get(assignment.getParticipantId()))
                .filter(participant -> participant != null && participant.getKind() == ParticipantKind.HELPER && !participant.isRemoved()
                        && participant.getCompensationPerShift() != null)
                .map(Participant::getCompensationPerShift)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
