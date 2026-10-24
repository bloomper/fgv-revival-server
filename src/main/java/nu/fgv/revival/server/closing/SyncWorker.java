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

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.audit.AuditSource;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.spex.SpexregisterClient;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Executes a run of the participation sync in the background (M7-FR-4): the revival first, then per participant the
 * participation, the task, the actor and the membership. Before each write it reads the spexare's activities again, so
 * what is already there is recorded as {@link SyncStepStatus#EXISTED} instead of written twice. A failed step is
 * recorded with a short code and the run carries on with the other participants; a participant's steps that depend on a
 * failed one are left {@link SyncStepStatus#PENDING} for the next run. Every step is saved in its own short transaction,
 * never across a call to Spexregister.
 * <p>
 * The work runs on a virtual thread (virtual threads are on for the application, and the work is almost all waiting on
 * Spexregister), one per run; a run is bounded by the revival's participants and at most one runs per revival, so no
 * pool or queue is needed. It runs as the person who started it: their security context and audit origin are carried
 * to the thread, so audit columns and revisions name them, and Spexregister is told whom the service acts for. Runs
 * left {@code RUNNING} by a restart are marked failed at startup, so they can be resumed. Logs ids and counts only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@Slf4j
public class SyncWorker implements DisposableBean {

    static final String OPERATION = "syncRun";

    private final SyncRunRepository runRepository;
    private final SyncStepRepository stepRepository;
    private final ParticipantRepository participantRepository;
    private final RevivalRepository revivalRepository;
    private final SpexregisterClient client;
    private final SpexareDirectory directory;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SimpleAsyncTaskExecutor executor;

    public SyncWorker(final SyncRunRepository runRepository, final SyncStepRepository stepRepository, final ParticipantRepository participantRepository,
                      final RevivalRepository revivalRepository, final SpexregisterClient client, final SpexareDirectory directory,
                      final EntityManager entityManager, final PlatformTransactionManager transactionManager, final Clock clock) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.participantRepository = participantRepository;
        this.revivalRepository = revivalRepository;
        this.client = client;
        this.directory = directory;
        this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.executor = new SimpleAsyncTaskExecutor("sync-");
        this.executor.setVirtualThreads(true);
        this.executor.setTaskTerminationTimeout(10_000);
    }

    /**
     * The contract's reasons: SPEXREGISTER_UNAVAILABLE, REFUSED or NOT_FOUND.
     */
    static String reason(final SpexregisterClient.SpexregisterCallException e) {
        return SpexregisterClient.SpexregisterCallException.EXISTS.equals(e.code()) ? SpexregisterClient.SpexregisterCallException.REFUSED : e.code();
    }

    @Override
    public void destroy() {
        executor.close();
    }

    /**
     * Runs the run in the background as the current caller.
     */
    public void submit(final Long runId, final String actingPerson) {
        final SecurityContext security = SecurityContextHolder.getContext();
        final AuditContext.Origin origin = AuditContext.currentOrSystem().withOperation(OPERATION);

        executor.execute(() -> {
            SecurityContextHolder.setContext(security);
            try {
                execute(runId, actingPerson, origin);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    /**
     * Runs left {@code RUNNING} when the application stopped are failed, so starting again resumes them.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        try {
            transactions.executeWithoutResult(_ -> {
                AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, "syncInterrupted", null));
                runRepository.findByStatus(SyncRunStatus.RUNNING).forEach(run -> {
                    run.setStatus(SyncRunStatus.FAILED);
                    run.setFinishedAt(Instant.now(clock));
                    runRepository.saveAndFlush(run);
                    log.info("Marked interrupted sync run {} failed", run.getId());
                });
            });
        } catch (final RuntimeException e) {
            log.warn("Could not mark interrupted sync runs failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Executes the run's pending steps and finishes it. Never throws.
     */
    void execute(final Long runId, final String actingPerson, final AuditContext.Origin origin) {
        try {
            final List<SyncStep> steps = inTransaction(origin, () -> stepRepository.findByRunIdOrderByIdAsc(runId));
            final SyncRun run = inTransaction(origin, () -> runRepository.findById(runId).orElseThrow());
            final Revival revival = inTransaction(origin, () -> revivalRepository.findById(run.getRevivalId()).orElseThrow());
            @Nullable Long remoteRevival = revival.getSpexregisterRevivalId();
            final Map<Long, List<SyncStep>> byParticipant = new LinkedHashMap<>();

            for (final SyncStep step : steps) {
                if (step.getOperation() == SyncOperation.REVIVAL) {
                    remoteRevival = revivalStep(step, revival, actingPerson, origin);
                } else if (step.getParticipantId() != null) {
                    byParticipant.computeIfAbsent(step.getParticipantId(), _ -> new ArrayList<>()).add(step);
                }
            }
            if (remoteRevival != null) {
                for (final Map.Entry<Long, List<SyncStep>> participant : byParticipant.entrySet()) {
                    participantSteps(participant.getKey(), participant.getValue(), remoteRevival, actingPerson, origin);
                }
            }
        } catch (final RuntimeException e) {
            log.warn("Sync run {} stopped: {}", runId, e.getClass().getSimpleName());
        } finally {
            finish(runId, origin);
        }
    }

    private @Nullable Long revivalStep(final SyncStep step, final Revival revival, final String actingPerson, final AuditContext.Origin origin) {
        try {
            final @Nullable Long existing = client.spexRevivals(revival.getSpexId()).stream()
                    .filter(remote -> Objects.equals(remote.year(), revival.getYear()))
                    .map(SpexregisterClient.SpexRevival::id)
                    .findFirst()
                    .orElse(null);
            final Long remote = existing != null ? existing : client.createSpexRevival(revival.getSpexId(), revival.getYear(), actingPerson);

            inTransaction(origin, () -> entityManager.createNativeQuery("UPDATE revival SET spexregister_revival_id = ? WHERE id = ?")
                    .setParameter(1, remote)
                    .setParameter(2, revival.getId())
                    .executeUpdate());
            record(step, existing != null ? SyncStepStatus.EXISTED : SyncStepStatus.DONE, String.valueOf(remote), null, origin);
            return remote;
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            record(step, SyncStepStatus.FAILED, null, reason(e), origin);
            return null;
        }
    }

    private void participantSteps(final Long participantId, final List<SyncStep> steps, final Long remoteRevival, final String actingPerson,
                                  final AuditContext.Origin origin) {
        final @Nullable Participant participant = inTransaction(origin, () -> participantRepository.findById(participantId)).orElse(null);

        if (participant == null || participant.getSpexareId() == null || participant.isRemoved()) {
            steps.forEach(step -> record(step, SyncStepStatus.FAILED, null, SpexregisterClient.SpexregisterCallException.NOT_FOUND, origin));
            return;
        }

        final Long spexareId = participant.getSpexareId();
        final SpexregisterClient.@Nullable SpexareActivities fresh;

        try {
            fresh = client.spexareActivities(List.of(spexareId), actingPerson).stream()
                    .filter(spexare -> String.valueOf(spexareId).equals(spexare.id()))
                    .findFirst().orElse(null);
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            steps.forEach(step -> record(step, SyncStepStatus.FAILED, null, reason(e), origin));
            return;
        }
        if (fresh == null) {
            steps.forEach(step -> record(step, SyncStepStatus.FAILED, null, SpexregisterClient.SpexregisterCallException.NOT_FOUND, origin));
            return;
        }

        final SpexregisterClient.@Nullable ActivityDetail activity = SyncPlanner.activityFor(fresh, remoteRevival);
        final SpexregisterClient.@Nullable TaskActivity task = SyncPlanner.taskFor(activity, participant.getPositionTaskId());
        @Nullable Long activityId = activity == null ? null : Long.valueOf(activity.id());
        @Nullable Long taskActivityId = task == null ? null : Long.valueOf(task.id());

        try {
            for (final SyncStep step : steps) {
                try {
                    switch (step.getOperation()) {
                        case PARTICIPATION -> {
                            if (activityId != null) {
                                record(step, SyncStepStatus.EXISTED, String.valueOf(activityId), null, origin);
                            } else {
                                // An activity without a spex is one an earlier run created before it failed: use it again
                                final @Nullable Long empty = fresh.all().stream()
                                        .filter(candidate -> candidate.spexId() == null && candidate.tasks().isEmpty())
                                        .map(candidate -> Long.valueOf(candidate.id()))
                                        .findFirst().orElse(null);
                                final Long created = empty != null ? empty : client.createActivity(spexareId, actingPerson);

                                client.createSpexActivity(spexareId, created, remoteRevival, actingPerson);
                                activityId = created;
                                record(step, SyncStepStatus.DONE, String.valueOf(created), null, origin);
                            }
                        }
                        case TASK -> {
                            if (activityId == null || participant.getPositionTaskId() == null) {
                                continue;
                            }
                            if (taskActivityId != null) {
                                record(step, SyncStepStatus.EXISTED, String.valueOf(taskActivityId), null, origin);
                            } else {
                                taskActivityId = client.createTaskActivity(spexareId, activityId, participant.getPositionTaskId(), actingPerson);
                                record(step, SyncStepStatus.DONE, String.valueOf(taskActivityId), null, origin);
                            }
                        }
                        case ACTOR -> {
                            if (activityId == null || taskActivityId == null) {
                                continue;
                            }
                            if (task != null && !task.allActors().isEmpty()) {
                                record(step, SyncStepStatus.EXISTED, task.allActors().getFirst().id(), null, origin);
                            } else {
                                final Long actor = client.createActor(spexareId, activityId, taskActivityId,
                                        Objects.requireNonNullElse(participant.getVocalPartId(), SpexregisterClient.UNKNOWN_VOCAL),
                                        participant.getRoleName() == null ? null : participant.getRoleName().strip(), actingPerson);

                                record(step, SyncStepStatus.DONE, String.valueOf(actor), null, origin);
                            }
                        }
                        case MEMBERSHIP -> {
                            try {
                                final Long membership = client.createMembership(spexareId, Integer.parseInt(Objects.requireNonNull(step.getDetail())),
                                        actingPerson);

                                record(step, SyncStepStatus.DONE, String.valueOf(membership), null, origin);
                            } catch (final SpexregisterClient.SpexregisterCallException e) {
                                if (!SpexregisterClient.SpexregisterCallException.EXISTS.equals(e.code())) {
                                    throw e;
                                }
                                record(step, SyncStepStatus.EXISTED, null, null, origin);
                            }
                        }
                        case REVIVAL -> {
                            // Handled before the participants
                        }
                    }
                } catch (final SpexregisterClient.SpexregisterCallException e) {
                    record(step, SyncStepStatus.FAILED, null, reason(e), origin);
                }
            }
        } finally {
            directory.evict(spexareId);
        }
    }

    private void record(final SyncStep step, final SyncStepStatus status, final @Nullable String remoteId, final @Nullable String errorCode,
                        final AuditContext.Origin origin) {
        inTransaction(origin, () -> {
            final SyncStep current = stepRepository.findById(step.getId()).orElseThrow();

            current.setStatus(status);
            current.setRemoteId(remoteId);
            current.setErrorCode(errorCode);
            current.setUpdatedAt(Instant.now(clock));
            return stepRepository.saveAndFlush(current);
        });
    }

    private void finish(final Long runId, final AuditContext.Origin origin) {
        try {
            inTransaction(origin, () -> {
                final SyncRun run = runRepository.findById(runId).orElseThrow();
                final List<SyncStep> steps = stepRepository.findByRunIdOrderByIdAsc(runId);
                final long failed = steps.stream().filter(step -> step.getStatus() == SyncStepStatus.FAILED).count();
                final long pending = steps.stream().filter(step -> step.getStatus() == SyncStepStatus.PENDING).count();

                run.setStatus(failed == 0 && pending == 0 ? SyncRunStatus.DONE : SyncRunStatus.FAILED);
                run.setFinishedAt(Instant.now(clock));
                log.info("Sync run {} finished: {} steps, {} failed, {} left", runId, steps.size(), failed, pending);
                return runRepository.saveAndFlush(run);
            });
        } catch (final RuntimeException e) {
            log.warn("Could not finish sync run {}: {}", runId, e.getClass().getSimpleName());
        }
    }

    private <T> T inTransaction(final AuditContext.Origin origin, final Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(_ -> {
            AuditContext.stamp(origin);
            return work.get();
        }));
    }
}
