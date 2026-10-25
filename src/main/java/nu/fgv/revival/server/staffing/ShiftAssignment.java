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

/**
 * A participant or helper holding a shift slot: signed up themselves, or assigned by a writer of staffing.
 * Unassigning deletes the row; Envers keeps that it was held.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "shift_assignment")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class ShiftAssignment extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_id", nullable = false, updatable = false)
    private Long slotId;

    @Column(name = "participant_id", nullable = false, updatable = false)
    private Long participantId;

    @Column(name = "self_signed_up", nullable = false)
    private boolean selfSignedUp;

    /**
     * The person who assigned it; null when the participant signed up themselves.
     */
    @Column(name = "assigned_by")
    private @Nullable Long assignedBy;
}
