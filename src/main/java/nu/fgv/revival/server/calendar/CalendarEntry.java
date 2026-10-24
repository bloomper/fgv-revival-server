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

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One VEVENT of a calendar feed: all day over whole dates, or timed between two instants.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
public record CalendarEntry(String uid, String summary, @Nullable String location, @Nullable String description,
                            When when, Instant stamp) {

    static final String UID_DOMAIN = "nyuppsattning.fgv.nu";

    /**
     * A UID that stays the same for the row, e.g. {@code event-12@nyuppsattning.fgv.nu}.
     */
    public static String uid(final String kind, final Long id) {
        return "%s-%d@%s".formatted(kind, id, UID_DOMAIN);
    }

    /**
     * When the entry happens.
     */
    public sealed interface When permits AllDay, Timed {
    }

    /**
     * From the first to the last day, both included.
     */
    public record AllDay(LocalDate first, LocalDate last) implements When {
    }

    public record Timed(Instant start, Instant end) implements When {
    }
}
