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
import java.time.LocalDate;

/**
 * A revival's checklist: one per chapter of its pinned guide version, or an event checklist with a name and an optional date (FR-022, FR-023).
 * An event checklist may belong to an event, which then names and dates it (M4-FR-4). Never deleted; an event checklist can be cancelled.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Entity
@Table(name = "checklist")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class Checklist extends AbstractAuditable {

    public static final int NAME_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 8)
    private ChecklistKind kind;

    @Column(name = "chapter_key", updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String chapterKey;

    @Column(name = "name", length = NAME_MAX_LENGTH)
    private @Nullable String name;

    @Column(name = "event_date")
    private @Nullable LocalDate date;

    @Column(name = "template_id")
    private @Nullable Long templateId;

    @Column(name = "event_id", updatable = false)
    private @Nullable Long eventId;

    @Column(name = "cancelled_at")
    private @Nullable Instant cancelledAt;

    public boolean isCancelled() {
        return cancelledAt != null;
    }
}
