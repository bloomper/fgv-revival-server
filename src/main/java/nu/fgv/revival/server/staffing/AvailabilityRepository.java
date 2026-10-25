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

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Availability answers. Deleting one is clearing the person's own answer.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface AvailabilityRepository extends Repository<Availability, Long> {

    Availability saveAndFlush(Availability availability);

    void delete(Availability availability);

    void flush();

    Optional<Availability> findByParticipantIdAndEventIdAndDay(Long participantId, Long eventId, LocalDate day);

    List<Availability> findByParticipantId(Long participantId);

    List<Availability> findByEventIdIn(Collection<Long> eventIds);
}
