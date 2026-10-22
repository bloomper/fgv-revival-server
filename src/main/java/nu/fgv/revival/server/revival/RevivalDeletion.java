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
import nu.fgv.revival.server.checklist.Checklist;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryCounter;
import nu.fgv.revival.server.decisionlog.LogImport;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestion;
import nu.fgv.revival.server.meeting.Meeting;
import nu.fgv.revival.server.meeting.MeetingEntryChange;
import nu.fgv.revival.server.meeting.MinutesEmail;
import nu.fgv.revival.server.member.RevivalMember;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Removes a revival with everything that belongs to it (constitution IV). Rows are removed one by one through the
 * entity manager, so that Envers records the removal of every audited row.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Component
class RevivalDeletion {

    private static final String BY_REVIVAL = "SELECT e FROM %s e WHERE e.revivalId = :revivalId";
    private static final String BY_MEETING = "SELECT e FROM %s e WHERE e.meetingId IN (SELECT m.id FROM Meeting m WHERE m.revivalId = :revivalId)";

    private final EntityManager entityManager;

    RevivalDeletion(final EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    void delete(final Revival revival) {
        final Long revivalId = revival.getId();

        find(BY_REVIVAL, LogEntry.class, revivalId).forEach(entry -> entry.setAnswersEntryId(null));
        find(BY_MEETING, MinutesEmail.class, revivalId).forEach(email -> email.setRetryOf(null));
        entityManager.flush();

        remove(BY_MEETING, MinutesEmail.class, revivalId);
        remove(BY_MEETING, MeetingEntryChange.class, revivalId);
        remove(BY_REVIVAL, LogEntry.class, revivalId);
        remove(BY_REVIVAL, LogImport.class, revivalId);
        remove(BY_REVIVAL, LogEntryCounter.class, revivalId);
        remove(BY_REVIVAL, ChecklistItem.class, revivalId);
        remove(BY_REVIVAL, Checklist.class, revivalId);
        remove(BY_REVIVAL, Meeting.class, revivalId);
        remove(BY_REVIVAL, GuideSuggestion.class, revivalId);
        remove(BY_REVIVAL, RevivalGuideVersionChange.class, revivalId);
        remove(BY_REVIVAL, RevivalStatusChange.class, revivalId);
        remove(BY_REVIVAL, RevivalMember.class, revivalId);
        entityManager.remove(revival);
        entityManager.flush();
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
