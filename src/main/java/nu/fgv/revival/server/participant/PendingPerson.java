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
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * The name and contact details of a participant who is not in Spexregister. Its home is this
 * application, so it is not audited: deleting it leaves no trace, as it must when the person declines, becomes a
 * spexare or never answers before the revival leaves {@code OPEN}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "pending_person")
@NoArgsConstructor
@Getter
@Setter
public class PendingPerson {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int EMAIL_MAX_LENGTH = 255;
    public static final int PHONE_MAX_LENGTH = 50;

    @Id
    @Column(name = "participant_id")
    private Long participantId;

    @Column(name = "first_name", nullable = false, length = NAME_MAX_LENGTH)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = NAME_MAX_LENGTH)
    private String lastName;

    @Column(name = "email", length = EMAIL_MAX_LENGTH)
    private @Nullable String email;

    @Column(name = "phone", length = PHONE_MAX_LENGTH)
    private @Nullable String phone;

    @Column(name = "street_address")
    private @Nullable String streetAddress;

    @Column(name = "postal_code", length = 20)
    private @Nullable String postalCode;

    @Column(name = "city", length = 100)
    private @Nullable String city;

    public PendingPerson(final Long participantId) {
        this.participantId = participantId;
    }
}
