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


package nu.fgv.revival.server.decisionlog;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * A decision, to-do or question in a revival's decision log. Never deleted: closed or cancelled instead.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "log_entry")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class LogEntry extends AbstractAuditable {

    public static final int TEXT_MAX_LENGTH = 5000;
    public static final int TAG_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "entry_date", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private LogEntryType type;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LogEntryStatus status = LogEntryStatus.OPEN;

    @Embedded
    private @Nullable Owner owner;

    @Column(name = "deadline")
    private @Nullable LocalDate deadline;

    @Column(name = "comment", columnDefinition = "TEXT")
    private @Nullable String comment;

    @Column(name = "meeting_id")
    private @Nullable Long meetingId;

    @Column(name = "guide_item_key", length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String guideItemKey;

    @Column(name = "checklist_item_id")
    private @Nullable Long checklistItemId;

    @Column(name = "answers_entry_id")
    private @Nullable Long answersEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 16)
    private LogEntryOrigin origin;

    @NotAudited
    @Column(name = "guide_question_key", insertable = false, updatable = false, length = 36, columnDefinition = "CHAR(36)")
    private @Nullable String guideQuestionKey;

    @ElementCollection
    @CollectionTable(name = "log_entry_tag", joinColumns = @JoinColumn(name = "entry_id"))
    @Column(name = "tag", nullable = false, length = TAG_MAX_LENGTH)
    private Set<String> tags = new HashSet<>();

    public boolean isOverdue(final LocalDate today) {
        return status.isOpen() && deadline != null && deadline.isBefore(today);
    }

    /**
     * Hibernate reads an owner whose columns are all null as no object at all.
     */
    public Owner getOwner() {
        return owner == null ? Owner.none() : owner;
    }
}
