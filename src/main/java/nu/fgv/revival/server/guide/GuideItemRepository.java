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

import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * @author Anders Jacobsson
 * @since 0.2
 */
public interface GuideItemRepository extends JpaRepository<GuideItem, Long> {

    List<GuideItem> findByVersionIdOrderByChapterIdAscPositionAsc(Long versionId);

    List<GuideItem> findByChapterIdAndSectionIdOrderByPosition(Long chapterId, @Nullable Long sectionId);

    List<GuideItem> findByChapterIdAndSectionIdIsNullOrderByPosition(Long chapterId);

    Optional<GuideItem> findByVersionIdAndKey(Long versionId, String key);

    boolean existsBySectionId(Long sectionId);

    void deleteByVersionId(Long versionId);
}
