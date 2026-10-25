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

package nu.fgv.revival.server.staffing;

import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Who holds which slot.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ShiftAssignmentRepository extends Repository<ShiftAssignment, Long> {

    ShiftAssignment saveAndFlush(ShiftAssignment assignment);

    void delete(ShiftAssignment assignment);

    void flush();

    Optional<ShiftAssignment> findBySlotIdAndParticipantId(Long slotId, Long participantId);

    List<ShiftAssignment> findBySlotIdIn(Collection<Long> slotIds);

    List<ShiftAssignment> findByParticipantId(Long participantId);

    boolean existsBySlotId(Long slotId);
}
