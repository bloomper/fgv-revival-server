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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

/**
 * That the owners of a revival were reminded of one plan entry, so they are reminded once (M6B-FR-11). Not audited:
 * it is a technical record.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Entity
@Table(name = "mailing_reminder")
@NoArgsConstructor
@Getter
@Setter
public class MailingReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "template_id", nullable = false, updatable = false)
    private Long templateId;

    /**
     * Null for a plan entry relative to the premiere.
     */
    @Column(name = "event_id", updatable = false)
    private @Nullable Long eventId;

    @Column(name = "sent_on", nullable = false, updatable = false)
    private LocalDate sentOn;
}
