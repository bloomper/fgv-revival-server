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

package nu.fgv.revival.server.person;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every column that refers to a person, and the rule for scrubbing a person's login data when the revivals they took
 * part in are anonymized (product spec section 7, M7-FR-11). An integration test fails when a foreign key to
 * {@code person} appears that is not listed here.
 * <p>
 * A reference is either revival data, reached from a revival's id, or global: the super admin's work outside any
 * revival, such as publishing a guide version or deciding a suggestion. A person referred to by an anonymized revival
 * is scrubbed when every remaining reference to them is revival data of revivals that are anonymized (deleted revivals
 * leave none), they hold no global reference, and they did not create or last change a global record or a revival that
 * is not anonymized ({@code created_by}/{@code last_modified_by} hold their Keycloak subject). Scrubbing keeps the row,
 * so the references stay valid, and replaces what identifies them: the external id becomes "anonymized-" and a random
 * UUID (so their next login makes a new row), the email {@code anonymized-<id>@invalid}, unverified, and the display
 * name is removed. Names then show as "Unknown person".
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@Slf4j
public class PersonReferences {

    /**
     * Records that are not revival data, whose {@code created_by} and {@code last_modified_by} keep a person. Reference
     * revivals are left out: the automatic ones are made by whoever deletes or anonymizes a revival.
     */
    static final List<String> GLOBAL_SUBJECT_TABLES = List.of("guide_version", "guide_chapter", "guide_section", "guide_item",
            "checklist_template", "checklist_template_item", "mailing_template", "event_type", "event_type_budget_line", "budget_category",
            "capability", "guide_item_type", "link_service", "spex_calendar_date");
    private static final String OWN = "t.revival_id";
    private static final String VIA_MEETING = "(SELECT m.revival_id FROM meeting m WHERE m.id = t.meeting_id)";

    /**
     * Every person reference in the schema.
     */
    public static final List<Reference> REFERENCES = List.of(
            new Reference("revival", "anonymized_by", "t.id", true),
            new Reference("revival_status_change", "changed_by", OWN, true),
            new Reference("revival_member", "person_id", OWN, true),
            new Reference("revival_member", "removed_by", OWN, true),
            new Reference("revival_guide_version_change", "changed_by", OWN, true),
            new Reference("guide_suggestion", "author_id", OWN, true),
            new Reference("checklist_item", "state_changed_by", OWN, true),
            new Reference("checklist_item", "owner_person_id", OWN, true),
            new Reference("meeting", "recorder_person_id", OWN, true),
            new Reference("meeting", "closed_by", OWN, true),
            new Reference("meeting_attendee", "person_id", VIA_MEETING, true),
            new Reference("minutes_email", "sent_by", VIA_MEETING, true),
            new Reference("minutes_email_recipient", "person_id",
                    "(SELECT m.revival_id FROM minutes_email e JOIN meeting m ON m.id = e.meeting_id WHERE e.id = t.email_id)", true),
            new Reference("log_entry", "owner_person_id", OWN, true),
            new Reference("meeting_entry_change", "changed_by", VIA_MEETING, true),
            new Reference("calendar_feed", "person_id", OWN, true),
            new Reference("participant", "fee_paid_by", OWN, true),
            new Reference("participant", "removed_by", OWN, true),
            new Reference("shift_assignment", "assigned_by", "(SELECT s.revival_id FROM shift_slot s WHERE s.id = t.slot_id)", true),
            new Reference("mailing", "created_by_person", OWN, true),
            new Reference("mailing", "sent_by_person", OWN, true),
            new Reference("mailing_recipient", "person_id", "(SELECT m.revival_id FROM mailing m WHERE m.id = t.mailing_id)", true),
            new Reference("inventory_item", "owner_person_id", OWN, true),
            new Reference("inventory_item", "removed_by", OWN, false),
            new Reference("material", "removed_by", OWN, false),
            new Reference("budget_line", "removed_by", OWN, false),
            new Reference("budget_transaction", "paid_by_person_id", OWN, false),
            new Reference("budget_transaction", "reimbursed_by", OWN, false),
            new Reference("budget_transaction", "cancelled_by", OWN, false),
            new Reference("sync_run", "started_by", OWN, true),
            // Global: the super admin's guide work
            new Reference("guide_version", "published_by", null, true),
            new Reference("guide_suggestion", "decided_by", null, true));
    private final EntityManager entityManager;

    public PersonReferences(final EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * The people the revival's data refers to.
     */
    public Set<Long> referencedBy(final Long revivalId) {
        final Set<Long> ids = new LinkedHashSet<>();

        for (final Reference reference : REFERENCES) {
            if (reference.revivalOf() != null) {
                ids.addAll(ids("SELECT t.%s FROM %s t WHERE t.%s IS NOT NULL AND %s = :revivalId"
                        .formatted(reference.column(), reference.table(), reference.column(), reference.revivalOf()), revivalId, null));
            }
        }
        return ids;
    }

    /**
     * Scrubs those of the people who are no longer referred to by anything but anonymized revivals, and returns how many.
     */
    public int scrubUnreferenced(final Collection<Long> candidates) {
        if (candidates.isEmpty()) {
            return 0;
        }

        final Set<Long> kept = new LinkedHashSet<>();

        for (final Reference reference : REFERENCES) {
            final String sql = reference.revivalOf() == null
                    ? "SELECT t.%s FROM %s t WHERE t.%s IN (:ids)".formatted(reference.column(), reference.table(), reference.column())
                    : "SELECT t.%s FROM %s t WHERE t.%s IN (:ids) AND %s IN (SELECT r.id FROM revival r WHERE r.anonymized_at IS NULL)"
                    .formatted(reference.column(), reference.table(), reference.column(), reference.revivalOf());

            kept.addAll(ids(sql, null, candidates));
        }

        final List<String> subjectQueries = new ArrayList<>();

        GLOBAL_SUBJECT_TABLES.forEach(table -> subjectQueries.add(
                "SELECT p.id FROM person p WHERE p.id IN (:ids) AND EXISTS (SELECT 1 FROM %s t WHERE t.created_by = p.external_id OR t.last_modified_by = p.external_id)"
                        .formatted(table)));
        subjectQueries.add("""
                SELECT p.id FROM person p WHERE p.id IN (:ids) AND EXISTS (SELECT 1 FROM revival t WHERE t.anonymized_at IS NULL
                AND (t.created_by = p.external_id OR t.last_modified_by = p.external_id))""");
        subjectQueries.forEach(sql -> kept.addAll(ids(sql, null, candidates)));

        final List<Long> scrubbed = candidates.stream().filter(id -> !kept.contains(id)).distinct().toList();

        if (scrubbed.isEmpty()) {
            return 0;
        }
        entityManager.flush();

        final int count = entityManager.createNativeQuery("""
                        UPDATE person SET external_id = CONCAT('anonymized-', UUID()), email = CONCAT('anonymized-', id, '@invalid'),
                            email_verified = FALSE, display_name = NULL
                        WHERE id IN (:ids)""")
                .setParameter("ids", scrubbed)
                .executeUpdate();

        log.info("Scrubbed the login data of {} people", count);
        return count;
    }

    @SuppressWarnings("unchecked")
    private Set<Long> ids(final String sql, final @Nullable Long revivalId, final @Nullable Collection<Long> candidates) {
        final var query = entityManager.createNativeQuery(sql);

        if (revivalId != null) {
            query.setParameter("revivalId", revivalId);
        }
        if (candidates != null) {
            query.setParameter("ids", candidates);
        }
        return ((List<Object>) query.getResultList()).stream()
                .map(value -> ((Number) value).longValue())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * A column holding a person id. {@code revivalOf} is the SQL giving the revival of row {@code t}, or null for a
     * global reference; {@code foreignKey} whether the database declares it.
     */
    public record Reference(String table, String column, @Nullable String revivalOf, boolean foreignKey) {
    }
}
