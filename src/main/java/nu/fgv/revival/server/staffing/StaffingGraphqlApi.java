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
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantDtos.Participation;
import nu.fgv.revival.server.participant.ParticipationService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.staffing.StaffingDtos.AvailabilityDay;
import nu.fgv.revival.server.staffing.StaffingDtos.ParticipantAvailability;
import nu.fgv.revival.server.staffing.StaffingDtos.ShiftSlotDto;
import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Availability and staffing over GraphQL (staffing.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class StaffingGraphqlApi {

    private final ExternalIds ids;

    private final StaffingService service;
    private final ParticipationService participation;

    public StaffingGraphqlApi(final StaffingService service, final ParticipationService participation, final ExternalIds ids) {
        this.ids = ids;
        this.service = service;
        this.participation = participation;
    }

    @QueryMapping
    public List<ParticipantAvailability> availability(@Argument final UUID revivalId, @Argument final @Nullable UUID eventId, final Locale locale) {
        return service.availability(ids.idOf(Revival.class, revivalId), ids.idOfNullable(Event.class, eventId), locale);
    }

    @QueryMapping
    public List<ShiftSlotDto> shiftSlots(@Argument final UUID revivalId, @Argument final @Nullable UUID eventId, final Locale locale) {
        return service.slots(ids.idOf(Revival.class, revivalId), ids.idOfNullable(Event.class, eventId), locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftSlotCreate(@Argument final ShiftSlotInput input, final Locale locale) {
        return service.create(input.toInput(ids), locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftSlotUpdate(@Argument final UUID id, @Argument final Long version, @Argument final ShiftSlotInput input, final Locale locale) {
        return service.update(ids.idOf(ShiftSlot.class, id), version, input.toInput(ids), locale);
    }

    @MutationMapping
    public void shiftSlotRemove(@Argument final UUID id, @Argument final Long version) {
        service.remove(ids.idOf(ShiftSlot.class, id), version);
    }

    @MutationMapping
    public ShiftSlotDto shiftAssign(@Argument final UUID slotId, @Argument final UUID participantId, final Locale locale) {
        return service.assign(ids.idOf(ShiftSlot.class, slotId), ids.idOf(Participant.class, participantId), locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftUnassign(@Argument final UUID slotId, @Argument final UUID participantId, final Locale locale) {
        return service.unassign(ids.idOf(ShiftSlot.class, slotId), ids.idOf(Participant.class, participantId), locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftSignUp(@Argument final UUID slotId, final Locale locale) {
        return service.signUp(ids.idOf(ShiftSlot.class, slotId), locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftWithdraw(@Argument final UUID slotId, final Locale locale) {
        return service.withdraw(ids.idOf(ShiftSlot.class, slotId), locale);
    }

    @MutationMapping
    public Participation availabilitySet(@Argument final AvailabilityInput input, final Locale locale) {
        service.setAvailability(new StaffingService.AvailabilityInput(ids.idOf(Revival.class, input.revivalId()), ids.idOfNullable(Event.class, input.eventId()), input.date(), input.answer(),
                input.comment()));
        return participation.mine(ids.idOf(Revival.class, input.revivalId()), locale);
    }

    @SchemaMapping(typeName = "Participation", field = "events")
    public List<EventDto> events(final Participation source) {
        return service.eventsOf(ids.idOf(Participant.class, source.participant().id()));
    }

    @SchemaMapping(typeName = "Participation", field = "availability")
    public List<AvailabilityDay> availability(final Participation source) {
        return service.availabilityOf(ids.idOf(Participant.class, source.participant().id()));
    }

    @SchemaMapping(typeName = "Participation", field = "shifts")
    public List<ShiftSlotDto> shifts(final Participation source, final Locale locale) {
        return service.shiftsOf(ids.idOf(Participant.class, source.participant().id()), locale);
    }

    @SchemaMapping(typeName = "Participation", field = "openShifts")
    public List<ShiftSlotDto> openShifts(final Participation source, final Locale locale) {
        return service.openShiftsOf(ids.idOf(Participant.class, source.participant().id()), locale);
    }

    public record ShiftSlotInput(@Nullable UUID eventId, LocalDate date, String startTime, String endTime, String task,
                                 int needed,
                                 @Nullable String comment) {

        StaffingService.SlotInput toInput(final ExternalIds ids) {
            return new StaffingService.SlotInput(ids.idOfNullable(Event.class, eventId), date, startTime, endTime, task, needed, comment);
        }
    }

    public record AvailabilityInput(UUID revivalId, UUID eventId, LocalDate date, @Nullable AvailabilityAnswer answer,
                                    @Nullable String comment) {
    }
}
