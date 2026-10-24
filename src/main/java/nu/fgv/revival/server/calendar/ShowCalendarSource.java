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

import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalShow;
import nu.fgv.revival.server.revival.RevivalTitles;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The revival's shows, for everyone with a calendar link (M4-FR-6): two and a half hours from the start time, or all
 * day while the time is not decided.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@Component
class ShowCalendarSource implements CalendarSource {

    static final Duration DEFAULT_LENGTH = Duration.ofMinutes(150);

    private final RevivalTitles titles;
    private final MessageSource messageSource;
    private final Clock clock;

    ShowCalendarSource(final RevivalTitles titles, final MessageSource messageSource, final Clock clock) {
        this.titles = titles;
        this.messageSource = messageSource;
        this.clock = clock;
    }

    @Override
    public List<CalendarEntry> entries(final Revival revival, final Caller person, final Locale locale) {
        final String summary = messageSource.getMessage("calendar.show", new Object[]{titles.ofCached(revival, locale)}, locale);
        final Instant stamp = revival.getLastModifiedAt() != null ? revival.getLastModifiedAt() : revival.getCreatedAt();

        return revival.getShows().stream()
                .sorted(Comparator.comparing(RevivalShow::getDate).thenComparing(RevivalShow::getId))
                .map(show -> {
                    final CalendarEntry.When when;

                    if (show.getTime() == null) {
                        when = new CalendarEntry.AllDay(show.getDate(), show.getDate());
                    } else {
                        final Instant start = show.getDate().atTime(show.getTime()).atZone(clock.getZone()).toInstant();

                        when = new CalendarEntry.Timed(start, start.plus(DEFAULT_LENGTH));
                    }
                    return new CalendarEntry(CalendarEntry.uid("show", show.getId()), summary, show.getVenue(), null, when, stamp);
                })
                .toList();
    }
}
