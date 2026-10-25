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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * An event in a revival's timeline: of a type from the guide's example timeline, or added freely. Times are
 * Swedish time. Never deleted; an event can be cancelled and restored.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "event")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class Event extends AbstractAuditable {

    public static final int TITLE_MAX_LENGTH = 255;
    public static final int LOCATION_MAX_LENGTH = 255;
    public static final int DESCRIPTION_MAX_LENGTH = 4000;
    /**
     * The most days the end date may be after the start date.
     */
    public static final int MAX_SPAN_DAYS = 31;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "type_id", updatable = false)
    private @Nullable Long typeId;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "start_time")
    private @Nullable LocalTime startTime;

    @Column(name = "end_time")
    private @Nullable LocalTime endTime;

    @Column(name = "location", length = LOCATION_MAX_LENGTH)
    private @Nullable String location;

    @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
    private @Nullable String description;

    @Column(name = "revival_pays", nullable = false)
    private boolean revivalPays;

    @Column(name = "cancelled_at")
    private @Nullable Instant cancelledAt;

    public boolean isCancelled() {
        return cancelledAt != null;
    }
}
