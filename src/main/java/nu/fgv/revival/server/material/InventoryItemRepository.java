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
package nu.fgv.revival.server.material;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Declares only what the inventory needs, so it has no delete method (constitution IV).
 *
 * @author Anders Jacobsson
 * @since 0.9
 */
public interface InventoryItemRepository extends Repository<InventoryItem, Long> {

    InventoryItem save(InventoryItem item);

    InventoryItem saveAndFlush(InventoryItem item);

    Optional<InventoryItem> findById(Long id);

    List<InventoryItem> findByRevivalId(Long revivalId);
}
