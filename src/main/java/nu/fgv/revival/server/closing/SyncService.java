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
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.closing.ClosingDtos.SyncFailureDto;
import nu.fgv.revival.server.closing.ClosingDtos.SyncOperationPreviewDto;
import nu.fgv.revival.server.closing.ClosingDtos.SyncParticipantPreviewDto;
import nu.fgv.revival.server.closing.ClosingDtos.SyncPreviewDto;
import nu.fgv.revival.server.closing.ClosingDtos.SyncRunDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantDirectory;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.staffing.StaffingDtos.ParticipantRef;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The participation sync to Spexregister (product spec 5.11, M7-FR-2..8): its preview, starting or resuming it, and its
 * runs. Every method resolves the caller and asks {@link AccessPolicy} first (constitution III). A start checks that the
 * preview the caller confirmed is still what would be done, creates the run and its pending steps in one transaction,
 * and hands the run to {@link SyncWorker} once that has committed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SyncService {

    private final RevivalRepository revivalRepository;
    private final SyncRunRepository runRepository;
    private final SyncStepRepository stepRepository;
    private final ParticipantRepository participantRepository;
    private final ParticipantDirectory participantDirectory;
    private final SyncPlanner planner;
    private final SyncWorker worker;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final EntityManager entityManager;
    private final Clock clock;

    public SyncService(final RevivalRepository revivalRepository, final SyncRunRepository runRepository, final SyncStepRepository stepRepository,
                       final ParticipantRepository participantRepository, final ParticipantDirectory participantDirectory, final SyncPlanner planner,
                       final SyncWorker worker, final CallerService callerService, final AccessPolicy accessPolicy, final EntityManager entityManager,
                       final Clock clock) {
        this.revivalRepository = revivalRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.participantRepository = participantRepository;
        this.participantDirectory = participantDirectory;
        this.planner = planner;
        this.worker = worker;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    private static void afterCommit(final Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }

    private Revival visible(final Caller caller, final Long revivalId) {
        return accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);
    }

    /**
     * What a sync would write now. SPEXREGISTER_UNAVAILABLE when Spexregister cannot be read.
     */
    @Transactional(readOnly = true)
    public SyncPreviewDto preview(final Long revivalId, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireReadSync(caller, revival);

        final String acting = ParticipantDirectory.actingPerson(caller);
        final SyncPlanner.Plan plan = planner.plan(revival, acting);
        final Map<Long, ParticipantDirectory.Named> names = participantDirectory.named(
                Stream.concat(plan.participants().stream().map(SyncPlanner.ParticipantPlan::participant), plan.excluded().stream()).toList(),
                acting, locale);

        return new SyncPreviewDto(revivalId, plan.hash(), plan.spexregisterRevivalId() != null,
                plan.participants().stream().map(participant -> new SyncParticipantPreviewDto(ParticipantRef.of(names.get(participant.participant().getId())),
                        participant.operations().stream().map(operation -> new SyncOperationPreviewDto(operation.operation(), operation.detail(),
                                operation.exists())).toList())).toList(),
                plan.excluded().stream().map(participant -> ParticipantRef.of(names.get(participant.getId()))).toList(),
                revival.getMembershipYear(), lastRun(revivalId));
    }

    /**
     * Starts the sync, or resumes it: the new run's preview leaves out what earlier runs wrote. CONFLICT when a run is
     * running or the preview has changed since the caller saw it.
     */
    @Transactional
    public SyncRunDto start(final Long revivalId, final String previewHash) {
        final Caller caller = callerService.current();
        final Revival revival = visible(caller, revivalId);

        if (revival.isAnonymized()) {
            throw new BadRequestException("error.revival.anonymized");
        }
        accessPolicy.requireSync(caller, revival);
        if (runRepository.existsByRevivalIdAndStatus(revivalId, SyncRunStatus.RUNNING)) {
            throw new ConflictException("error.sync.running");
        }

        final String acting = ParticipantDirectory.actingPerson(caller);
        final SyncPlanner.Plan plan = planner.plan(revival, acting);

        if (!plan.hash().equals(previewHash)) {
            throw new ConflictException("error.sync.previewChanged");
        }
        AuditContext.operation("syncStart");
        if (plan.spexregisterRevivalId() != null && revival.getSpexregisterRevivalId() == null) {
            entityManager.createNativeQuery("UPDATE revival SET spexregister_revival_id = ? WHERE id = ?")
                    .setParameter(1, plan.spexregisterRevivalId())
                    .setParameter(2, revivalId)
                    .executeUpdate();
        }

        final Instant now = Instant.now(clock);
        final List<SyncPlanner.Operation> planned = plan.planned();
        final SyncRun run = new SyncRun();

        run.setRevivalId(revivalId);
        run.setStartedBy(caller.person().getId());
        run.setStartedAt(now);
        run.setPreviewHash(plan.hash());
        run.setStatus(planned.isEmpty() ? SyncRunStatus.DONE : SyncRunStatus.RUNNING);
        run.setFinishedAt(planned.isEmpty() ? now : null);

        final SyncRun saved;

        try {
            saved = runRepository.saveAndFlush(run);
        } catch (final DataIntegrityViolationException _) {
            throw new ConflictException("error.sync.running");
        }

        final List<SyncStep> steps = new ArrayList<>();

        for (final SyncPlanner.Operation operation : planned) {
            final SyncStep step = new SyncStep();

            step.setRunId(saved.getId());
            step.setParticipantId(operation.participantId());
            step.setOperation(operation.operation());
            step.setDetail(operation.detail());
            step.setUpdatedAt(now);
            steps.add(step);
        }
        stepRepository.saveAllAndFlush(steps);
        if (!planned.isEmpty()) {
            afterCommit(() -> worker.submit(saved.getId(), acting));
        }
        return SyncRunDto.of(saved, steps);
    }

    /**
     * A run of a revival the caller reads the sync of; missing otherwise.
     */
    @Transactional(readOnly = true)
    public SyncRunDto run(final Long id) {
        final Caller caller = callerService.current();
        final SyncRun run = runRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(SyncRun.class, id));
        final Revival revival = revivalRepository.findById(run.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canSee(caller, revival) || !accessPolicy.canReadSync(caller, revival)) {
            throw new ResourceNotFoundException(SyncRun.class, id);
        }
        return SyncRunDto.of(run, stepRepository.findByRunIdOrderByIdAsc(id));
    }

    /**
     * The revival's latest run. No caller check: callers have checked the revival.
     */
    @Transactional(readOnly = true)
    public @Nullable SyncRunDto lastRun(final Long revivalId) {
        return runRepository.findFirstByRevivalIdOrderByStartedAtDescIdDesc(revivalId)
                .map(run -> SyncRunDto.of(run, stepRepository.findByRunIdOrderByIdAsc(run.getId())))
                .orElse(null);
    }

    /**
     * The run's failed steps with the participants by name; a participant shows as unknown while Spexregister cannot be
     * read, and as none once the revival is anonymized. No caller check: callers have read the run.
     */
    @Transactional(readOnly = true)
    public List<SyncFailureDto> failures(final SyncRunDto run, final Locale locale) {
        final List<SyncStep> failed = stepRepository.findByRunIdOrderByIdAsc(run.id()).stream()
                .filter(step -> step.getStatus() == SyncStepStatus.FAILED)
                .toList();

        if (failed.isEmpty()) {
            return List.of();
        }

        final List<Participant> participants = participantRepository.findAllById(failed.stream().map(SyncStep::getParticipantId)
                .filter(Objects::nonNull).distinct().toList());
        Map<Long, ParticipantDirectory.Named> names;

        try {
            names = participantDirectory.named(participants, ParticipantDirectory.actingPerson(callerService.current()), locale);
        } catch (final SpexregisterUnavailableException _) {
            names = Map.of();
        }

        final Map<Long, ParticipantDirectory.Named> byId = names;

        return failed.stream()
                .map(step -> new SyncFailureDto(step.getParticipantId() == null || !byId.containsKey(step.getParticipantId()) ? null
                        : ParticipantRef.of(byId.get(step.getParticipantId())), step.getOperation(),
                        Objects.requireNonNullElse(step.getErrorCode(), "REFUSED")))
                .toList();
    }
}
