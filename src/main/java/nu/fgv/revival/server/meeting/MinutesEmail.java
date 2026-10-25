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

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One sending of a meeting's minutes, saved after the mail went out with each recipient's result. Append-only, and
 * itself the record, so not audited.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "minutes_email")
@NoArgsConstructor
@Getter
@Setter
public class MinutesEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false, updatable = false)
    private Long meetingId;

    @Column(name = "language", nullable = false, updatable = false, length = 2)
    private String language;

    @Column(name = "sent_by", nullable = false, updatable = false)
    private Long sentBy;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "retry_of", updatable = false)
    private @Nullable Long retryOf;

    @Column(name = "waiting_left_out", nullable = false, updatable = false)
    private int waitingLeftOut;

    @ElementCollection
    @CollectionTable(name = "minutes_email_recipient", joinColumns = @JoinColumn(name = "email_id"))
    private List<MinutesEmailRecipient> recipients = new ArrayList<>();
}
