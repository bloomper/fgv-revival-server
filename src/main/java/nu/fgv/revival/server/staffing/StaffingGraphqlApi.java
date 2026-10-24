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

import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.participant.ParticipantDtos.Participation;
import nu.fgv.revival.server.participant.ParticipationService;
import nu.fgv.revival.server.staffing.StaffingDtos.AvailabilityDay;
import nu.fgv.revival.server.staffing.StaffingDtos.ParticipantAvailability;
import nu.fgv.revival.server.staffing.StaffingDtos.ShiftSlotDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Availability and staffing over GraphQL (staffing.graphqls). A thin adapter: every rule is in {@link StaffingService}.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Controller
public class StaffingGraphqlApi {

    private final StaffingService service;
    private final ParticipationService participation;

    public StaffingGraphqlApi(final StaffingService service, final ParticipationService participation) {
        this.service = service;
        this.participation = participation;
    }

    @QueryMapping
    public List<ParticipantAvailability> availability(@Argument final Long revivalId, @Argument final @Nullable Long eventId, final Locale locale) {
        return service.availability(revivalId, eventId, locale);
    }

    @QueryMapping
    public List<ShiftSlotDto> shiftSlots(@Argument final Long revivalId, @Argument final @Nullable Long eventId, final Locale locale) {
        return service.slots(revivalId, eventId, locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftSlotCreate(@Argument final ShiftSlotInput input, final Locale locale) {
        return service.create(input.toInput(), locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftSlotUpdate(@Argument final Long id, @Argument final Long version, @Argument final ShiftSlotInput input, final Locale locale) {
        return service.update(id, version, input.toInput(), locale);
    }

    @MutationMapping
    public void shiftSlotRemove(@Argument final Long id, @Argument final Long version) {
        service.remove(id, version);
    }

    @MutationMapping
    public ShiftSlotDto shiftAssign(@Argument final Long slotId, @Argument final Long participantId, final Locale locale) {
        return service.assign(slotId, participantId, locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftUnassign(@Argument final Long slotId, @Argument final Long participantId, final Locale locale) {
        return service.unassign(slotId, participantId, locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftSignUp(@Argument final Long slotId, final Locale locale) {
        return service.signUp(slotId, locale);
    }

    @MutationMapping
    public ShiftSlotDto shiftWithdraw(@Argument final Long slotId, final Locale locale) {
        return service.withdraw(slotId, locale);
    }

    @MutationMapping
    public Participation availabilitySet(@Argument final AvailabilityInput input, final Locale locale) {
        service.setAvailability(new StaffingService.AvailabilityInput(input.revivalId(), input.eventId(), input.date(), input.answer(),
                input.comment()));
        return participation.mine(input.revivalId(), locale);
    }

    @SchemaMapping(typeName = "Participation", field = "events")
    public List<EventDto> events(final Participation source) {
        return service.eventsOf(source.participant().id());
    }

    @SchemaMapping(typeName = "Participation", field = "availability")
    public List<AvailabilityDay> availability(final Participation source) {
        return service.availabilityOf(source.participant().id());
    }

    // --- Participation ----------------------------------------------------------------------------------------------

    @SchemaMapping(typeName = "Participation", field = "shifts")
    public List<ShiftSlotDto> shifts(final Participation source, final Locale locale) {
        return service.shiftsOf(source.participant().id(), locale);
    }

    @SchemaMapping(typeName = "Participation", field = "openShifts")
    public List<ShiftSlotDto> openShifts(final Participation source, final Locale locale) {
        return service.openShiftsOf(source.participant().id(), locale);
    }

    public record ShiftSlotInput(@Nullable Long eventId, LocalDate date, String startTime, String endTime, String task,
                                 int needed,
                                 @Nullable String comment) {

        StaffingService.SlotInput toInput() {
            return new StaffingService.SlotInput(eventId, date, startTime, endTime, task, needed, comment);
        }
    }

    public record AvailabilityInput(Long revivalId, Long eventId, LocalDate date, @Nullable AvailabilityAnswer answer,
                                    @Nullable String comment) {
    }
}
