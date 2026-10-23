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

package nu.fgv.revival.server.guide.suggestion;

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
 * A proposed change to a published version, made from one revival (FR-031, FR-032). Never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Entity
@Table(name = "guide_suggestion")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class GuideSuggestion extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private Long versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private GuideSuggestionKind kind;

    @Column(name = "item_key", updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String itemKey;

    @Column(name = "chapter_key", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String chapterKey;

    @Column(name = "section_key", updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String sectionKey;

    @Column(name = "proposed_type", updatable = false, length = 36)
    private @Nullable String proposedType;

    @Column(name = "proposed_text_sv", updatable = false, columnDefinition = "TEXT")
    private @Nullable String proposedTextSv;

    @Column(name = "proposed_text_en", updatable = false, columnDefinition = "TEXT")
    private @Nullable String proposedTextEn;

    @Column(name = "reason", updatable = false, length = 2000)
    private @Nullable String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GuideSuggestionStatus status = GuideSuggestionStatus.OPEN;

    @Column(name = "decided_by")
    private @Nullable Long decidedBy;

    @Column(name = "decided_at")
    private @Nullable Instant decidedAt;

    @Column(name = "decision_reason", length = 2000)
    private @Nullable String decisionReason;

    @Column(name = "accepted_into_version_id")
    private @Nullable Long acceptedIntoVersionId;

    @Column(name = "reopened_at")
    private @Nullable Instant reopenedAt;

}
