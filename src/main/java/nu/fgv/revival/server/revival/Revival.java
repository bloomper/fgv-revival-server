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

package nu.fgv.revival.server.revival;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.annotations.BatchSize;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A new production of an old spex. The spex itself lives in Spexregister; only its id is stored (FR-008).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Entity
@Table(name = "revival")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class Revival extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "spex_id", nullable = false)
    private Long spexId;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "premiere_date")
    private @Nullable LocalDate premiereDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RevivalStatus status = RevivalStatus.PROPOSED;

    @Column(name = "guide_version_id")
    private @Nullable Long guideVersionId;

    /**
     * The year the revival pays FGV membership for its participants (M7-FR-1).
     */
    @Column(name = "membership_year")
    private @Nullable Integer membershipYear;

    /**
     * Spexregister's id for this revival, once the sync has found or created it (M7). Written by the sync without
     * touching the revival's version, so it is not audited.
     */
    @NotAudited
    @Column(name = "spexregister_revival_id", updatable = false, insertable = false)
    private @Nullable Long spexregisterRevivalId;

    /**
     * When the people were removed from the revival (product spec section 7); nothing personal is added after.
     */
    @Column(name = "anonymized_at")
    private @Nullable Instant anonymizedAt;

    /**
     * Who anonymized it; null when it was anonymized automatically.
     */
    @Column(name = "anonymized_by")
    private @Nullable Long anonymizedBy;

    @OneToMany(mappedBy = "revival", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    private List<RevivalShow> shows = new ArrayList<>();

    public boolean isAnonymized() {
        return anonymizedAt != null;
    }

}
