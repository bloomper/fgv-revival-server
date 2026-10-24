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

package nu.fgv.revival.server.closing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface SyncStepRepository extends JpaRepository<SyncStep, Long> {

    List<SyncStep> findByRunIdOrderByIdAsc(Long runId);

    /**
     * The participants of a revival whose membership for the year an earlier run wrote or found: Spexregister does not
     * let the application read memberships, so these steps are what tells that one exists (M7-FR-5).
     */
    @Query("""
            SELECT DISTINCT s.participantId FROM SyncStep s
            WHERE s.operation = nu.fgv.revival.server.closing.SyncOperation.MEMBERSHIP AND s.detail = :year AND s.status IN :statuses
            AND s.participantId IN :participantIds""")
    List<Long> membershipsRecorded(@Param("participantIds") Collection<Long> participantIds, @Param("year") String year,
                                   @Param("statuses") Collection<SyncStepStatus> statuses);
}
