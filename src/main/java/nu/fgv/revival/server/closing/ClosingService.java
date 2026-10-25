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
import nu.fgv.revival.server.budget.BudgetDtos;
import nu.fgv.revival.server.budget.BudgetService;
import nu.fgv.revival.server.budget.BudgetSettings;
import nu.fgv.revival.server.budget.BudgetSettingsRepository;
import nu.fgv.revival.server.closing.ClosingDtos.ClosingStatusDto;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntrySummaryDto;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionStatus;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalAnonymization;
import nu.fgv.revival.server.revival.RevivalRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Where a revival stands on closing: the result and the loan from the budget, the surplus decision from the log,
 * memberships, lessons learned as guide suggestions, the sync, and anonymization. Read by whoever reads the revival's
 * work; null for anyone else.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ClosingService {

    /**
     * The tags that mark the decision on the surplus, compared in lower case.
     */
    static final List<String> SURPLUS_TAGS = List.of("överskott", "surplus");

    private final RevivalRepository revivalRepository;
    private final BudgetService budgetService;
    private final BudgetSettingsRepository settingsRepository;
    private final SyncService syncService;
    private final RevivalAnonymization anonymization;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final EntityManager entityManager;

    public ClosingService(final RevivalRepository revivalRepository, final BudgetService budgetService, final BudgetSettingsRepository settingsRepository,
                          final SyncService syncService, final RevivalAnonymization anonymization, final CallerService callerService,
                          final AccessPolicy accessPolicy, final EntityManager entityManager) {
        this.revivalRepository = revivalRepository;
        this.budgetService = budgetService;
        this.settingsRepository = settingsRepository;
        this.syncService = syncService;
        this.anonymization = anonymization;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.entityManager = entityManager;
    }

    /**
     * The revival's closing status, or null when the caller does not read its work. A loan counts as repaid when its
     * repayment date is set, or when there is none.
     */
    @Transactional(readOnly = true)
    public @Nullable ClosingStatusDto closing(final Long revivalId) {
        final Caller caller = callerService.current();
        final Revival revival = revivalRepository.findById(revivalId).orElse(null);

        if (revival == null || !accessPolicy.canSee(caller, revival) || !accessPolicy.canReadRevivalWork(caller, revival)) {
            return null;
        }

        final BudgetDtos.Totals totals = budgetService.totalsOf(revivalId);
        final Optional<BudgetSettings> settings = settingsRepository.findByRevivalId(revivalId);
        final @Nullable BigDecimal loan = settings.map(BudgetSettings::getLoanAmount).orElse(null);
        final boolean repaid = loan == null || loan.signum() == 0 || settings.map(BudgetSettings::getLoanRepaidOn).isPresent();
        final LogEntrySummaryDto surplus = entityManager.createQuery("""
                        SELECT e FROM LogEntry e JOIN e.tags t
                        WHERE e.revivalId = :revivalId AND e.type = :type AND LOWER(t) IN :tags
                        ORDER BY e.number DESC""", LogEntry.class)
                .setParameter("revivalId", revivalId)
                .setParameter("type", LogEntryType.DECISION)
                .setParameter("tags", SURPLUS_TAGS)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(LogEntrySummaryDto::of)
                .orElse(null);
        final Long memberships = entityManager.createQuery("""
                        SELECT COUNT(p) FROM Participant p WHERE p.revivalId = :revivalId AND p.membershipPaid = TRUE AND p.removedAt IS NULL""", Long.class)
                .setParameter("revivalId", revivalId)
                .getSingleResult();
        final Long suggestions = entityManager.createQuery("""
                        SELECT COUNT(s) FROM GuideSuggestion s WHERE s.revivalId = :revivalId AND s.status <> :withdrawn""", Long.class)
                .setParameter("revivalId", revivalId)
                .setParameter("withdrawn", GuideSuggestionStatus.WITHDRAWN)
                .getSingleResult();

        return new ClosingStatusDto(totals.actualResult(), loan, repaid, surplus, revival.getMembershipYear(), memberships.intValue(),
                suggestions.intValue(), syncService.lastRun(revivalId), revival.getAnonymizedAt(), anonymization.automaticOn(revival).orElse(null));
    }
}
