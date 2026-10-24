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
package nu.fgv.revival.server.budget;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An earlier revival's budget and outcome per category, kept as reference (product spec 5.10, section 7): entered by
 * the super admin, or made automatically from a revival that is deleted or anonymized. Holds no personal data.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Entity
@Table(name = "reference_revival")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class ReferenceRevival extends AbstractAuditable {

    public static final int LABEL_MAX_LENGTH = 100;
    public static final int COMMENT_MAX_LENGTH = 1000;
    public static final int MAX_PARTICIPANTS = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "label", nullable = false, length = LABEL_MAX_LENGTH)
    private String label;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "participants")
    private @Nullable Integer participants;

    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private @Nullable String comment;

    @Column(name = "automatic", nullable = false, updatable = false)
    private boolean automatic;

    @OneToMany(mappedBy = "referenceRevival", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    private List<ReferenceFigure> figures = new ArrayList<>();
}
