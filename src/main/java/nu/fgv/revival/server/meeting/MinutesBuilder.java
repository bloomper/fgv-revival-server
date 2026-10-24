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

import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryType;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Builds a meeting's minutes from what was recorded in it and the rows as they are now (FR-020, research R8):
 * decisions and questions created in it, to-dos created or assigned in it, and rows closed in it. Changes made to rows
 * outside the meeting are not part of it.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
final class MinutesBuilder {

    private MinutesBuilder() {
    }

    static Minutes build(final Meeting meeting, final Collection<MeetingEntryChange> changes, final Map<Long, LogEntry> entries) {
        final Map<Long, Set<MeetingEntryChangeKind>> kinds = changes.stream().collect(Collectors.groupingBy(MeetingEntryChange::getEntryId,
                Collectors.mapping(MeetingEntryChange::getKind, Collectors.toCollection(() -> EnumSet.noneOf(MeetingEntryChangeKind.class)))));

        return new Minutes(
                rows(kinds, entries, (entry, entryKinds) -> entry.getType() == LogEntryType.DECISION && entryKinds.contains(MeetingEntryChangeKind.CREATED)),
                rows(kinds, entries, (entry, entryKinds) -> entry.getType() == LogEntryType.TODO
                        && (entryKinds.contains(MeetingEntryChangeKind.CREATED) || entryKinds.contains(MeetingEntryChangeKind.ASSIGNED))),
                rows(kinds, entries, (entry, entryKinds) -> entry.getType() == LogEntryType.QUESTION && entryKinds.contains(MeetingEntryChangeKind.CREATED)),
                rows(kinds, entries, (entry, entryKinds) -> entryKinds.contains(MeetingEntryChangeKind.CLOSED)),
                List.copyOf(meeting.getAgendaPoints()),
                List.copyOf(meeting.getAttendeeIds()),
                meeting.getOthersPresent(),
                meeting.getRecorderPersonId());
    }

    private static List<Minutes.Row> rows(final Map<Long, Set<MeetingEntryChangeKind>> kinds, final Map<Long, LogEntry> entries, final Rule rule) {
        final Predicate<Long> included = id -> entries.containsKey(id) && rule.test(entries.get(id), kinds.get(id));

        return kinds.keySet().stream()
                .filter(included)
                .map(entries::get)
                .sorted(Comparator.comparingInt(LogEntry::getNumber))
                .map(entry -> new Minutes.Row(entry.getId(), entry.getNumber(), entry.getType(), entry.getText(),
                        entry.getOwner().getPersonId(), entry.getOwner().getName(), entry.getDeadline(), entry.getStatus()))
                .toList();
    }

    private interface Rule {
        boolean test(LogEntry entry, Set<MeetingEntryChangeKind> kinds);
    }
}
