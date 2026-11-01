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
import nu.fgv.revival.server.util.Uuids;
import org.hibernate.annotations.Formula;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One run of the participation sync to Spexregister: who started it, from which preview, and how it ended.
 * At most one is {@code RUNNING} per revival, which the database enforces.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "sync_run")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class SyncRun extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Formula("(SELECT t.uuid FROM revival t WHERE t.id = revival_id)")
    @NotAudited
    private @Nullable UUID revivalUuid;

    @Column(name = "started_by")
    private @Nullable Long startedBy;

    @Formula("(SELECT t.uuid FROM person t WHERE t.id = started_by)")
    @NotAudited
    private @Nullable UUID startedByUuid;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private @Nullable Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SyncRunStatus status = SyncRunStatus.RUNNING;

    @Column(name = "preview_hash", nullable = false, length = 64)
    private String previewHash;

    @NotAudited
    @Column(name = "running_marker", insertable = false, updatable = false)
    private @Nullable Integer runningMarker;
}
