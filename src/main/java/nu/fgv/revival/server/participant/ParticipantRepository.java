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

import nu.fgv.revival.server.revival.RevivalStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Declares only what participants need, so it has no delete method: a participant is removed by closing the row
 * (constitution IV).
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
public interface ParticipantRepository extends Repository<Participant, Long> {

    Participant saveAndFlush(Participant participant);

    Optional<Participant> findById(Long id);

    List<Participant> findAllById(Iterable<Long> ids);

    List<Participant> findByRevivalIdOrderByIdAsc(Long revivalId);

    List<Participant> findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(Long revivalId);

    Optional<Participant> findByMemberIdAndRemovedAtIsNull(Long memberId);

    List<Participant> findByMemberIdIn(Collection<Long> memberIds);

    boolean existsByRevivalIdAndSpexareIdAndRemovedAtIsNull(Long revivalId, Long spexareId);

    /**
     * Pending people, not removed, with one of the responses, of revivals in any status but the given one.
     */
    @Query("""
            SELECT p FROM Participant p WHERE p.removedAt IS NULL AND p.spexareId IS NULL AND p.response IN :responses
            AND p.revivalId IN (SELECT r.id FROM Revival r WHERE r.status <> :status)""")
    List<Participant> findPendingToDelete(@Param("responses") Collection<ParticipantResponse> responses, @Param("status") RevivalStatus status);

    @Query("""
            SELECT p FROM Participant p WHERE p.removedAt IS NULL AND p.spexareId IS NULL AND p.response IN :responses
            AND p.revivalId = :revivalId""")
    List<Participant> findPendingToDeleteIn(@Param("revivalId") Long revivalId, @Param("responses") Collection<ParticipantResponse> responses);
}
