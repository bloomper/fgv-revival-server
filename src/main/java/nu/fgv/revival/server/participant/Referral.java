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
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Someone a participant thinks the steering group is missing (product spec 5.6). Not audited and deleted once the
 * steering group has handled it (product spec section 7).
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
@Entity
@Table(name = "referral")
@NoArgsConstructor
@Getter
@Setter
public class Referral {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int CONTACT_MAX_LENGTH = 200;
    public static final int COMMENT_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "referred_by_participant_id", nullable = false, updatable = false)
    private Long referredByParticipantId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "contact", length = CONTACT_MAX_LENGTH)
    private @Nullable String contact;

    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private @Nullable String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
