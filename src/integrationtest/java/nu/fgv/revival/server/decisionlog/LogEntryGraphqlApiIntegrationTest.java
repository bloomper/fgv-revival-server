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


package nu.fgv.revival.server.decisionlog;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision log over GraphQL against MySQL and Keycloak (US1, FR-001–FR-015, SC-002, SC-006).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class LogEntryGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String FIELDS = "id number date type text status overdue comment tags origin version createdBy "
            + "owner { name person { id displayName active } } answers { id number } answeredBy { id number }";
    private static final String CREATE = "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { " + FIELDS + " } }";
    private static final String UPDATE = "mutation($input: LogEntryUpdateInput!) { logEntryUpdate(input: $input) { " + FIELDS + " } }";
    private static final String ANSWER = "mutation($input: LogEntryAnswerInput!) { logEntryAnswer(input: $input) { question { " + FIELDS
            + " } decision { " + FIELDS + " } } }";
    private static final String LIST = "query($revivalId: ID!, $filter: LogEntryFilter) { logEntries(revivalId: $revivalId, filter: $filter) { number } }";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Stockholm"));

    private long revival;

    @BeforeEach
    void revival() {
        revival = organisingRevival();
    }

    private long create(final String user, final Map<String, Object> fields) {
        final Map<String, Object> input = new HashMap<>(fields);

        input.put("revivalId", revival);
        return Long.parseLong(execute(user, CREATE, Map.of("input", input)).path("logEntryCreate.id").entity(String.class).get());
    }

    private long rowVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM log_entry WHERE id = ?").param(id).query(Long.class).single();
    }

    private List<Integer> numbers(final String user, final Map<String, Object> filter) {
        return execute(user, LIST, Map.of("revivalId", revival, "filter", filter)).path("logEntries[*].number").entityList(Integer.class).get();
    }

    @Test
    void rowsAreNumberedPerRevivalAndStartOpenToday() {
        execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "Boka RunAn")))
                .path("logEntryCreate.number").entity(Integer.class).isEqualTo(1)
                .path("logEntryCreate.status").entity(String.class).isEqualTo("OPEN")
                .path("logEntryCreate.date").entity(String.class).isEqualTo(TODAY.toString())
                .path("logEntryCreate.origin").entity(String.class).isEqualTo("MANUAL")
                .path("logEntryCreate.createdBy").entity(String.class).isEqualTo("Stina Styrgrupp");
        create(DIRECTEUR, Map.of("type", "DECISION", "text", "Vi spelar i februari"));

        final long other = createRevival(ALI_BABA, 2029, "Ali Baba 2029");

        assertThat(jdbcClient.sql("SELECT last_number FROM log_entry_counter WHERE revival_id = ?").param(other).query(Integer.class).single()).isZero();
        assertThat(numbers(STEERING_GROUP, Map.of())).containsExactly(1, 2);
    }

    @Test
    void rowsCreatedAtTheSameTimeGetConsecutiveNumbers() throws Exception {
        final int writers = 6;
        final CountDownLatch start = new CountDownLatch(1);
        final List<Callable<Long>> tasks = new ArrayList<>();

        token(STEERING_GROUP);
        token(DIRECTEUR);
        for (int i = 0; i < writers; i++) {
            final String user = i % 2 == 0 ? STEERING_GROUP : DIRECTEUR;
            final int index = i;

            tasks.add(() -> {
                start.await();
                return create(user, Map.of("type", "TODO", "text", "Rad " + index));
            });
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(writers)) {
            final List<Future<Long>> futures = tasks.stream().map(executor::submit).toList();

            start.countDown();
            for (final Future<Long> future : futures) {
                future.get();
            }
        }
        assertThat(numbers(STEERING_GROUP, Map.of())).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void ownersAreMembersOrFreeTextNames() {
        final long stina = personId(STEERING_GROUP);

        execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "a",
                "owner", Map.of("personId", stina))))
                .path("logEntryCreate.owner.person.displayName").entity(String.class).isEqualTo("Stina Styrgrupp")
                .path("logEntryCreate.owner.person.active").entity(Boolean.class).isEqualTo(true);
        execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "b",
                "owner", Map.of("name", " Sam på lokalen "))))
                .path("logEntryCreate.owner.name").entity(String.class).isEqualTo("Sam på lokalen");

        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "c",
                "owner", Map.of("personId", personId(SUPER_ADMIN))))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "c",
                "owner", Map.of("personId", stina, "name", "Sam")))), "BAD_REQUEST");
    }

    @Test
    void filtersCombineAndKeepNumberOrder() {
        final long stina = personId(STEERING_GROUP);

        create(STEERING_GROUP, Map.of("type", "TODO", "text", "Boka lokal", "owner", Map.of("personId", stina),
                "deadline", TODAY.minusDays(1).toString(), "tags", List.of("lokal")));
        create(STEERING_GROUP, Map.of("type", "QUESTION", "text", "Var spelar vi?", "tags", List.of("lokal", "budget")));
        create(STEERING_GROUP, Map.of("type", "DECISION", "text", "Spexpengen är 1500 kr", "status", "DONE",
                "deadline", TODAY.minusDays(5).toString()));
        create(STEERING_GROUP, Map.of("type", "TODO", "text", "Ring Kåren", "status", "WAITING", "owner", Map.of("name", "Sam"),
                "deadline", TODAY.minusDays(2).toString(), "comment", "Väntar på svar från kåren"));

        assertThat(numbers(STEERING_GROUP, Map.of("mine", true))).containsExactly(1);
        assertThat(numbers(DIRECTEUR, Map.of("mine", true))).isEmpty();
        assertThat(numbers(STEERING_GROUP, Map.of("open", true))).containsExactly(1, 2, 4);
        assertThat(numbers(STEERING_GROUP, Map.of("overdue", true))).containsExactly(1, 4);
        assertThat(numbers(STEERING_GROUP, Map.of("overdue", true, "mine", true))).containsExactly(1);
        assertThat(numbers(STEERING_GROUP, Map.of("types", List.of("QUESTION", "DECISION")))).containsExactly(2, 3);
        assertThat(numbers(STEERING_GROUP, Map.of("tags", List.of("budget", "lokal")))).containsExactly(1, 2);
        assertThat(numbers(STEERING_GROUP, Map.of("text", "KAREN"))).containsExactly(4);
        assertThat(numbers(STEERING_GROUP, Map.of("text", "sam"))).containsExactly(4);
        assertThat(numbers(STEERING_GROUP, Map.of("text", "100%"))).isEmpty();
        assertThat(execute(STEERING_GROUP, "query($id: ID!) { logTags(revivalId: $id) }", Map.of("id", revival))
                .path("logTags").entityList(String.class).get()).containsExactly("budget", "lokal");
    }

    @Test
    void overdueIsShownOnTheRow() {
        execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "x",
                "deadline", TODAY.minusDays(1).toString())))
                .path("logEntryCreate.overdue").entity(Boolean.class).isEqualTo(true);
        execute(STEERING_GROUP, CREATE, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "x",
                "deadline", TODAY.toString())))
                .path("logEntryCreate.overdue").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void anyFieldButTheNumberChangesAndStaleVersionsConflict() {
        final long id = create(STEERING_GROUP, Map.of("type", "TODO", "text", "x"));
        final long version = rowVersion(id);

        execute(DIRECTEUR, UPDATE, Map.of("input", Map.of("id", id, "version", version, "status", "CANCELLED", "text", "y",
                "comment", "Inte aktuellt")))
                .path("logEntryUpdate.status").entity(String.class).isEqualTo("CANCELLED")
                .path("logEntryUpdate.text").entity(String.class).isEqualTo("y")
                .path("logEntryUpdate.number").entity(Integer.class).isEqualTo(1);
        expectError(execute(DIRECTEUR, UPDATE, Map.of("input", Map.of("id", id, "version", version, "text", "z"))), "CONFLICT");
        execute(DIRECTEUR, UPDATE, Map.of("input", Map.of("id", id, "version", rowVersion(id), "clearComment", true, "status", "OPEN")))
                .path("logEntryUpdate.comment").valueIsNull()
                .path("logEntryUpdate.status").entity(String.class).isEqualTo("OPEN");
    }

    @Test
    void answeringAQuestionLinksANewDecision() {
        final long question = create(STEERING_GROUP, Map.of("type", "QUESTION", "text", "Var spelar vi?"));

        execute(STEERING_GROUP, ANSWER, Map.of("input", Map.of("questionId", question, "questionVersion", rowVersion(question),
                "text", "Vi spelar i RunAn")))
                .path("logEntryAnswer.question.status").entity(String.class).isEqualTo("DONE")
                .path("logEntryAnswer.question.answeredBy.number").entity(Integer.class).isEqualTo(2)
                .path("logEntryAnswer.decision.type").entity(String.class).isEqualTo("DECISION")
                .path("logEntryAnswer.decision.answers.number").entity(Integer.class).isEqualTo(1);
        expectError(execute(STEERING_GROUP, ANSWER, Map.of("input", Map.of("questionId", question, "questionVersion",
                rowVersion(question), "text", "Igen"))), "BAD_REQUEST");
    }

    @Test
    void superAdminsAndObserversReadButNeverWrite() {
        final long id = create(STEERING_GROUP, Map.of("type", "QUESTION", "text", "x"));

        for (final String user : List.of(SUPER_ADMIN, OBSERVER)) {
            assertThat(numbers(user, Map.of())).containsExactly(1);
            expectError(execute(user, CREATE, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "x"))), "FORBIDDEN");
            expectError(execute(user, UPDATE, Map.of("input", Map.of("id", id, "version", rowVersion(id), "text", "y"))), "FORBIDDEN");
            expectError(execute(user, ANSWER, Map.of("input", Map.of("questionId", id, "questionVersion", rowVersion(id), "text", "y"))),
                    "FORBIDDEN");
        }
    }

    @Test
    void someoneWithoutARoleGetsNotFound() {
        final long id = create(STEERING_GROUP, Map.of("type", "TODO", "text", "x"));

        login(PLAIN_USER);
        expectError(execute(PLAIN_USER, LIST, Map.of("revivalId", revival)), "NOT_FOUND");
        expectError(execute(PLAIN_USER, "query($id: ID!) { logEntry(id: $id) { id } }", Map.of("id", id)), "NOT_FOUND");
        expectError(execute(PLAIN_USER, CREATE, Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "x"))), "NOT_FOUND");
    }

    @Test
    void steeringGroupLosesTheLogWhenTheRevivalIsSettling() {
        create(STEERING_GROUP, Map.of("type", "TODO", "text", "x"));
        setStatus(revival, "SETTLING");

        expectError(execute(STEERING_GROUP, LIST, Map.of("revivalId", revival)), "NOT_FOUND");
        assertThat(numbers(DIRECTEUR, Map.of())).containsExactly(1);
        create(DIRECTEUR, Map.of("type", "TODO", "text", "y"));
    }

    @Test
    void anArchivedLogIsReadOnly() {
        final long id = create(STEERING_GROUP, Map.of("type", "TODO", "text", "x"));

        setStatus(revival, "ARCHIVED");
        assertThat(numbers(SUPER_ADMIN, Map.of())).containsExactly(1);
        expectError(execute(SUPER_ADMIN, UPDATE, Map.of("input", Map.of("id", id, "version", rowVersion(id), "text", "y"))), "FORBIDDEN");
        expectError(execute(DIRECTEUR, LIST, Map.of("revivalId", revival)), "NOT_FOUND");
    }

    @Test
    void everyChangeIsAuditedFromTheWeb() {
        final long id = create(STEERING_GROUP, Map.of("type", "TODO", "text", "x"));

        execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", id, "version", rowVersion(id), "status", "DONE")));

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM log_entry_audit WHERE id = ?").param(id).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        SELECT r.source FROM log_entry_audit a JOIN revinfo r ON r.id = a.rev WHERE a.id = ? ORDER BY a.rev""")
                .param(id).query(String.class).list()).containsOnly("WEB");
    }
}
