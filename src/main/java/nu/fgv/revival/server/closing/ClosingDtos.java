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

import nu.fgv.revival.server.decisionlog.LogEntrySummaryDto;
import nu.fgv.revival.server.staffing.StaffingDtos.ParticipantRef;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * GraphQL's closing types (closing.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ClosingDtos {

    private ClosingDtos() {
    }

    public record SyncPreviewDto(Long revivalId, String hash, boolean spexregisterRevivalExists,
                                 List<SyncParticipantPreviewDto> participants,
                                 List<ParticipantRef> excluded, @Nullable Integer membershipYear,
                                 @Nullable SyncRunDto lastRun) {
    }

    public record SyncParticipantPreviewDto(ParticipantRef participant, List<SyncOperationPreviewDto> operations) {
    }

    public record SyncOperationPreviewDto(SyncOperation operation, @Nullable String detail, boolean exists) {
    }

    /**
     * A run; {@code done} counts steps written or found, {@code startedBy} is a person id the GraphQL layer resolves.
     */
    public record SyncRunDto(Long id, Long revivalId, SyncRunStatus status, @Nullable Long startedById,
                             Instant startedAt, @Nullable Instant finishedAt,
                             int total, int done, int failed) {

        static SyncRunDto of(final SyncRun run, final List<SyncStep> steps) {
            return new SyncRunDto(run.getId(), run.getRevivalId(), run.getStatus(), run.getStartedBy(), run.getStartedAt(), run.getFinishedAt(),
                    steps.size(), (int) steps.stream().filter(step -> step.getStatus().isComplete()).count(),
                    (int) steps.stream().filter(step -> step.getStatus() == SyncStepStatus.FAILED).count());
        }
    }

    public record SyncFailureDto(@Nullable ParticipantRef participant, SyncOperation operation, String reason) {
    }

    public record ClosingStatusDto(@Nullable BigDecimal actualResult, @Nullable BigDecimal loanAmount,
                                   boolean loanRepaid,
                                   @Nullable LogEntrySummaryDto surplusDecision, @Nullable Integer membershipYear,
                                   int membershipsPaid, int suggestions,
                                   @Nullable SyncRunDto lastSync, @Nullable Instant anonymizedAt,
                                   @Nullable LocalDate autoAnonymizeOn) {
    }
}
