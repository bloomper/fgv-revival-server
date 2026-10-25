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


package nu.fgv.revival.server.meeting;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * A recipient of a minutes email, by person id. No email address is stored.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Embeddable
public record MinutesEmailRecipient(
        @Column(name = "person_id", nullable = false) Long personId,
        @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 8) MinutesEmailRecipientStatus status) {
}
