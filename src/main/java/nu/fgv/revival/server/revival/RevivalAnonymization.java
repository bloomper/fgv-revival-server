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
import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.audit.AuditSource;
import nu.fgv.revival.server.budget.BudgetSnapshot;
import nu.fgv.revival.server.budget.BudgetTransaction;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.material.InventoryItem;
import nu.fgv.revival.server.meeting.Meeting;
import nu.fgv.revival.server.meeting.Minutes;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.participant.ParticipantResponse;
import nu.fgv.revival.server.person.PersonReferences;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Removes the people from a revival for good (product spec section 7, M7-FR-10..13), in one transaction, and keeps its
 * work: the guide work, log texts, checklists, events, budget and materials.
 * <ol>
 * <li>The budget is kept as an automatic reference revival, with the number of participants who said yes.</li>
 * <li>Participants are deleted with everything about them: pending people, dietary needs, referrals, availability,
 * shifts and mailing recipients, and their participant and helper roles. Sync steps keep what was written but no longer
 * which participant it was for.</li>
 * <li>Free text naming people goes: free-text owners of log rows, checklist items and inventory, who paid a
 * transaction by name, who else was at a meeting; meeting attendees are cleared, and the minutes keep their decisions
 * and to-dos but no people. Mailing texts stay, as written content. Waiting roles lose their email address.</li>
 * <li>The calendar links of the revival are deleted.</li>
 * <li>The people the revival referred to lose their login data when nothing else keeps them, see
 * {@link PersonReferences}.</li>
 * <li>The revival is marked anonymized; nothing personal can be added to it afterwards.</li>
 * </ol>
 * This is the one place audit rows are removed (with {@link RevivalDeletion}, which uses {@link #purgePersonal}): the
 * rows of participants, availability, shifts, mailing recipients and participant and helper roles are themselves
 * personal data, and section 7 does not let the history keep what was removed, so they are deleted with their
 * {@code _audit} rows, in SQL, in the same transaction. Envers is not involved for them, so no deletion revision is
 * written for rows whose history is then removed. In the history of the rows that stay, the free-text names above are
 * blanked the same way, and the minutes replaced by the scrubbed ones (minutes never change once written).
 * <p>
 * Archived and rejected revivals are anonymized automatically, as the system, {@value #RETENTION_MONTHS} months after
 * they got that status ({@link #anonymizeExpired}, from the daily job). One revival failing does not stop the others.
 * Logs ids and counts only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@Slf4j
public class RevivalAnonymization {

    public static final int RETENTION_MONTHS = 36;
    static final String AUTOMATIC_OPERATION = "revivalAnonymizeAutomatic";
    private static final List<RevivalStatus> FINAL_STATUSES = List.of(RevivalStatus.ARCHIVED, RevivalStatus.REJECTED);
    private static final String PARTICIPANT_ROLES = "('PARTICIPANT', 'HELPER')";

    private final EntityManager entityManager;
    private final BudgetSnapshot budgetSnapshot;
    private final PersonReferences personReferences;
    private final RevivalStatusChangeRepository statusChangeRepository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RevivalAnonymization(final EntityManager entityManager, final BudgetSnapshot budgetSnapshot, final PersonReferences personReferences,
                                final RevivalStatusChangeRepository statusChangeRepository, final PlatformTransactionManager transactionManager,
                                final Clock clock) {
        this.entityManager = entityManager;
        this.budgetSnapshot = budgetSnapshot;
        this.personReferences = personReferences;
        this.statusChangeRepository = statusChangeRepository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * The minutes with their decisions, to-dos and questions, and without anyone: owners, attendees, others present and
     * the recorder.
     */
    static Minutes scrub(final Minutes minutes) {
        return new Minutes(scrub(minutes.decisions()), scrub(minutes.todos()), scrub(minutes.questions()), scrub(minutes.closed()),
                minutes.agendaPoints(), List.of(), null, null);
    }

    private static List<Minutes.Row> scrub(final List<Minutes.Row> rows) {
        return rows.stream()
                .map(row -> new Minutes.Row(row.entryId(), row.number(), row.type(), row.text(), null, null, row.deadline(), row.status()))
                .toList();
    }

    /**
     * Anonymizes the revival, by the person or, when null, automatically. No caller check: the caller has checked. Runs
     * in the caller's transaction.
     */
    void anonymize(final Revival revival, final @Nullable Long by) {
        final Long revivalId = revival.getId();
        final Set<Long> people = new LinkedHashSet<>(personReferences.referencedBy(revivalId));

        if (by != null) {
            people.add(by);
        }

        // 1. The budget as a reference, with who took part
        final Long yes = entityManager.createQuery("""
                        SELECT COUNT(p) FROM Participant p WHERE p.revivalId = :revivalId AND p.removedAt IS NULL AND p.kind = :kind
                        AND p.response = :response""", Long.class)
                .setParameter("revivalId", revivalId)
                .setParameter("kind", ParticipantKind.PARTICIPANT)
                .setParameter("response", ParticipantResponse.YES)
                .getSingleResult();

        budgetSnapshot.keep(revival, yes.intValue());

        // 3. Free text naming people, recorded by Envers as changes
        find("SELECT e FROM LogEntry e WHERE e.revivalId = :revivalId AND e.owner.name IS NOT NULL", LogEntry.class, revivalId)
                .forEach(entry -> entry.setOwner(Owner.none()));
        find("SELECT e FROM ChecklistItem e WHERE e.revivalId = :revivalId AND e.owner.name IS NOT NULL", ChecklistItem.class, revivalId)
                .forEach(item -> item.setOwner(Owner.none()));
        find("SELECT e FROM InventoryItem e WHERE e.revivalId = :revivalId AND e.owner.name IS NOT NULL", InventoryItem.class, revivalId)
                .forEach(item -> item.setOwner(Owner.none()));
        find("SELECT e FROM BudgetTransaction e WHERE e.revivalId = :revivalId AND e.paidBy.name IS NOT NULL", BudgetTransaction.class, revivalId)
                .forEach(transaction -> transaction.setPaidBy(Owner.none()));
        find("SELECT e FROM Meeting e WHERE e.revivalId = :revivalId", Meeting.class, revivalId).forEach(meeting -> {
            meeting.setOthersPresent(null);
            meeting.getAttendeeIds().clear();
            if (meeting.getMinutes() != null) {
                meeting.setMinutes(scrub(meeting.getMinutes()));
            }
        });
        find("SELECT e FROM RevivalMember e WHERE e.revivalId = :revivalId AND e.pendingEmail IS NOT NULL", RevivalMember.class, revivalId)
                .forEach(member -> {
                    if (member.getRemovedAt() == null) {
                        member.setRemovedAt(Instant.now(clock));
                        member.setRemovedBy(by);
                    }
                    member.setPendingEmail(null);
                });
        entityManager.flush();
        for (final String table : List.of("log_entry_audit", "checklist_item_audit", "inventory_item_audit")) {
            update("UPDATE %s SET owner_name = NULL WHERE revival_id = :revivalId AND owner_name IS NOT NULL".formatted(table), revivalId);
        }
        update("UPDATE budget_transaction_audit SET paid_by_name = NULL WHERE revival_id = :revivalId AND paid_by_name IS NOT NULL", revivalId);
        update("UPDATE meeting_audit SET others_present = NULL WHERE revival_id = :revivalId AND others_present IS NOT NULL", revivalId);
        update("""
                UPDATE meeting_audit a JOIN meeting m ON m.id = a.id SET a.minutes = m.minutes
                WHERE m.revival_id = :revivalId AND a.minutes IS NOT NULL""", revivalId);

        // 2. and 6. Participants and everything about them, with their history
        purgePersonal(revivalId);

        // 4. Calendar links (not audited: credentials)
        update("DELETE FROM calendar_feed WHERE revival_id = :revivalId", revivalId);

        // 7. The revival itself
        final Revival current = entityManager.find(Revival.class, revivalId);

        current.setAnonymizedAt(Instant.now(clock));
        current.setAnonymizedBy(by);
        entityManager.flush();

        // 5. Login data of the people nothing else keeps
        personReferences.scrubUnreferenced(people);
        log.info("Anonymized revival {}", revivalId);
    }

    /**
     * Deletes the revival's participants and everything about them, and their participant and helper roles, together
     * with their audit rows, in SQL. Sync steps no longer say which participant they were for. Shared with
     * {@link RevivalDeletion}.
     */
    void purgePersonal(final Long revivalId) {
        entityManager.flush();

        final String participants = "(SELECT p.id FROM participant p WHERE p.revival_id = :revivalId)";
        final String auditedParticipants = "(SELECT pa.id FROM participant_audit pa WHERE pa.revival_id = :revivalId)";
        final String participantMembers = "(SELECT rm.id FROM revival_member rm WHERE rm.revival_id = :revivalId AND rm.role IN %s)"
                .formatted(PARTICIPANT_ROLES);

        update("UPDATE sync_step SET participant_id = NULL WHERE participant_id IN " + participants, revivalId);
        update("UPDATE sync_step_audit SET participant_id = NULL WHERE run_id IN (SELECT r.id FROM sync_run r WHERE r.revival_id = :revivalId)",
                revivalId);
        update("DELETE FROM mailing_recipient WHERE mailing_id IN (SELECT m.id FROM mailing m WHERE m.revival_id = :revivalId)", revivalId);
        update("DELETE FROM shift_assignment WHERE slot_id IN (SELECT s.id FROM shift_slot s WHERE s.revival_id = :revivalId)", revivalId);
        update("DELETE FROM availability WHERE participant_id IN " + participants, revivalId);
        update("DELETE FROM referral WHERE revival_id = :revivalId", revivalId);
        update("DELETE FROM dietary_need WHERE participant_id IN " + participants, revivalId);
        update("DELETE FROM pending_person WHERE participant_id IN " + participants, revivalId);
        update("DELETE FROM participant WHERE revival_id = :revivalId", revivalId);
        update("DELETE FROM revival_member_capability WHERE member_id IN " + participantMembers, revivalId);
        update("DELETE FROM revival_member WHERE revival_id = :revivalId AND role IN " + PARTICIPANT_ROLES, revivalId);

        update("DELETE FROM mailing_recipient_audit WHERE mailing_id IN (SELECT ma.id FROM mailing_audit ma WHERE ma.revival_id = :revivalId)",
                revivalId);
        update("DELETE FROM shift_assignment_audit WHERE participant_id IN " + auditedParticipants
                + " OR slot_id IN (SELECT sa.id FROM shift_slot_audit sa WHERE sa.revival_id = :revivalId)", revivalId);
        update("DELETE FROM availability_audit WHERE participant_id IN " + auditedParticipants, revivalId);
        update("""
                        DELETE FROM revival_member_capability_audit WHERE member_id IN
                        (SELECT rma.id FROM revival_member_audit rma WHERE rma.revival_id = :revivalId AND rma.role IN %s)""".formatted(PARTICIPANT_ROLES),
                revivalId);
        update("""
                DELETE FROM revival_member_audit WHERE id IN (SELECT x.id FROM
                (SELECT DISTINCT rma.id FROM revival_member_audit rma WHERE rma.revival_id = :revivalId AND rma.role IN %s) x)"""
                .formatted(PARTICIPANT_ROLES), revivalId);
        update("DELETE FROM participant_audit WHERE revival_id = :revivalId", revivalId);
        // Rows the entity manager may hold are gone; nothing below may use them
        entityManager.clear();
    }

    /**
     * When the revival will be anonymized automatically: {@value #RETENTION_MONTHS} months after it last got its status, if
     * archived or rejected and not anonymized yet.
     */
    public Optional<LocalDate> automaticOn(final Revival revival) {
        if (revival.isAnonymized() || !FINAL_STATUSES.contains(revival.getStatus())) {
            return Optional.empty();
        }
        return statusChangeRepository.findByRevivalIdOrderByChangedAtDescIdDesc(revival.getId()).stream()
                .filter(change -> change.getToStatus() == revival.getStatus())
                .findFirst()
                .map(change -> change.getChangedAt().atZone(clock.getZone()).plus(Period.ofMonths(RETENTION_MONTHS)).toLocalDate());
    }

    /**
     * Anonymizes, as the system, every revival archived or rejected for {@value #RETENTION_MONTHS} months or more (by the
     * latest change into its current status) that is not anonymized yet, each in its own transaction. Returns how many.
     */
    public int anonymizeExpired() {
        final Instant cutoff = clock.instant().atZone(clock.getZone()).minus(Period.ofMonths(RETENTION_MONTHS)).toInstant();
        final List<Long> due = transactions.execute(_ -> entityManager.createQuery("""
                        SELECT r.id FROM Revival r WHERE r.status IN :statuses AND r.anonymizedAt IS NULL
                        AND (SELECT MAX(c.changedAt) FROM RevivalStatusChange c WHERE c.revivalId = r.id AND c.toStatus = r.status) <= :cutoff
                        ORDER BY r.id""", Long.class)
                .setParameter("statuses", FINAL_STATUSES)
                .setParameter("cutoff", cutoff)
                .getResultList());
        int anonymized = 0;

        for (final Long revivalId : due == null ? List.<Long>of() : due) {
            try {
                transactions.executeWithoutResult(_ -> {
                    AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, AUTOMATIC_OPERATION, null));

                    final Revival revival = entityManager.find(Revival.class, revivalId);

                    if (revival != null && !revival.isAnonymized()) {
                        anonymize(revival, null);
                    }
                });
                anonymized++;
            } catch (final RuntimeException e) {
                log.warn("Automatic anonymization of revival {} failed: {}", revivalId, e.getClass().getSimpleName());
            }
        }
        if (anonymized > 0) {
            log.info("Anonymized {} revivals {} months after they were archived or rejected", anonymized, RETENTION_MONTHS);
        }
        return anonymized;
    }

    private <T> List<T> find(final String query, final Class<T> type, final Long revivalId) {
        return entityManager.createQuery(query, type).setParameter("revivalId", revivalId).getResultList();
    }

    private void update(final String sql, final Long revivalId) {
        entityManager.createNativeQuery(sql).setParameter("revivalId", revivalId).executeUpdate();
    }
}
