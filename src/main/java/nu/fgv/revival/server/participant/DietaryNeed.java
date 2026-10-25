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

package nu.fgv.revival.server.participant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A participant's dietary needs and allergies, readable only with {@code CATERING}. Not audited, so
 * the daily deletion after the last show leaves no copy behind.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "dietary_need")
@NoArgsConstructor
@Getter
@Setter
public class DietaryNeed {

    public static final int TEXT_MAX_LENGTH = 1000;

    @Id
    @Column(name = "participant_id")
    private Long participantId;

    @Column(name = "text", nullable = false, length = TEXT_MAX_LENGTH)
    private String text;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
