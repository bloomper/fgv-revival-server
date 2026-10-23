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

package nu.fgv.revival.server.member;

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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A role in one revival, held by a person or waiting on an email address. Removing closes the row; it is never deleted
 * (constitution IV). The waiting email is not audited and is cleared on removal or claim (FR-028).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Entity
@Table(name = "revival_member")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class RevivalMember extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "person_id")
    private @Nullable Long personId;

    @NotAudited
    @Column(name = "pending_email")
    private @Nullable String pendingEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private RevivalRole role;

    @Column(name = "removed_at")
    private @Nullable Instant removedAt;

    @Column(name = "removed_by")
    private @Nullable Long removedBy;

    @NotAudited
    @Column(name = "active_person_id", insertable = false, updatable = false)
    private @Nullable Long activePersonId;

    @ElementCollection
    @CollectionTable(name = "revival_member_capability", joinColumns = @JoinColumn(name = "member_id"))
    @Column(name = "capability", nullable = false, length = 36)
    private Set<String> capabilities = new HashSet<>();

    public MemberState getState() {
        return personId == null ? MemberState.WAITING : MemberState.ACTIVE;
    }

    public boolean isRemoved() {
        return removedAt != null;
    }

}
