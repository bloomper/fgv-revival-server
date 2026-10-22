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


package nu.fgv.revival.server.checklist.template;

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
import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A super admin's checklist template for a guide chapter or an event kind (FR-030). Retired, never deleted, so checklist items
 * can refer to its items.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Entity
@Table(name = "checklist_template")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class ChecklistTemplate extends AbstractAuditable {

    public static final int NAME_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 8)
    private ChecklistKind kind;

    @Column(name = "chapter_key", updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String chapterKey;

    @Column(name = "name_sv", length = NAME_MAX_LENGTH)
    private @Nullable String nameSv;

    @Column(name = "name_en", length = NAME_MAX_LENGTH)
    private @Nullable String nameEn;

    @Column(name = "retired_at")
    private @Nullable Instant retiredAt;

    @NotAudited
    @Column(name = "chapter_marker", insertable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String chapterMarker;

    public boolean isRetired() {
        return retiredAt != null;
    }
}
