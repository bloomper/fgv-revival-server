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
 * A tip, requirement, question or text in Markdown. A null section means it comes before the chapter's first section.
 * Its key stays the same from version to version, so checklists can link to it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "guide_item")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class GuideItem extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false, updatable = false)
    private Long versionId;

    @Column(name = "chapter_id", nullable = false)
    private Long chapterId;

    @Column(name = "section_id")
    private @Nullable Long sectionId;

    @Column(name = "`key`", nullable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private String key;

    @Column(name = "position", nullable = false)
    private Integer position;

    /**
     * A {@link GuideItemType}'s id.
     */
    @Column(name = "type", nullable = false, length = 36)
    private String type;

    @Column(name = "text_sv", nullable = false, columnDefinition = "TEXT")
    private String textSv;

    @Column(name = "text_en", columnDefinition = "TEXT")
    private @Nullable String textEn;

}
