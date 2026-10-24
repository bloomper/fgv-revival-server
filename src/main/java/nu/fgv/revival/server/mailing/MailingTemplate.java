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
import jakarta.persistence.Embedded;
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
import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A planned mailing in the super admin's mailing plan (product spec 5.8, M6B-FR-10): due relative to each event of a
 * type, or to the premiere, e.g. "Monday the week before rehearsal weekend 2: remind about allergies". Ordered and
 * retired like event types, never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Entity
@Table(name = "mailing_template")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class MailingTemplate extends AbstractAuditable {

    public static final int NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_sv", nullable = false, length = NAME_MAX_LENGTH)
    private String nameSv;

    @Column(name = "name_en", length = NAME_MAX_LENGTH)
    private @Nullable String nameEn;

    @Column(name = "subject_sv", nullable = false, length = Mailing.SUBJECT_MAX_LENGTH)
    private String subjectSv;

    @Column(name = "subject_en", length = Mailing.SUBJECT_MAX_LENGTH)
    private @Nullable String subjectEn;

    @Column(name = "body_sv", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String bodySv;

    @Column(name = "body_en", columnDefinition = "MEDIUMTEXT")
    private @Nullable String bodyEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_kind", nullable = false, length = 16)
    private MailingAudienceKind audienceKind;

    /**
     * Due relative to each event of this type; null: relative to the premiere.
     */
    @Column(name = "event_type_id")
    private @Nullable Long eventTypeId;

    @Embedded
    private RelativeDeadline due;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "retired_at")
    private @Nullable Instant retiredAt;

    public boolean isRetired() {
        return retiredAt != null;
    }
}
