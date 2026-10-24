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
import nu.fgv.revival.server.participant.ParticipantDirectory;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.spex.SpexareDirectory;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;

/**
 * The GraphQL types of availability and staffing (staffing.graphqls). Participants appear by name and position only,
 * never with contact details.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
public final class StaffingDtos {

    private StaffingDtos() {
    }

    public record ParticipantRef(Long id, String displayName, ParticipantKind kind, SpexareDirectory.@Nullable Position position) {

        public static ParticipantRef of(final ParticipantDirectory.Named named) {
            return new ParticipantRef(named.id(), named.displayName(), named.kind(), named.position());
        }
    }

    public record AvailabilityDay(Long eventId, LocalDate date, AvailabilityAnswer answer, @Nullable String comment) {

        static AvailabilityDay of(final Availability availability) {
            return new AvailabilityDay(availability.getEventId(), availability.getDay(), availability.getAnswer(), availability.getComment());
        }
    }

    public record ParticipantAvailability(ParticipantRef participant, List<AvailabilityDay> days) {
    }

    public record ShiftAssignmentDto(ParticipantRef participant, boolean selfSignedUp) {
    }

    public record ShiftSlotDto(Long id, EventDto event, LocalDate date, String startTime, String endTime, String task, int needed,
                               @Nullable String comment, List<ShiftAssignmentDto> assigned, int open, Long version) {
    }
}
