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

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Something a revival has, needs or borrows (M8-FR-4): a script, props, a costume. Its owner is a member of the steering
 * group or a free-text name, as for decision log rows; it may point at a material of the same revival that describes it.
 * Removed, never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.9
 */
@Entity
@Table(name = "inventory_item")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class InventoryItem extends AbstractAuditable {

    public static final int NAME_MAX_LENGTH = 200;
    public static final int MAX_QUANTITY = 100_000;
    public static final int LOCATION_NOTE_MAX_LENGTH = 255;
    public static final int COMMENT_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private InventoryKind kind;

    @Column(name = "quantity", nullable = false)
    private int quantity = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InventoryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "location", length = 16)
    private @Nullable InventoryLocation location;

    @Column(name = "location_note", length = LOCATION_NOTE_MAX_LENGTH)
    private @Nullable String locationNote;

    @Embedded
    private @Nullable Owner owner;

    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private @Nullable String comment;

    @Column(name = "material_id")
    private @Nullable Long materialId;

    @Column(name = "removed_at")
    private @Nullable Instant removedAt;

    @Column(name = "removed_by")
    private @Nullable Long removedBy;

    public boolean isRemoved() {
        return removedAt != null;
    }

    /**
     * Hibernate reads an owner whose columns are all null as no object at all.
     */
    public Owner getOwner() {
        return owner == null ? Owner.none() : owner;
    }
}
