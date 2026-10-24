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

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A link to where a revival's file is kept (M8-FR-3): https only, in the link service chosen or recognised from its
 * host when it was saved. Its positions, Spexregister task ids, matter only for {@link MaterialVisibility#POSITIONS}.
 * Removed, never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.9
 */
@Entity
@Table(name = "material")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class Material extends AbstractAuditable {

    public static final int TITLE_MAX_LENGTH = 200;
    public static final int URL_MAX_LENGTH = 2000;
    public static final int COMMENT_MAX_LENGTH = 1000;
    public static final int MAX_POSITIONS = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "url", nullable = false, length = URL_MAX_LENGTH)
    private String url;

    @Column(name = "service_id", length = 36)
    private @Nullable String serviceId;

    /**
     * Whether the service was picked explicitly rather than recognised from the link's host.
     */
    @Column(name = "service_chosen", nullable = false)
    private boolean serviceChosen;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private MaterialCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private MaterialVisibility visibility;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "material_position", joinColumns = @JoinColumn(name = "material_id"))
    @Column(name = "task_id", nullable = false, length = 36)
    private Set<String> positions = new HashSet<>();

    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private @Nullable String comment;

    @Column(name = "removed_at")
    private @Nullable Instant removedAt;

    @Column(name = "removed_by")
    private @Nullable Long removedBy;

    public boolean isRemoved() {
        return removedAt != null;
    }
}
