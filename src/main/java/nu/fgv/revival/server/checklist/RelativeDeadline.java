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

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * A template item's deadline relative to a checklist's date (FR-031, research R11): days, working days (Monday–Friday;
 * public holidays are not counted out), or a weekday of the week before.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Embeddable
public class RelativeDeadline {

    public static final int AMOUNT_LIMIT = 365;

    @Enumerated(EnumType.STRING)
    @Column(name = "deadline_kind", length = 24)
    private RelativeDeadlineKind kind;

    @Column(name = "deadline_amount")
    private @Nullable Integer amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "deadline_weekday", length = 9)
    private @Nullable DayOfWeek weekday;

    protected RelativeDeadline() {
    }

    private RelativeDeadline(final RelativeDeadlineKind kind, final @Nullable Integer amount, final @Nullable DayOfWeek weekday) {
        this.kind = kind;
        this.amount = amount;
        this.weekday = weekday;
    }

    /**
     * Checks the combination: {@code amount} between -365 and 365 for days and working days, {@code weekday} only for
     * a weekday of the week before.
     */
    public static RelativeDeadline of(final RelativeDeadlineKind kind, final @Nullable Integer amount, final @Nullable DayOfWeek weekday) {
        final boolean valid = switch (kind) {
            case DAYS, WORKING_DAYS -> amount != null && Math.abs(amount) <= AMOUNT_LIMIT && weekday == null;
            case WEEKDAY_WEEK_BEFORE -> amount == null && weekday != null;
        };
        if (!valid) {
            throw new BadRequestException("error.checklist.deadline.invalid");
        }
        return new RelativeDeadline(kind, amount, weekday);
    }

    private static LocalDate workingDays(final LocalDate date, final int amount) {
        final int step = amount < 0 ? -1 : 1;
        LocalDate result = date;
        int remaining = Math.abs(amount);

        while (remaining > 0) {
            result = result.plusDays(step);
            if (result.getDayOfWeek() != DayOfWeek.SATURDAY && result.getDayOfWeek() != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return result;
    }

    public RelativeDeadline copy() {
        return new RelativeDeadline(kind, amount, weekday);
    }

    public RelativeDeadlineKind getKind() {
        return kind;
    }

    public @Nullable Integer getAmount() {
        return amount;
    }

    public @Nullable DayOfWeek getWeekday() {
        return weekday;
    }

    public LocalDate apply(final LocalDate date) {
        return switch (kind) {
            case DAYS -> date.plusDays(Objects.requireNonNull(amount));
            case WORKING_DAYS -> workingDays(date, Objects.requireNonNull(amount));
            case WEEKDAY_WEEK_BEFORE -> date.minusWeeks(1)
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .with(TemporalAdjusters.nextOrSame(Objects.requireNonNull(weekday)));
        };
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof RelativeDeadline deadline
                && kind == deadline.kind && Objects.equals(amount, deadline.amount) && weekday == deadline.weekday;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, amount, weekday);
    }
}
