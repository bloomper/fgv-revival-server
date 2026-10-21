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

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.annotations.SortNatural;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

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

    @Column(name = "working_title", nullable = false)
    private String workingTitle;

    @Column(name = "premiere_date")
    private @Nullable LocalDate premiereDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RevivalStatus status = RevivalStatus.PROPOSED;

    @ElementCollection
    @CollectionTable(name = "revival_show_date", joinColumns = @JoinColumn(name = "revival_id"))
    @Column(name = "show_date", nullable = false)
    @SortNatural
    private SortedSet<LocalDate> showDates = new TreeSet<>();

    @ElementCollection
    @CollectionTable(name = "revival_venue", joinColumns = @JoinColumn(name = "revival_id"))
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    private List<String> venues = new ArrayList<>();

}
