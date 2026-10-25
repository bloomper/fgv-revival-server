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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * An event as GraphQL's {@code Event}, with times as {@code HH:mm}. Its type and checklist are resolved from ids.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record EventDto(Long id,
                       Long revivalId,
                       @Nullable Long typeId,
                       String title,
                       LocalDate startDate,
                       LocalDate endDate,
                       @Nullable String startTime,
                       @Nullable String endTime,
                       @Nullable String location,
                       @Nullable String description,
                       boolean revivalPays,
                       @Nullable Integer daysFromPremiere,
                       boolean cancelled,
                       Long version) {

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public static EventDto of(final Event event, final @Nullable LocalDate premiereDate) {
        return new EventDto(event.getId(), event.getRevivalId(), event.getTypeId(), event.getTitle(), event.getStartDate(), event.getEndDate(),
                event.getStartTime() == null ? null : event.getStartTime().format(TIME_FORMAT),
                event.getEndTime() == null ? null : event.getEndTime().format(TIME_FORMAT),
                event.getLocation(), event.getDescription(), event.isRevivalPays(),
                premiereDate == null ? null : Math.toIntExact(ChronoUnit.DAYS.between(premiereDate, event.getStartDate())),
                event.isCancelled(), event.getVersion() == null ? 0L : event.getVersion());
    }
}
