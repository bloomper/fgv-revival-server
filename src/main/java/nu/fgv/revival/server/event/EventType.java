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

/**
 * A kind of event from the guide's example timeline, with its offsets from the premiere (M4-FR-1). Kept by the super
 * admin in order; retired, never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@Entity
@Table(name = "event_type")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class EventType extends AbstractAuditable {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int TEXT_MAX_LENGTH = 4000;
    public static final int OFFSET_LIMIT = 730;
    public static final int MAX_DAYS = 14;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_sv", nullable = false, length = NAME_MAX_LENGTH)
    private String nameSv;

    @Column(name = "name_en", length = NAME_MAX_LENGTH)
    private @Nullable String nameEn;

    @Column(name = "description_sv", columnDefinition = "TEXT")
    private @Nullable String descriptionSv;

    @Column(name = "description_en", columnDefinition = "TEXT")
    private @Nullable String descriptionEn;

    @Column(name = "rooms_sv", columnDefinition = "TEXT")
    private @Nullable String roomsSv;

    @Column(name = "rooms_en", columnDefinition = "TEXT")
    private @Nullable String roomsEn;

    @Column(name = "catering_sv", columnDefinition = "TEXT")
    private @Nullable String cateringSv;

    @Column(name = "catering_en", columnDefinition = "TEXT")
    private @Nullable String cateringEn;

    @Column(name = "offset_min_days")
    private @Nullable Integer offsetMinDays;

    @Column(name = "offset_max_days")
    private @Nullable Integer offsetMaxDays;

    @Column(name = "days", nullable = false)
    private int days = 1;

    @Column(name = "revival_pays", nullable = false)
    private boolean revivalPays;

    @Column(name = "checklist_template_id")
    private @Nullable Long checklistTemplateId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "retired_at")
    private @Nullable Instant retiredAt;

    public boolean isRetired() {
        return retiredAt != null;
    }

    /**
     * Whether the type has offsets, so it can be proposed from the premiere.
     */
    public boolean hasOffsets() {
        return offsetMinDays != null && offsetMaxDays != null;
    }
}
