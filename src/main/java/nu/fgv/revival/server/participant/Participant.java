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
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A participant or helper of a revival (product spec 5.6): a spexare by id, or a pending person kept in
 * {@link PendingPerson} until they become one. Their login role is the {@code memberId} row, made when they are invited.
 * Removing closes the row; it is never deleted while the revival exists (constitution IV). The comment is the person's
 * own free text and is not audited.
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
@Entity
@Table(name = "participant")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class Participant extends AbstractAuditable {

    public static final int ROLE_NAME_MAX_LENGTH = 100;
    public static final int COMMENT_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ParticipantKind kind;

    @Column(name = "spexare_id")
    private @Nullable Long spexareId;

    @Column(name = "member_id")
    private @Nullable Long memberId;

    @Column(name = "position_task_id", length = 36)
    private @Nullable String positionTaskId;

    @Column(name = "role_name", length = ROLE_NAME_MAX_LENGTH)
    private @Nullable String roleName;

    @Column(name = "vocal_part_id", length = 36)
    private @Nullable String vocalPartId;

    @Column(name = "preferred_task_id", length = 36)
    private @Nullable String preferredTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "response", nullable = false, length = 16)
    private ParticipantResponse response = ParticipantResponse.PENDING;

    @Column(name = "responded_at")
    private @Nullable Instant respondedAt;

    @NotAudited
    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private @Nullable String comment;

    @Column(name = "terms_accepted_at")
    private @Nullable Instant termsAcceptedAt;

    @Column(name = "register_consent")
    private @Nullable Boolean registerConsent;

    @Column(name = "register_consent_at")
    private @Nullable Instant registerConsentAt;

    @Column(name = "invited_at")
    private @Nullable Instant invitedAt;

    @Column(name = "reminded_at")
    private @Nullable Instant remindedAt;

    @Column(name = "fee", precision = 12, scale = 2)
    private @Nullable BigDecimal fee;

    @Column(name = "fee_paid_at")
    private @Nullable Instant feePaidAt;

    @Column(name = "fee_paid_by")
    private @Nullable Long feePaidBy;

    @Column(name = "compensation_per_shift", precision = 12, scale = 2)
    private @Nullable BigDecimal compensationPerShift;

    @Column(name = "membership_paid", nullable = false)
    private boolean membershipPaid;

    @Column(name = "removed_at")
    private @Nullable Instant removedAt;

    @Column(name = "removed_by")
    private @Nullable Long removedBy;

    @NotAudited
    @Column(name = "active_spexare_id", insertable = false, updatable = false)
    private @Nullable Long activeSpexareId;

    public boolean isRemoved() {
        return removedAt != null;
    }

    public boolean isPending() {
        return spexareId == null;
    }

    public boolean isFeePaid() {
        return feePaidAt != null;
    }
}
