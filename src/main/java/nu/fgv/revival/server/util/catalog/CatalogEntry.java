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
package nu.fgv.revival.server.util.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * An entry of a list the super admin keeps. The id is what other rows store: the entries that were enums before keep
 * their constant's name, new ones get a UUID.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@MappedSuperclass
@Audited
@Getter
@Setter
public abstract class CatalogEntry extends AbstractAuditable {

    public static final int NAME_MAX_LENGTH = 100;

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "name_sv", nullable = false, length = NAME_MAX_LENGTH)
    private String nameSv;

    @Column(name = "name_en", length = NAME_MAX_LENGTH)
    private @Nullable String nameEn;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Column(name = "retired_at")
    private @Nullable Instant retiredAt;

    public boolean isRetired() {
        return retiredAt != null;
    }
}
