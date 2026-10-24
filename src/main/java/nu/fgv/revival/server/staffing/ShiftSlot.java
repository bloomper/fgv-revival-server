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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A time block on an event day that needs people for a task, e.g. ticket check or punch guard (product spec 5.7,
 * M6B-FR-2). Times are Swedish time. Removed only while nobody holds it, as it is then an empty planning row.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Entity
@Table(name = "shift_slot")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class ShiftSlot extends AbstractAuditable {

    public static final int TASK_MAX_LENGTH = 100;
    public static final int COMMENT_MAX_LENGTH = 1000;
    public static final int MAX_NEEDED = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "task", nullable = false, length = TASK_MAX_LENGTH)
    private String task;

    @Column(name = "needed", nullable = false)
    private int needed;

    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private @Nullable String comment;

    /**
     * Whether the two slots share a day and their times overlap; touching ends do not overlap.
     */
    public boolean overlaps(final ShiftSlot other) {
        return day.equals(other.day) && startTime.isBefore(other.endTime) && other.startTime.isBefore(endTime);
    }
}
