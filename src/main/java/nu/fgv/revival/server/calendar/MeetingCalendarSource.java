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

import nu.fgv.revival.server.meeting.Meeting;
import nu.fgv.revival.server.meeting.MeetingRepository;
import nu.fgv.revival.server.meeting.MeetingStatus;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The steering group's meetings that are not cancelled, all day, for its directeurs and steering group only (M4-FR-6).
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@Component
class MeetingCalendarSource implements CalendarSource {

    private final MeetingRepository repository;
    private final AccessPolicy accessPolicy;
    private final MessageSource messageSource;

    MeetingCalendarSource(final MeetingRepository repository, final AccessPolicy accessPolicy, final MessageSource messageSource) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.messageSource = messageSource;
    }

    @Override
    public List<CalendarEntry> entries(final Revival revival, final Caller person, final Locale locale) {
        if (!accessPolicy.canSeeMeetingsInCalendar(person, revival)) {
            return List.of();
        }

        final String meeting = messageSource.getMessage("calendar.meeting", null, locale);

        return repository.findByRevivalIdOrderByDateDescIdDesc(revival.getId()).stream()
                .filter(candidate -> candidate.getStatus() != MeetingStatus.CANCELLED)
                .sorted(Comparator.comparing(Meeting::getDate).thenComparing(Meeting::getId))
                .map(candidate -> new CalendarEntry(CalendarEntry.uid("meeting", candidate.getId()),
                        candidate.getTitle() == null ? meeting : candidate.getTitle(), null, null,
                        new CalendarEntry.AllDay(candidate.getDate(), candidate.getDate()),
                        candidate.getLastModifiedAt() != null ? candidate.getLastModifiedAt() : candidate.getCreatedAt()))
                .toList();
    }
}
