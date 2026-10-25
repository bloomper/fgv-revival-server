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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface GuideVersionRepository extends JpaRepository<GuideVersion, Long> {

    Optional<GuideVersion> findFirstByStatus(GuideVersionStatus status);

    Optional<GuideVersion> findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus status);

    List<GuideVersion> findByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus status);

    boolean existsByStatus(GuideVersionStatus status);

    @Query("select v.label from GuideVersion v where v.label is not null")
    List<String> findLabels();
}
