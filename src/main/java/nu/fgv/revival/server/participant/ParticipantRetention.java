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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.audit.AuditSource;
import nu.fgv.revival.server.revival.RevivalAnonymization;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalStatusChanged;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Deletes participants' data once it is no longer needed (product spec 5.6 and section 7, M6-FR-20): dietary needs the
 * day after the revival's last show, and the names and contact details of pending people who declined or never
 * answered once the revival has left {@code OPEN}; those participants are then closed with their roles. Then it
 * anonymizes the revivals archived or rejected {@value RevivalAnonymization#RETENTION_MONTHS} months ago (M7-FR-12). Runs
 * daily at 03:15 Swedish time as the system, and the second part also as soon as a revival leaves {@code OPEN}. Logs
 * counts only.
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
@Component
@Slf4j
public class ParticipantRetention {

    static final String OPERATION = "participantRetention";
    private static final List<ParticipantResponse> UNANSWERED = List.of(ParticipantResponse.PENDING, ParticipantResponse.NO);

    private final ParticipantRepository repository;
    private final DietaryNeedRepository dietaryRepository;
    private final ParticipantService participantService;
    private final TransactionTemplate transactions;
    private final RevivalAnonymization anonymization;
    private final Clock clock;

    public ParticipantRetention(final ParticipantRepository repository, final DietaryNeedRepository dietaryRepository,
                                final ParticipantService participantService, final PlatformTransactionManager transactionManager,
                                final RevivalAnonymization anonymization, final Clock clock) {
        this.repository = repository;
        this.dietaryRepository = dietaryRepository;
        this.participantService = participantService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.anonymization = anonymization;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Europe/Stockholm")
    public void daily() {
        try {
            transactions.executeWithoutResult(_ -> {
                AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, OPERATION, null));
                deleteDietaryNeeds();
                closeUnansweredPending();
            });
        } catch (final RuntimeException e) {
            log.warn("The daily deletion of participants' data failed: {}", e.getClass().getSimpleName());
        }
        // Each revival in its own transaction, so one failing does not stop the others (M7-FR-12)
        anonymization.anonymizeExpired();
    }

    /**
     * Deletes the dietary needs of every revival whose last show was before today.
     */
    int deleteDietaryNeeds() {
        final int deleted = dietaryRepository.deleteAfterLastShow(LocalDate.now(clock));

        if (deleted > 0) {
            log.info("Deleted the dietary needs of {} participants after their revival's last show", deleted);
        }
        return deleted;
    }

    /**
     * Closes the pending people of revivals that are not {@code OPEN} who declined or never answered.
     */
    int closeUnansweredPending() {
        return close(repository.findPendingToDelete(UNANSWERED, RevivalStatus.OPEN));
    }

    /**
     * As soon as a revival leaves {@code OPEN}, within the status change.
     */
    @EventListener
    public void statusChanged(final RevivalStatusChanged event) {
        if (event.from() == RevivalStatus.OPEN && event.to() != RevivalStatus.OPEN) {
            close(repository.findPendingToDeleteIn(event.revivalId(), UNANSWERED));
        }
    }

    private int close(final List<Participant> participants) {
        participants.forEach(participant -> participantService.close(participant, null));
        if (!participants.isEmpty()) {
            log.info("Deleted {} pending people who declined or never answered before their revival left OPEN", participants.size());
        }
        return participants.size();
    }
}
