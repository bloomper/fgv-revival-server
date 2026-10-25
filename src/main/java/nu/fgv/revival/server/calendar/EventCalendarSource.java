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


package nu.fgv.revival.server.calendar;

import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The revival's events that are not cancelled, for everyone with a calendar link who reads them:
 * helpers get their shifts instead. All day without a start time, otherwise from the start time to the end time, or
 * two hours without one.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
class EventCalendarSource implements CalendarSource {

    static final Duration DEFAULT_LENGTH = Duration.ofHours(2);

    private final EventRepository repository;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    EventCalendarSource(final EventRepository repository, final AccessPolicy accessPolicy, final Clock clock) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    public List<CalendarEntry> entries(final Revival revival, final Caller person, final Locale locale) {
        if (!accessPolicy.canReadEvents(person, revival)) {
            return List.of();
        }
        return repository.findByRevivalId(revival.getId()).stream()
                .filter(event -> !event.isCancelled())
                .sorted(Comparator.comparing(Event::getStartDate).thenComparing(Event::getId))
                .map(this::entry)
                .toList();
    }

    private CalendarEntry entry(final Event event) {
        final CalendarEntry.When when;

        if (event.getStartTime() == null) {
            when = new CalendarEntry.AllDay(event.getStartDate(), event.getEndDate());
        } else {
            final Instant start = event.getStartDate().atTime(event.getStartTime()).atZone(clock.getZone()).toInstant();

            when = new CalendarEntry.Timed(start, event.getEndTime() == null ? start.plus(DEFAULT_LENGTH)
                    : event.getEndDate().atTime(event.getEndTime()).atZone(clock.getZone()).toInstant());
        }
        return new CalendarEntry(CalendarEntry.uid("event", event.getId()), event.getTitle(), event.getLocation(), event.getDescription(), when,
                event.getLastModifiedAt() != null ? event.getLastModifiedAt() : event.getCreatedAt());
    }
}
