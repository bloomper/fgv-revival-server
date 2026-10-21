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

package nu.fgv.revival.server.guide;

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

import java.time.Instant;

/**
 * A published version of the guide, or the one draft. Publishing keeps the id, so the draft becomes the version
 * (research R3). Every version holds its own chapters, sections and items.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Entity
@Table(name = "guide_version")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class GuideVersion extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GuideVersionStatus status = GuideVersionStatus.DRAFT;

    @Column(name = "label", length = 32)
    private @Nullable String label;

    @Column(name = "note", length = 2000)
    private @Nullable String note;

    @Column(name = "intro_sv", nullable = false, columnDefinition = "TEXT")
    private String introSv = "";

    @Column(name = "intro_en", columnDefinition = "TEXT")
    private @Nullable String introEn;

    @Column(name = "based_on_id")
    private @Nullable Long basedOnId;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    @Column(name = "published_by")
    private @Nullable Long publishedBy;

    @NotAudited
    @Column(name = "draft_marker", insertable = false, updatable = false)
    private @Nullable Integer draftMarker;

    public boolean isDraft() {
        return status == GuideVersionStatus.DRAFT;
    }

}
