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

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Declares only what meetings need, so it has no delete method.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface MeetingRepository extends Repository<Meeting, Long> {

    Meeting save(Meeting meeting);

    Meeting saveAndFlush(Meeting meeting);

    Optional<Meeting> findById(Long id);

    List<Meeting> findAllById(Iterable<Long> ids);

    List<Meeting> findByRevivalIdOrderByDateDescIdDesc(Long revivalId);

    Optional<Meeting> findFirstByRevivalIdAndStatus(Long revivalId, MeetingStatus status);
}
