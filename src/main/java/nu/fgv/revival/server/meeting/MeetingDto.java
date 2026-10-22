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

import nu.fgv.revival.server.decisionlog.LogEntryStatus;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.PersonRefDto;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A meeting as GraphQL's {@code Meeting}, with people as display names and the minutes ready to show.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public record MeetingDto(Long id,
                         Long revivalId,
                         LocalDate date,
                         @Nullable String title,
                         MeetingStatus status,
                         List<PersonRefDto> attendees,
                         @Nullable String othersPresent,
                         List<String> agendaPoints,
                         @Nullable PersonRefDto recorder,
                         @Nullable Instant startedAt,
                         @Nullable Instant closedAt,
                         @Nullable MinutesDto minutes,
                         Long version) {

    /**
     * GraphQL's {@code Minutes}.
     */
    public record MinutesDto(List<RowDto> decisions, List<RowDto> todos, List<RowDto> questions, List<RowDto> closed,
                             List<String> agendaPoints, List<PersonRefDto> attendees, @Nullable String othersPresent,
                             @Nullable PersonRefDto recorder) {
    }

    /**
     * GraphQL's {@code MinutesRow}: a row as it was when the meeting closed.
     */
    public record RowDto(Long entryId, int number, LogEntryType type, String text, @Nullable OwnerDto owner,
                         @Nullable LocalDate deadline, LogEntryStatus status) {
    }
}
