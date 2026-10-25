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
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * An item of a checklist template. Retired, never deleted, so checklist items made from it keep their link.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "checklist_template_item")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class ChecklistTemplateItem extends AbstractAuditable {

    public static final int TEXT_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false, updatable = false)
    private Long templateId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "text_sv", nullable = false, length = TEXT_MAX_LENGTH)
    private String textSv;

    @Column(name = "text_en", length = TEXT_MAX_LENGTH)
    private @Nullable String textEn;

    @Column(name = "guide_item_key", length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String guideItemKey;

    @Column(name = "default_capability", length = 36)
    private @Nullable String defaultCapability;

    @Embedded
    private @Nullable RelativeDeadline deadline;

    @Column(name = "retired_at")
    private @Nullable Instant retiredAt;

    public boolean isRetired() {
        return retiredAt != null;
    }
}
