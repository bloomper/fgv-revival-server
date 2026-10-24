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

package nu.fgv.revival.server.mailing;

import jakarta.persistence.Column;
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
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

/**
 * One recipient of a sent mailing: a participant or helper, or a person of the steering group, and how sending went.
 * Never an address (product spec 5.8).
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Entity
@Table(name = "mailing_recipient")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class MailingRecipient extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mailing_id", nullable = false, updatable = false)
    private Long mailingId;

    @Column(name = "participant_id", updatable = false)
    private @Nullable Long participantId;

    @Column(name = "person_id", updatable = false)
    private @Nullable Long personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MailingRecipientStatus status;

    /**
     * How many times it was sent: 1, and one more for each retry.
     */
    @Column(name = "attempt", nullable = false)
    private int attempt = 1;
}
