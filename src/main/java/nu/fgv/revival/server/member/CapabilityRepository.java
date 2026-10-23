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
package nu.fgv.revival.server.member;

import nu.fgv.revival.server.util.catalog.CatalogRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * @author Anders Jacobsson
 * @since 0.4
 */
public interface CapabilityRepository extends CatalogRepository<Capability> {

    @Query(value = """
            SELECT capability FROM revival_member_capability
            UNION SELECT default_capability FROM checklist_item WHERE default_capability IS NOT NULL
            UNION SELECT default_capability FROM checklist_template_item WHERE default_capability IS NOT NULL""", nativeQuery = true)
    List<String> findUsedIds();
}
