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
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.budget.BudgetSnapshot;
import nu.fgv.revival.server.decisionlog.LogEntryStatus;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.meeting.Minutes;
import nu.fgv.revival.server.person.PersonReferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Anonymization's pure parts: the minutes keep what was decided but no people, and the automatic
 * date is 36 months after the latest change into archived or rejected, with a fixed clock.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class RevivalAnonymizationTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private BudgetSnapshot budgetSnapshot;
    @Mock
    private PersonReferences personReferences;
    @Mock
    private RevivalStatusChangeRepository statusChangeRepository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private RevivalAnonymization anonymization;

    private static RevivalStatusChange change(final RevivalStatus to, final String at) {
        final RevivalStatusChange change = new RevivalStatusChange();

        change.setRevivalId(1L);
        change.setToStatus(to);
        change.setChangedAt(Instant.parse(at));
        return change;
    }

    @BeforeEach
    void setUp() {
        anonymization = new RevivalAnonymization(entityManager, budgetSnapshot, personReferences, statusChangeRepository, transactionManager,
                TestClocks.at(LocalDate.of(2026, 10, 23)));
    }

    @Test
    void minutesKeepTheirDecisionsButNoPeople() {
        final Minutes.Row decision = new Minutes.Row(1L, 1, LogEntryType.DECISION, "Vi kör", 5L, null, null, LogEntryStatus.DONE);
        final Minutes.Row todo = new Minutes.Row(2L, 2, LogEntryType.TODO, "Boka lokal", null, "Kim Extern", LocalDate.of(2028, 1, 1),
                LogEntryStatus.OPEN);
        final Minutes scrubbed = RevivalAnonymization.scrub(new Minutes(List.of(decision), List.of(todo), List.of(), List.of(), List.of("Budget"),
                List.of(5L, 6L), "Kim Extern", 5L));

        assertThat(scrubbed.decisions()).extracting(Minutes.Row::text).containsExactly("Vi kör");
        assertThat(scrubbed.todos()).extracting(Minutes.Row::text, Minutes.Row::deadline).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Boka lokal", LocalDate.of(2028, 1, 1)));
        assertThat(scrubbed.decisions().getFirst().ownerPersonId()).isNull();
        assertThat(scrubbed.todos().getFirst().ownerName()).isNull();
        assertThat(scrubbed.attendeeIds()).isEmpty();
        assertThat(scrubbed.othersPresent()).isNull();
        assertThat(scrubbed.recorderId()).isNull();
        assertThat(scrubbed.agendaPoints()).containsExactly("Budget");
    }

    @Test
    void archivedRevivalsAreAnonymizedThirtySixMonthsAfterTheyWereArchived() {
        final Revival revival = new Revival();

        revival.setId(1L);
        revival.setStatus(RevivalStatus.ARCHIVED);
        when(statusChangeRepository.findByRevivalIdOrderByChangedAtDescIdDesc(1L)).thenReturn(List.of(
                change(RevivalStatus.ARCHIVED, "2026-03-01T10:00:00Z"),
                change(RevivalStatus.SETTLING, "2026-02-01T10:00:00Z"),
                change(RevivalStatus.ARCHIVED, "2025-01-01T10:00:00Z")));

        assertThat(anonymization.automaticOn(revival)).contains(LocalDate.of(2029, 3, 1));

        revival.setAnonymizedAt(Instant.parse("2026-04-01T10:00:00Z"));
        assertThat(anonymization.automaticOn(revival)).isEmpty();
    }

    @Test
    void onlyArchivedAndRejectedRevivalsHaveADate() {
        final Revival revival = new Revival();

        revival.setId(1L);
        revival.setStatus(RevivalStatus.SETTLING);

        assertThat(anonymization.automaticOn(revival)).isEmpty();
    }
}
