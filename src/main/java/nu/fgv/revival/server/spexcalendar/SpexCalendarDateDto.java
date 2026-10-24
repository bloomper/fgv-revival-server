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
package nu.fgv.revival.server.spexcalendar;

import java.time.LocalDate;

/**
 * GraphQL's {@code SpexCalendarDate}.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
public record SpexCalendarDateDto(Long id, LocalDate date, String label, Long version) {

    static SpexCalendarDateDto of(final SpexCalendarDate date) {
        return new SpexCalendarDateDto(date.getId(), date.getDate(), date.getLabel(), date.getVersion() == null ? 0L : date.getVersion());
    }
}
