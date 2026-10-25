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

import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Declares only what checklist items need, so it has no delete method. Filters run through
 * {@code SpecificationQueries}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ChecklistItemRepository extends Repository<ChecklistItem, Long> {

    ChecklistItem save(ChecklistItem item);

    ChecklistItem saveAndFlush(ChecklistItem item);

    Optional<ChecklistItem> findById(Long id);

    List<ChecklistItem> findAllById(Iterable<Long> ids);

    List<ChecklistItem> findByChecklistIdOrderByPositionAscIdAsc(Long checklistId);

    List<ChecklistItem> findByChecklistIdInOrderByPositionAscIdAsc(Collection<Long> checklistIds);

    List<ChecklistItem> findByRevivalIdAndRequiredTrue(Long revivalId);

    List<ChecklistItem> findByRevivalId(Long revivalId);

    void deleteAll(Iterable<ChecklistItem> items);
}
