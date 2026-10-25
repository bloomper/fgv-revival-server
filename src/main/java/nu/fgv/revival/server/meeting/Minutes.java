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
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;

/**
 * A meeting's minutes, as produced when it closed and never changed afterwards. Language-neutral:
 * people are ids, rendered by display name when read.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record Minutes(List<Row> decisions, List<Row> todos, List<Row> questions, List<Row> closed,
                      List<String> agendaPoints, List<Long> attendeeIds, @Nullable String othersPresent,
                      @Nullable Long recorderId) {

    public Minutes {
        decisions = List.copyOf(decisions);
        todos = List.copyOf(todos);
        questions = List.copyOf(questions);
        closed = List.copyOf(closed);
        agendaPoints = List.copyOf(agendaPoints);
        attendeeIds = List.copyOf(attendeeIds);
    }

    /**
     * A row as it was when the meeting closed.
     */
    public record Row(Long entryId, int number, LogEntryType type, String text, @Nullable Long ownerPersonId,
                      @Nullable String ownerName, @Nullable LocalDate deadline, LogEntryStatus status) {
    }
}
