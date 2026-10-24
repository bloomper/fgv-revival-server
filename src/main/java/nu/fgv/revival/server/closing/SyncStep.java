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

package nu.fgv.revival.server.closing;

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
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * One write a run makes, for a participant or (for {@link SyncOperation#REVIVAL}) the revival. No personal data: the
 * participant by id, Spexregister's id of what was written or found, a short error code and a detail such as a
 * position's name or the membership year.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "sync_step")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class SyncStep extends AbstractAuditable {

    public static final int DETAIL_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private Long runId;

    @Column(name = "participant_id")
    private @Nullable Long participantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 16)
    private SyncOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SyncStepStatus status = SyncStepStatus.PENDING;

    @Column(name = "remote_id", length = 64)
    private @Nullable String remoteId;

    @Column(name = "error_code", length = 40)
    private @Nullable String errorCode;

    @Column(name = "detail", length = DETAIL_MAX_LENGTH)
    private @Nullable String detail;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
