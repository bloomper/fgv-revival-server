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

package nu.fgv.revival.server.revival;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * One show of a revival: a date, with a start time in Swedish time and a venue once they are decided. A revival never
 * has two shows with the same date, time and venue; {@link RevivalService} checks that, since a unique key cannot hold a null venue.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "revival_show")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class RevivalShow {

    static final int VENUE_MAX_LENGTH = 255;
    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "revival_id", nullable = false, updatable = false)
    private Revival revival;

    @Column(name = "show_date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time")
    private @Nullable LocalTime time;

    @Column(name = "venue", length = VENUE_MAX_LENGTH)
    private @Nullable String venue;

    RevivalShow(final Revival revival, final LocalDate date, final @Nullable LocalTime time, final @Nullable String venue) {
        this.revival = revival;
        this.date = date;
        this.time = time;
        this.venue = venue;
    }

    RevivalShowDto toDto() {
        return new RevivalShowDto(date, time == null ? null : time.format(TIME_FORMAT), venue);
    }
}
