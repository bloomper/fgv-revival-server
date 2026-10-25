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
package nu.fgv.revival.server.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * The application's settings: one row, made by the migration.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "application_settings")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class ApplicationSettings extends AbstractAuditable {

    public static final long ID = 1L;
    public static final int TERMS_MAX_LENGTH = 4000;

    @Id
    private Long id;

    /**
     * Terms every participant accepts, in every revival, besides the fee, the loan and the revival's own terms.
     */
    @Column(name = "terms_sv", columnDefinition = "TEXT")
    private @Nullable String termsSv;

    @Column(name = "terms_en", columnDefinition = "TEXT")
    private @Nullable String termsEn;

    /**
     * The participation fee a new revival's budget starts with.
     */
    @Column(name = "default_participation_fee", precision = 12, scale = 2)
    private @Nullable BigDecimal defaultParticipationFee;
}
