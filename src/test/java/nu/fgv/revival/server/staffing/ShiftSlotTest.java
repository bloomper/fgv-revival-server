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

package nu.fgv.revival.server.staffing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Overlapping slots: the same day and times that overlap; touching ends do not.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ShiftSlotTest {

    private static ShiftSlot slot(final String day, final String start, final String end) {
        final ShiftSlot slot = new ShiftSlot();

        slot.setDay(LocalDate.parse(day));
        slot.setStartTime(LocalTime.parse(start));
        slot.setEndTime(LocalTime.parse(end));
        return slot;
    }

    @ParameterizedTest(name = "{0} {1}–{2} overlaps 2028-04-01 18:00–20:00: {3}")
    @CsvSource({
            "2028-04-01, 17:00, 19:00, true",
            "2028-04-01, 19:00, 21:00, true",
            "2028-04-01, 18:30, 19:30, true",
            "2028-04-01, 17:00, 21:00, true",
            "2028-04-01, 16:00, 18:00, false",
            "2028-04-01, 20:00, 22:00, false",
            "2028-04-02, 18:00, 20:00, false"
    })
    void overlaps(final String day, final String start, final String end, final boolean expected) {
        final ShiftSlot other = slot("2028-04-01", "18:00", "20:00");

        assertThat(slot(day, start, end).overlaps(other)).isEqualTo(expected);
        assertThat(other.overlaps(slot(day, start, end))).isEqualTo(expected);
    }
}
