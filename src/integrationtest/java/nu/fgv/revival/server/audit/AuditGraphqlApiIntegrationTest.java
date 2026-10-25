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
package nu.fgv.revival.server.audit;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * A record's history over GraphQL: newest first, by name, readable by those who may read the record.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AuditGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String REVISIONS = "query($type: AuditedType!, $id: ID!) { revisions(type: $type, id: $id) { "
            + "revisionType modifiedBy source changes { field kind oldValue newValue } } }";

    private long revival;

    @BeforeEach
    void revival() {
        revival = organisingRevival();
    }

    private long rowVersion(final String table, final long id) {
        return jdbcClient.sql("SELECT version FROM " + table + " WHERE id = ?").param(id).query(Long.class).single();
    }

    @Test
    void aLogEntrysHistoryShowsWhoChangedWhatByName() {
        final long row = Long.parseLong(execute(STEERING_GROUP, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "Boka RunAn", "owner", Map.of("name", "Vaktmästaren"))))
                .path("logEntryCreate.id").entity(String.class).get());

        execute(DIRECTEUR, "mutation($input: LogEntryUpdateInput!) { logEntryUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", row, "version", rowVersion("log_entry", row), "text", "Boka RunAn", "status", "DONE",
                        "owner", Map.of("personId", personId(STEERING_GROUP)))))
                .errors().verify();

        for (final String user : new String[]{STEERING_GROUP, OBSERVER, SUPER_ADMIN}) {
            execute(user, REVISIONS, Map.of("type", "LOG_ENTRY", "id", row))
                    .path("revisions[*].revisionType").entityList(String.class).containsExactly("MOD", "ADD")
                    .path("revisions[0].modifiedBy").entity(String.class).isEqualTo("Dana Directeur")
                    .path("revisions[0].source").entity(String.class).isEqualTo("WEB")
                    .path("revisions[0].changes[*].field").entityList(String.class).containsExactly("status", "owner")
                    .path("revisions[0].changes[0].kind").entity(String.class).isEqualTo("ENUM")
                    .path("revisions[0].changes[0].oldValue").entity(String.class).isEqualTo("OPEN")
                    .path("revisions[0].changes[0].newValue").entity(String.class).isEqualTo("DONE")
                    .path("revisions[0].changes[1].oldValue").entity(String.class).isEqualTo("Vaktmästaren")
                    .path("revisions[0].changes[1].newValue").entity(String.class).isEqualTo("Stina Styrgrupp")
                    .path("revisions[1].modifiedBy").entity(String.class).isEqualTo("Stina Styrgrupp");
        }
        // Someone who cannot see the revival learns nothing, not even that the row exists
        expectError(execute(PLAIN_USER, REVISIONS, Map.of("type", "LOG_ENTRY", "id", row)), "NOT_FOUND");
        expectError(execute(STEERING_GROUP, REVISIONS, Map.of("type", "LOG_ENTRY", "id", row + 1000)), "NOT_FOUND");
    }

    @Test
    void aBudgetLinesHistoryShowsAmountsAndCategoryNames() {
        final long line = Long.parseLong(execute(DIRECTEUR, "mutation($input: BudgetLineCreateInput!) { budgetLineCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "categoryId", "FOOD", "description", "Mat", "unitAmount", "1200")))
                .path("budgetLineCreate.id").entity(String.class).get());

        execute(DIRECTEUR, "mutation($input: BudgetLineUpdateInput!) { budgetLineUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", line, "version", rowVersion("budget_line", line), "categoryId", "FOOD", "description", "Mat",
                        "unitAmount", "1450.50")))
                .errors().verify();

        execute(OBSERVER, REVISIONS, Map.of("type", "BUDGET_LINE", "id", line))
                .path("revisions[0].changes[*].field").entityList(String.class).containsExactly("unitAmount")
                .path("revisions[0].changes[0].kind").entity(String.class).isEqualTo("AMOUNT")
                .path("revisions[0].changes[0].oldValue").entity(String.class).isEqualTo("1200.00")
                .path("revisions[0].changes[0].newValue").entity(String.class).isEqualTo("1450.50")
                .path("revisions[1].changes[?(@.field == 'categoryId')].newValue").entityList(String.class).containsExactly("Food and drink");
        // Participants never read the budget
        expectError(execute(PARTICIPANT, REVISIONS, Map.of("type", "BUDGET_LINE", "id", line)), "NOT_FOUND");
    }

    @Test
    void newsHistoryIsTheSuperAdminsAndKeptAfterDeleting() {
        final long news = Long.parseLong(execute(SUPER_ADMIN, "mutation($input: NewsInput!) { newsCreate(input: $input) { id } }",
                Map.of("input", Map.of("subject", "Utkast", "text", "Text"))).path("newsCreate.id").entity(String.class).get());

        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { newsDelete(id: $id, version: $version) }",
                Map.of("id", news, "version", rowVersion("news", news))).errors().verify();

        execute(SUPER_ADMIN, REVISIONS, Map.of("type", "NEWS", "id", news))
                .path("revisions[*].revisionType").entityList(String.class).containsExactly("DEL", "ADD")
                .path("revisions[1].changes[*].field").entityList(String.class).containsExactly("subject", "text");
        expectError(execute(OBSERVER, REVISIONS, Map.of("type", "NEWS", "id", news)), "FORBIDDEN");
    }
}
