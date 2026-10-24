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
package nu.fgv.revival.server.revival;

import jakarta.persistence.EntityManager;
import nu.fgv.revival.server.budget.BudgetLine;
import nu.fgv.revival.server.budget.BudgetScenario;
import nu.fgv.revival.server.budget.BudgetSettings;
import nu.fgv.revival.server.budget.BudgetSnapshot;
import nu.fgv.revival.server.budget.BudgetTransaction;
import nu.fgv.revival.server.budget.ReceiptContent;
import nu.fgv.revival.server.calendar.CalendarFeed;
import nu.fgv.revival.server.checklist.Checklist;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.closing.SyncRun;
import nu.fgv.revival.server.closing.SyncStep;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryCounter;
import nu.fgv.revival.server.decisionlog.LogImport;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestion;
import nu.fgv.revival.server.mailing.Mailing;
import nu.fgv.revival.server.mailing.MailingReminder;
import nu.fgv.revival.server.material.InventoryItem;
import nu.fgv.revival.server.material.Material;
import nu.fgv.revival.server.meeting.Meeting;
import nu.fgv.revival.server.meeting.MeetingEntryChange;
import nu.fgv.revival.server.meeting.MinutesEmail;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.participant.ParticipationSettings;
import nu.fgv.revival.server.person.PersonReferences;
import nu.fgv.revival.server.staffing.ShiftSlot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Removes a revival with everything that belongs to it (constitution IV), its participants and their data, staffing,
 * mailings, materials with their positions, and the inventory among it. Rows are removed one by one through the
 * entity manager, so that Envers records the removal of every audited row; except the rows that are themselves personal
 * data (participants, availability, shifts, mailing recipients and participant roles), which go with their audit rows
 * through {@link RevivalAnonymization#purgePersonal}, so the history keeps no people either (section 7). Its budget is
 * first kept as an anonymous reference (M5-FR-14), unless anonymizing the revival already kept it. Afterwards, the people it referred to lose their login data when
 * nothing else keeps them ({@link PersonReferences}).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Component
class RevivalDeletion {

    private static final String BY_REVIVAL = "SELECT e FROM %s e WHERE e.revivalId = :revivalId";
    private static final String BY_MEETING = "SELECT e FROM %s e WHERE e.meetingId IN (SELECT m.id FROM Meeting m WHERE m.revivalId = :revivalId)";
    private static final String BY_RUN = "SELECT e FROM %s e WHERE e.runId IN (SELECT r.id FROM SyncRun r WHERE r.revivalId = :revivalId)";
    private static final String BY_TRANSACTION = "SELECT e FROM %s e WHERE e.transactionId IN (SELECT t.id FROM BudgetTransaction t WHERE t.revivalId = :revivalId)";

    private final EntityManager entityManager;
    private final BudgetSnapshot budgetSnapshot;
    private final RevivalAnonymization anonymization;
    private final PersonReferences personReferences;

    RevivalDeletion(final EntityManager entityManager, final BudgetSnapshot budgetSnapshot, final RevivalAnonymization anonymization,
                    final PersonReferences personReferences) {
        this.entityManager = entityManager;
        this.budgetSnapshot = budgetSnapshot;
        this.anonymization = anonymization;
        this.personReferences = personReferences;
    }

    void delete(final Revival revival) {
        final Long revivalId = revival.getId();

        final Set<Long> people = personReferences.referencedBy(revivalId);

        if (!revival.isAnonymized()) {
            budgetSnapshot.keep(revival);
        }

        find(BY_REVIVAL, LogEntry.class, revivalId).forEach(entry -> entry.setAnswersEntryId(null));
        find(BY_MEETING, MinutesEmail.class, revivalId).forEach(email -> email.setRetryOf(null));
        entityManager.flush();

        // The sync's steps point at participants; the people go first, with their history
        remove(BY_RUN, SyncStep.class, revivalId);
        remove(BY_REVIVAL, SyncRun.class, revivalId);
        anonymization.purgePersonal(revivalId);

        // Budget lines point at events and decisions, so the budget goes first
        remove(BY_TRANSACTION, ReceiptContent.class, revivalId);
        remove(BY_REVIVAL, BudgetTransaction.class, revivalId);
        remove(BY_REVIVAL, BudgetLine.class, revivalId);
        remove(BY_REVIVAL, BudgetScenario.class, revivalId);
        remove(BY_REVIVAL, BudgetSettings.class, revivalId);
        // Inventory items point at materials; a material's positions go with it
        remove(BY_REVIVAL, InventoryItem.class, revivalId);
        remove(BY_REVIVAL, Material.class, revivalId);
        // Mailings and staffing point at events and participants, so they go before them
        remove(BY_REVIVAL, MailingReminder.class, revivalId);
        remove(BY_REVIVAL, Mailing.class, revivalId);
        remove(BY_REVIVAL, ShiftSlot.class, revivalId);
        remove(BY_MEETING, MinutesEmail.class, revivalId);
        remove(BY_MEETING, MeetingEntryChange.class, revivalId);
        remove(BY_REVIVAL, LogEntry.class, revivalId);
        remove(BY_REVIVAL, LogImport.class, revivalId);
        remove(BY_REVIVAL, LogEntryCounter.class, revivalId);
        remove(BY_REVIVAL, ChecklistItem.class, revivalId);
        // A checklist made for an event points at it, so events go after the checklists
        remove(BY_REVIVAL, Checklist.class, revivalId);
        remove(BY_REVIVAL, Event.class, revivalId);
        remove(BY_REVIVAL, CalendarFeed.class, revivalId);
        remove(BY_REVIVAL, Meeting.class, revivalId);
        remove(BY_REVIVAL, GuideSuggestion.class, revivalId);
        remove(BY_REVIVAL, RevivalGuideVersionChange.class, revivalId);
        remove(BY_REVIVAL, RevivalStatusChange.class, revivalId);
        remove(BY_REVIVAL, ParticipationSettings.class, revivalId);
        remove(BY_REVIVAL, RevivalMember.class, revivalId);
        entityManager.remove(entityManager.find(Revival.class, revivalId));
        entityManager.flush();
        // Login data is kept no longer than the revivals that need it (section 7)
        personReferences.scrubUnreferenced(people);
    }

    private <T> List<T> find(final String query, final Class<T> type, final Long revivalId) {
        return entityManager.createQuery(query.formatted(type.getSimpleName()), type)
                .setParameter("revivalId", revivalId)
                .getResultList();
    }

    private <T> void remove(final String query, final Class<T> type, final Long revivalId) {
        find(query, type, revivalId).forEach(entityManager::remove);
        entityManager.flush();
    }
}
