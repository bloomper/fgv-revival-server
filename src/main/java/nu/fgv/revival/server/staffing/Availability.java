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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

/**
 * A participant's answer for one day of an event (M6B-FR-1). Clearing the answer deletes the row: it is the person's
 * own answer, not the revival's history. The comment is the person's free text and is not audited.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Entity
@Table(name = "availability")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class Availability extends AbstractAuditable {

    public static final int COMMENT_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participant_id", nullable = false, updatable = false)
    private Long participantId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "day", nullable = false, updatable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer", nullable = false, length = 8)
    private AvailabilityAnswer answer;

    @NotAudited
    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private @Nullable String comment;
}
