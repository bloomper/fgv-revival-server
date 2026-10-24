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

package nu.fgv.revival.server.participant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

/**
 * What a revival tells its participants besides the fee (product spec 5.6): its Swish number, WhatsApp group and extra
 * terms. One per revival, made the first time it is saved.
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
@Entity
@Table(name = "participation_settings")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class ParticipationSettings extends AbstractAuditable {

    public static final int SWISH_NUMBER_MAX_LENGTH = 20;
    public static final int WHATSAPP_LINK_MAX_LENGTH = 255;
    public static final int TERMS_MAX_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "swish_number", length = SWISH_NUMBER_MAX_LENGTH)
    private @Nullable String swishNumber;

    @Column(name = "whatsapp_link", length = WHATSAPP_LINK_MAX_LENGTH)
    private @Nullable String whatsappLink;

    @Column(name = "terms", length = TERMS_MAX_LENGTH)
    private @Nullable String terms;

    public ParticipationSettings(final Long revivalId) {
        this.revivalId = revivalId;
    }
}
