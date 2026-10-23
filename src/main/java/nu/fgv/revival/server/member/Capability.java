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

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;
import nu.fgv.revival.server.util.catalog.CatalogEntry;
import org.hibernate.envers.Audited;

/**
 * An area of responsibility a steering group member can hold (FR-003), from the list the super admin keeps. A
 * directeur holds all of them. Checklist items name one as a hint for who should own them.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Entity
@Table(name = "capability")
@Audited
@NoArgsConstructor
public class Capability extends CatalogEntry {
}
