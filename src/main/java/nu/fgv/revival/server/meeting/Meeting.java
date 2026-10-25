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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A steering group meeting in a revival. Never deleted; a planned meeting can be cancelled.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "meeting")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class Meeting extends AbstractAuditable {

    public static final int TITLE_MAX_LENGTH = 255;
    public static final int OTHERS_PRESENT_MAX_LENGTH = 1000;
    public static final int AGENDA_POINT_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "meeting_date", nullable = false)
    private LocalDate date;

    @Column(name = "title", length = TITLE_MAX_LENGTH)
    private @Nullable String title;

    @Column(name = "others_present", length = OTHERS_PRESENT_MAX_LENGTH)
    private @Nullable String othersPresent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MeetingStatus status = MeetingStatus.PLANNED;

    @Column(name = "recorder_person_id")
    private @Nullable Long recorderPersonId;

    @Column(name = "started_at")
    private @Nullable Instant startedAt;

    @Column(name = "closed_at")
    private @Nullable Instant closedAt;

    @Column(name = "closed_by")
    private @Nullable Long closedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "minutes")
    private @Nullable Minutes minutes;

    @NotAudited
    @Column(name = "in_progress_marker", insertable = false, updatable = false)
    private @Nullable Integer inProgressMarker;

    @ElementCollection
    @CollectionTable(name = "meeting_attendee", joinColumns = @JoinColumn(name = "meeting_id"))
    @Column(name = "person_id", nullable = false)
    private Set<Long> attendeeIds = new LinkedHashSet<>();

    @ElementCollection
    @CollectionTable(name = "meeting_agenda_point", joinColumns = @JoinColumn(name = "meeting_id"))
    @OrderColumn(name = "position")
    @Column(name = "text", nullable = false, length = AGENDA_POINT_MAX_LENGTH)
    private List<String> agendaPoints = new ArrayList<>();
}
