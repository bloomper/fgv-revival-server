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

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Declares only what the log needs, so it has no delete method (constitution IV). Filters run through
 * {@code SpecificationQueries}.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public interface LogEntryRepository extends Repository<LogEntry, Long> {

    LogEntry save(LogEntry entry);

    LogEntry saveAndFlush(LogEntry entry);

    Optional<LogEntry> findById(Long id);

    List<LogEntry> findAllById(Iterable<Long> ids);

    Optional<LogEntry> findByRevivalIdAndNumber(Long revivalId, int number);

    Optional<LogEntry> findByAnswersEntryId(Long answersEntryId);

    List<LogEntry> findByAnswersEntryIdIn(Collection<Long> answersEntryIds);

    boolean existsByRevivalIdAndOriginIn(Long revivalId, Collection<LogEntryOrigin> origins);

    List<LogEntry> findByRevivalIdAndOriginOrderByNumberAsc(Long revivalId, LogEntryOrigin origin);

    List<LogEntry> findByRevivalIdOrderByNumberAsc(Long revivalId);

    List<LogEntry> findByChecklistItemIdOrderByNumberAsc(Long checklistItemId);

    List<LogEntry> findByMeetingIdOrderByNumberAsc(Long meetingId);

    @Query("SELECT DISTINCT t FROM LogEntry e JOIN e.tags t WHERE e.revivalId = :revivalId ORDER BY t")
    List<String> findDistinctTags(Long revivalId);
}
