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


package nu.fgv.revival.server.checklist;

import nu.fgv.revival.server.util.error.BadRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every kind of relative deadline computed from a checklist date.
 *
 * @author Anders Jacobsson
 */
class RelativeDeadlineTest {

    @ParameterizedTest
    @CsvSource({
            "2027-11-20, -14, 2027-11-06",
            "2027-11-20, 0, 2027-11-20",
            "2027-11-20, 14, 2027-12-04",
            "2027-03-01, -1, 2027-02-28",
            "2028-03-01, -1, 2028-02-29",
            "2027-01-03, -7, 2026-12-27",
            "2027-12-31, 1, 2028-01-01",
    })
    void daysCountEveryDay(final LocalDate date, final int amount, final LocalDate expected) {
        assertThat(RelativeDeadline.of(RelativeDeadlineKind.DAYS, amount, null).apply(date)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "2027-11-20, -5, 2027-11-15",
            "2027-11-21, -5, 2027-11-15",
            "2027-11-19, -5, 2027-11-12",
            "2027-11-17, -1, 2027-11-16",
            "2027-11-15, -1, 2027-11-12",
            "2027-11-19, 1, 2027-11-22",
            "2027-11-20, 1, 2027-11-22",
            "2027-12-31, 1, 2028-01-03",
            "2027-03-01, -1, 2027-02-26",
            "2027-11-20, 0, 2027-11-20",
    })
    void workingDaysSkipWeekends(final LocalDate date, final int amount, final LocalDate expected) {
        assertThat(RelativeDeadline.of(RelativeDeadlineKind.WORKING_DAYS, amount, null).apply(date)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "2027-11-20, MONDAY, 2027-11-08",
            "2027-11-20, SUNDAY, 2027-11-14",
            "2027-11-15, MONDAY, 2027-11-08",
            "2027-11-21, FRIDAY, 2027-11-12",
            "2027-01-02, MONDAY, 2026-12-21",
            "2027-01-04, MONDAY, 2026-12-28",
            "2026-12-31, WEDNESDAY, 2026-12-23",
            "2027-01-06, THURSDAY, 2026-12-31",
    })
    void aWeekdayOfTheIsoWeekBefore(final LocalDate date, final DayOfWeek weekday, final LocalDate expected) {
        assertThat(RelativeDeadline.of(RelativeDeadlineKind.WEEKDAY_WEEK_BEFORE, null, weekday).apply(date)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "DAYS, 366, null",
            "DAYS, -366, null",
            "DAYS, null, null",
            "DAYS, 3, MONDAY",
            "WORKING_DAYS, 400, null",
            "WORKING_DAYS, null, null",
            "WEEKDAY_WEEK_BEFORE, null, null",
            "WEEKDAY_WEEK_BEFORE, 1, MONDAY",
    })
    void refusesInvalidCombinations(final RelativeDeadlineKind kind, final Integer amount, final DayOfWeek weekday) {
        assertThatThrownBy(() -> RelativeDeadline.of(kind, amount, weekday)).isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @CsvSource({"DAYS, 365", "DAYS, -365", "WORKING_DAYS, 365", "WORKING_DAYS, -365"})
    void acceptsTheLimits(final RelativeDeadlineKind kind, final int amount) {
        assertThat(RelativeDeadline.of(kind, amount, null).getAmount()).isEqualTo(amount);
    }
}
