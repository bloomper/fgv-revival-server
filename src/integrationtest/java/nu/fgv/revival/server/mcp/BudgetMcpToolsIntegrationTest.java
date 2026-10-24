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
package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.AbstractIntegrationTest;
import nu.fgv.revival.server.Documents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The budget's MCP tools against MySQL and Keycloak (M5-FR-13): its position for readers without payers' names, and
 * recording a transaction for writers.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
class BudgetMcpToolsIntegrationTest extends AbstractIntegrationTest {

    private long revival;

    private static boolean refused(final JsonNode response) {
        return response.has("error") || response.path("result").path("isError").asBoolean(false);
    }

    @BeforeEach
    void revivalWithABudget() {
        revival = organisingRevival();

        final long member = jdbcClient.sql("SELECT m.id FROM revival_member m JOIN person p ON p.id = m.person_id WHERE m.revival_id = ? AND p.email = ?")
                .params(revival, STEERING_GROUP).query(Long.class).single();

        execute(SUPER_ADMIN, Documents.MEMBER_CAPABILITIES, Map.of("input", Map.of("id", member, "version",
                jdbcClient.sql("SELECT version FROM revival_member WHERE id = ?").param(member).query(Long.class).single(), "capabilities", List.of("FINANCE"))));
        execute(STEERING_GROUP, "mutation($input: BudgetLineCreateInput!) { budgetLineCreate(input: $input) { id } }", Map.of("input", Map.of(
                "revivalId", revival, "categoryId", "SHOW_TICKETS", "description", "Biljetter", "quantity", 400, "unitAmount", "150")));
        execute(STEERING_GROUP, "mutation($input: BudgetSettingsInput!) { budgetSettingsUpdate(input: $input) { revivalId } }", Map.of("input",
                Map.of("revivalId", revival, "version", 0, "participantsPlanned", 60, "loanAmount", "10000", "loanReceivedOn", "2027-09-01")));
    }

    private JsonNode call(final String user, final String tool, final Map<String, Object> arguments) {
        return mcpRpc(mcpToken(user), "tools/call", Map.of("name", tool, "arguments", arguments));
    }

    private JsonNode result(final JsonNode response) {
        final JsonNode result = response.get("result");

        if (result == null || result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool error: " + response);
        }
        return objectMapper.readTree(result.get("content").get(0).get("text").asString());
    }

    @Test
    void aWriterRecordsATransactionPaidByThemselves() {
        final JsonNode written = result(call(STEERING_GROUP, "add_transaction", Map.of("revivalId", revival, "date", "2027-12-11",
                "amount", "349,50", "category", "mat och dryck", "description", "Fika", "paidBy", "me")));

        assertThat(written.get("amount").asString()).isEqualTo("349.50");
        assertThat(written.get("category").get("sv").asString()).isEqualTo("Mat och dryck");
        assertThat(written.get("kind").asString()).isEqualTo("COST");
        assertThat(written.get("paidBy").get("name").asString()).isEqualTo("Stina Styrgrupp");
        assertThat(written.get("paidByResolution").asString()).isEqualTo("MEMBER");
        assertThat(jdbcClient.sql("SELECT DISTINCT r.source FROM budget_transaction_audit a JOIN revinfo r ON r.id = a.rev").query(String.class).list())
                .containsExactly("MCP");
    }

    @Test
    void readersOnlyRead() {
        final JsonNode refusal = call(OBSERVER, "add_transaction", Map.of("revivalId", revival, "date", "2027-12-11", "amount", "100",
                "category", "FOOD", "description", "Fika"));

        assertThat(refused(refusal)).isTrue();
        assertThat(refusal.toString()).contains("FORBIDDEN");
        assertThat(refused(call(STEERING_GROUP, "add_transaction", Map.of("revivalId", revival, "date", "2027-12-11", "amount", "1.234",
                "category", "FOOD", "description", "Fika")))).isTrue();
        assertThat(refused(call(STEERING_GROUP, "add_transaction", Map.of("revivalId", revival, "date", "2027-12-11", "amount", "100",
                "category", "Biljetter föreställning", "description", "Biljetter", "paidBy", "Kim")))).as("income paid by someone").isTrue();
    }

    @Test
    void theSummaryGivesThePositionButNeverWhoPaid() {
        result(call(STEERING_GROUP, "add_transaction", Map.of("revivalId", revival, "date", "2027-12-11", "amount", "2000", "category", "VENUE",
                "description", "Handpenning", "paidBy", "Kim Kassör")));

        final JsonNode summary = result(call(OBSERVER, "get_budget_summary", Map.of("revivalId", revival)));

        assertThat(summary.get("budgeted").get("income").asString()).isEqualTo("60000");
        assertThat(summary.get("actual").get("cost").asString()).isEqualTo("2000");
        assertThat(summary.get("actual").get("result").asString()).isEqualTo("-2000");
        assertThat(summary.get("loan").get("amount").asString()).isEqualTo("10000");
        assertThat(summary.get("unreimbursedCount").asInt()).isEqualTo(1);
        assertThat(summary.get("missingReceipts").asInt()).isEqualTo(1);
        assertThat(summary.toString()).doesNotContain("Kim");

        final JsonNode status = result(call(DIRECTEUR, "get_revival_status", Map.of("revivalId", revival)));

        assertThat(status.get("budget").get("budgetedResult").asString()).isEqualTo("60000");
        assertThat(status.get("budget").get("actualResult").asString()).isEqualTo("-2000");
        assertThat(refused(call(PLAIN_USER, "get_budget_summary", Map.of("revivalId", revival)))).isTrue();
    }
}
