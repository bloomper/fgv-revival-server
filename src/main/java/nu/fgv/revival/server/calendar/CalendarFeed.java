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


package nu.fgv.revival.server.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A person's secret calendar link for a revival (M4-FR-5). Only the SHA-256 of the token is kept. A credential, like
 * {@code person}, so not audited; at most one link per person and revival is active.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@Entity
@Table(name = "calendar_feed")
@NoArgsConstructor
@Getter
@Setter
public class CalendarFeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "person_id", nullable = false, updatable = false)
    private Long personId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
