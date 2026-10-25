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


package nu.fgv.revival.server.event;

import org.jspecify.annotations.Nullable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Dates proposed from the premiere for the types a revival has no event of yet. A pure function: the caller
 * passes the active types and the types of the revival's events that are not cancelled.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class EventProposals {

    private EventProposals() {
    }

    /**
     * For every type with offsets that has no event: the range the offsets allow, and a start date in it. The start is
     * the middle of the range, rounded toward the earliest; an event of two days or more over a range moves to the
     * nearest start whose last day is a Sunday, the earlier one on a tie, and stays in the middle when there is none.
     * Ordered by start date. None without a premiere.
     */
    public static List<EventProposalDto> propose(final @Nullable LocalDate premiere, final List<EventTypeDto> types,
                                                 final Collection<Long> typesWithEvents) {
        if (premiere == null) {
            return List.of();
        }
        return types.stream()
                .filter(type -> !type.retired() && type.offsetMinDays() != null && type.offsetMaxDays() != null)
                .filter(type -> !typesWithEvents.contains(type.id()))
                .map(type -> propose(premiere, type))
                .sorted(Comparator.comparing(EventProposalDto::startDate).thenComparing(proposal -> proposal.type().position()))
                .toList();
    }

    static EventProposalDto propose(final LocalDate premiere, final EventTypeDto type) {
        final int min = Objects.requireNonNull(type.offsetMinDays());
        final int max = Objects.requireNonNull(type.offsetMaxDays());
        final LocalDate earliest = premiere.plusDays(min);
        final LocalDate latest = premiere.plusDays(max);
        final LocalDate middle = premiere.plusDays(Math.floorDiv(min + max, 2));
        final LocalDate start = type.days() >= 2 && min < max ? endingOnSunday(middle, earliest, latest, type.days()) : middle;

        return new EventProposalDto(type, start, start.plusDays(type.days() - 1L), earliest, latest);
    }

    private static LocalDate endingOnSunday(final LocalDate middle, final LocalDate earliest, final LocalDate latest, final int days) {
        for (int distance = 0; !middle.minusDays(distance).isBefore(earliest) || !middle.plusDays(distance).isAfter(latest); distance++) {
            final LocalDate before = middle.minusDays(distance);
            final LocalDate after = middle.plusDays(distance);

            if (!before.isBefore(earliest) && endsOnSunday(before, days)) {
                return before;
            }
            if (!after.isAfter(latest) && endsOnSunday(after, days)) {
                return after;
            }
        }
        return middle;
    }

    private static boolean endsOnSunday(final LocalDate start, final int days) {
        return start.plusDays(days - 1L).getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
