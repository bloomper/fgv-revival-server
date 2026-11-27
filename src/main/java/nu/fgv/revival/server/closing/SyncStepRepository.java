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
     * Whether an earlier run asked Medlemskap for the payment request with this key (the step's detail) for the revival.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM SyncStep s
            WHERE s.operation = nu.fgv.revival.server.closing.SyncOperation.PAYMENT AND s.detail = :key AND s.status IN :statuses
            AND s.runId IN (SELECT r.id FROM SyncRun r WHERE r.revivalId = :revivalId)""")
    boolean paymentRequested(@Param("revivalId") Long revivalId, @Param("key") String key, @Param("statuses") Collection<SyncStepStatus> statuses);

    /**
     * The id of the revival's latest payment request in Medlemskap, if one was made.
     */
    @Query("""
            SELECT s.remoteId FROM SyncStep s
            WHERE s.operation = nu.fgv.revival.server.closing.SyncOperation.PAYMENT AND s.remoteId IS NOT NULL AND s.status IN :statuses
            AND s.runId IN (SELECT r.id FROM SyncRun r WHERE r.revivalId = :revivalId) ORDER BY s.id DESC""")
    List<String> paymentRequestIds(@Param("revivalId") Long revivalId, @Param("statuses") Collection<SyncStepStatus> statuses);
}
