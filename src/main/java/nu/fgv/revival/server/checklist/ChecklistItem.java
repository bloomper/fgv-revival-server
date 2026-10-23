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


package nu.fgv.revival.server.checklist;

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
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A thing to do in a checklist (FR-024). Required when it comes from a guide requirement, and then its text is the
 * guide's, read from the pinned version (FR-025). Never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Entity
@Table(name = "checklist_item")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class ChecklistItem extends AbstractAuditable {

    public static final int TEXT_MAX_LENGTH = 1000;
    public static final int REASON_MAX_LENGTH = 1000;
    public static final int COMMENT_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checklist_id", nullable = false)
    private Long checklistId;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "required", nullable = false, updatable = false)
    private boolean required;

    @Column(name = "guide_item_key", length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String guideItemKey;

    @Column(name = "text", length = TEXT_MAX_LENGTH)
    private @Nullable String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private ChecklistItemState state = ChecklistItemState.OPEN;

    @Column(name = "state_changed_by")
    private @Nullable Long stateChangedBy;

    @Column(name = "state_changed_at")
    private @Nullable Instant stateChangedAt;

    @Column(name = "reason", length = REASON_MAX_LENGTH)
    private @Nullable String reason;

    @Column(name = "reason_key", length = 64)
    private @Nullable String reasonKey;

    @Column(name = "reason_arg", length = 32)
    private @Nullable String reasonArg;

    @Embedded
    private @Nullable Owner owner;

    @Column(name = "deadline")
    private @Nullable LocalDate deadline;

    @Column(name = "deadline_manual", nullable = false)
    private boolean deadlineManual;

    /**
     * A copy of the template item's rule, so later template changes leave this checklist alone (FR-032).
     */
    @Embedded
    private @Nullable RelativeDeadline relativeDeadline;

    @Column(name = "comment", length = COMMENT_MAX_LENGTH)
    private @Nullable String comment;

    @Column(name = "template_item_id", updatable = false)
    private @Nullable Long templateItemId;

    @Column(name = "default_capability", length = 36)
    private @Nullable String defaultCapability;

    @NotAudited
    @Column(name = "required_key", insertable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String requiredKey;

    public boolean isOverdue(final LocalDate today) {
        return state == ChecklistItemState.OPEN && deadline != null && deadline.isBefore(today);
    }

    /**
     * Hibernate reads an owner whose columns are all null as no object at all.
     */
    public Owner getOwner() {
        return owner == null ? Owner.none() : owner;
    }
}
