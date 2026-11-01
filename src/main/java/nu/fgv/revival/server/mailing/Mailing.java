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
import nu.fgv.revival.server.util.Uuids;
import org.hibernate.annotations.Formula;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A mailing of a revival: its audience, subject and Markdown body. Drafted by the steering group,
 * sent by a directeur or a member with {@code COMMS}, or cancelled; never deleted while the revival exists.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "mailing")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class Mailing extends AbstractAuditable {

    public static final int SUBJECT_MAX_LENGTH = 200;
    public static final int BODY_MAX_LENGTH = 20_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Formula("(SELECT t.uuid FROM revival t WHERE t.id = revival_id)")
    @NotAudited
    private @Nullable UUID revivalUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_kind", nullable = false, length = 16)
    private MailingAudienceKind audienceKind;

    /**
     * {@link MailingAudienceKind#POSITION}: the Spexregister task id.
     */
    @Column(name = "audience_task_id", length = 36)
    private @Nullable String audienceTaskId;

    /**
     * {@link MailingAudienceKind#EVENT_ATTENDEES}: the event.
     */
    @Column(name = "audience_event_id")
    private @Nullable Long audienceEventId;

    @Formula("(SELECT t.uuid FROM event t WHERE t.id = audience_event_id)")
    @NotAudited
    private @Nullable UUID audienceEventUuid;

    @Column(name = "subject", nullable = false, length = SUBJECT_MAX_LENGTH)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MailingStatus status = MailingStatus.DRAFT;

    /**
     * The plan entry it was drafted from: its template, and its event or null for the premiere.
     */
    @Column(name = "template_id", updatable = false)
    private @Nullable Long templateId;

    @Formula("(SELECT t.uuid FROM mailing_template t WHERE t.id = template_id)")
    @NotAudited
    private @Nullable UUID templateUuid;

    @Column(name = "plan_event_id", updatable = false)
    private @Nullable Long planEventId;

    @Formula("(SELECT t.uuid FROM event t WHERE t.id = plan_event_id)")
    @NotAudited
    private @Nullable UUID planEventUuid;

    @Column(name = "created_by_person", updatable = false)
    private @Nullable Long createdByPerson;

    @Column(name = "sent_by_person")
    private @Nullable Long sentByPerson;

    @Column(name = "sent_at")
    private @Nullable Instant sentAt;

    @Column(name = "cancelled_at")
    private @Nullable Instant cancelledAt;
}
