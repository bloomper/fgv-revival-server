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
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.AbstractIntegrationTest;
import nu.fgv.revival.server.Documents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A revival's budget over GraphQL against MySQL and Keycloak: categories, reference figures, event type lines,
 * settings, lines, transactions, scenarios, who may do what, and the reference kept when a revival is deleted.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class BudgetGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    static final String LINE_FIELDS = "id category { id kind } description quantity unitAmount amount event { id title } logEntry { id number type } "
            + "comment removed version";
    static final String TRANSACTION_FIELDS = "id date amount category { id } description budgetLine { id } paidBy { person { id displayName active } name } "
            + "reimbursed reimbursedAt receipt { filename contentType size } cancelled version";
    static final String TRANSACTION_CREATE = "mutation($input: TransactionCreateInput!) { transactionCreate(input: $input) { " + TRANSACTION_FIELDS + " } }";
    private static final String LINE_CREATE = "mutation($input: BudgetLineCreateInput!) { budgetLineCreate(input: $input) { " + LINE_FIELDS + " } }";
    private static final String BUDGET = "query($id: ID!) { revival(id: $id) { permissions { canReadBudget canWriteBudget } budget { "
            + "settings { participantsPlanned participationFee loanAmount loanReceivedOn version } "
            + "totals { budgetedIncome budgetedCost budgetedResult actualIncome actualCost actualResult unreimbursed unreimbursedCount missingReceipts } "
            + "categories { category { id } budgeted actual references { label year participants budgeted actual budgetedPerParticipant actualPerParticipant } } "
            + "lines(includeRemoved: true) { " + LINE_FIELDS + " } transactions(includeCancelled: true) { " + TRANSACTION_FIELDS + " } "
            + "proposals { event { id } line { id description { sv en } quantityRule } quantity amount } "
            + "scenarios { id name parameters { showTickets } result { income result breakEvenShowTickets } version } } } }";

    private long revival;

    @BeforeEach
    void revivalWithAFinanceMember() {
        revival = organisingRevival();
        giveFinance(revival, STEERING_GROUP);
    }

    /**
     * Gives the steering group member the finance area, as super admin.
     */
    void giveFinance(final long revivalId, final String email) {
        final long member = jdbcClient.sql("SELECT m.id FROM revival_member m JOIN person p ON p.id = m.person_id WHERE m.revival_id = ? AND p.email = ?")
                .params(revivalId, email).query(Long.class).single();
        final long memberVersion = jdbcClient.sql("SELECT version FROM revival_member WHERE id = ?").param(member).query(Long.class).single();

        execute(SUPER_ADMIN, Documents.MEMBER_CAPABILITIES, Map.of("input", Map.of("id", member, "version", memberVersion,
                "capabilities", List.of("FINANCE"))))
                .path("revivalMemberCapabilitiesSet.capabilities[*].id").entityList(String.class).containsExactly("FINANCE");
    }

    private long createLine(final String user, final Map<String, Object> fields) {
        final Map<String, Object> input = new HashMap<>(fields);

        input.put("revivalId", revival);
        return Long.parseLong(execute(user, LINE_CREATE, Map.of("input", input)).path("budgetLineCreate.id").entity(String.class).get());
    }

    private long createTransaction(final String user, final Map<String, Object> fields) {
        final Map<String, Object> input = new HashMap<>(fields);

        input.put("revivalId", revival);
        return Long.parseLong(execute(user, TRANSACTION_CREATE, Map.of("input", input)).path("transactionCreate.id").entity(String.class).get());
    }

    private long version(final String table, final long id) {
        return jdbcClient.sql("SELECT version FROM " + table + " WHERE id = ?").param(id).query(Long.class).single();
    }

    @Test
    void theCategoriesAreSeededAndKeptByTheSuperAdmin() {
        execute(STEERING_GROUP, "{ budgetCategories { id kind role name { sv en } position retired inUse } }", Map.of())
                .path("budgetCategories[*].id").entityList(String.class).hasSize(16).contains("SHOW_TICKETS", "PARTY_TICKETS", "FEES")
                .path("budgetCategories[0].role").entity(String.class).isEqualTo("SHOW_TICKETS")
                .path("budgetCategories[5].name.en").entity(String.class).isEqualTo("Venues")
                .path("budgetCategories[0].inUse").entity(Boolean.class).isEqualTo(false);

        final String created = execute(SUPER_ADMIN, "mutation($input: BudgetCategoryCreateInput!) { budgetCategoryCreate(input: $input) { id kind role version } }",
                Map.of("input", Map.of("kind", "COST", "name", Map.of("sv", "Resor", "en", "Travel"))))
                .path("budgetCategoryCreate.kind").entity(String.class).isEqualTo("COST")
                .path("budgetCategoryCreate.id").entity(String.class).get();

        expectError(execute(SUPER_ADMIN, "mutation($input: BudgetCategoryUpdateInput!) { budgetCategoryUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", created, "version", 0, "name", Map.of("sv", "Resor"), "role", "VENUE"))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, "mutation($input: BudgetCategoryCreateInput!) { budgetCategoryCreate(input: $input) { id } }",
                Map.of("input", Map.of("kind", "COST", "name", Map.of("sv", "Mer")))), "FORBIDDEN");
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { budgetCategoryDelete(id: $id, version: $version) }",
                Map.of("id", created, "version", 0)).errors().verify();
    }

    @Test
    void readersAndWritersFollowTheFinanceArea() {
        execute(STEERING_GROUP, BUDGET, Map.of("id", revival))
                .path("revival.permissions.canReadBudget").entity(Boolean.class).isEqualTo(true)
                .path("revival.permissions.canWriteBudget").entity(Boolean.class).isEqualTo(true)
                .path("revival.budget.settings.version").entity(Integer.class).isEqualTo(0);
        execute(OBSERVER, BUDGET, Map.of("id", revival))
                .path("revival.permissions.canWriteBudget").entity(Boolean.class).isEqualTo(false)
                .path("revival.budget.totals.budgetedResult").entity(String.class).isEqualTo("0");

        login(DIRECTEUR_2);
        addMember(revival, DIRECTEUR_2, "STEERING_GROUP", List.of("CATERING"));
        execute(DIRECTEUR_2, BUDGET, Map.of("id", revival))
                .path("revival.permissions.canReadBudget").entity(Boolean.class).isEqualTo(true)
                .path("revival.permissions.canWriteBudget").entity(Boolean.class).isEqualTo(false);
        for (final String user : List.of(DIRECTEUR_2, OBSERVER, SUPER_ADMIN)) {
            expectError(execute(user, LINE_CREATE, Map.of("input", Map.of("revivalId", revival, "categoryId", "FOOD", "description", "Mat",
                    "unitAmount", "100"))), "FORBIDDEN");
        }
        expectError(execute(PLAIN_USER, "query($id: ID!) { revival(id: $id) { id } }", Map.of("id", revival)), "NOT_FOUND");
    }

    @Test
    void settingsLinesAndTotals() {
        execute(STEERING_GROUP, "mutation($input: BudgetSettingsInput!) { budgetSettingsUpdate(input: $input) { settings { participantsPlanned "
                + "participationFee loanAmount loanReceivedOn version } } }", Map.of("input", Map.of("revivalId", revival, "version", 0,
                "participantsPlanned", 60, "participationFee", "500", "loanAmount", 10000, "loanReceivedOn", "2027-09-01")))
                .path("budgetSettingsUpdate.settings.participationFee").entity(String.class).isEqualTo("500")
                .path("budgetSettingsUpdate.settings.loanAmount").entity(String.class).isEqualTo("10000")
                .path("budgetSettingsUpdate.settings.version").entity(Integer.class).isEqualTo(1);
        expectError(execute(STEERING_GROUP, "mutation($input: BudgetSettingsInput!) { budgetSettingsUpdate(input: $input) { revivalId } }",
                Map.of("input", Map.of("revivalId", revival, "version", 0))), "CONFLICT");

        final long decision = Long.parseLong(execute(STEERING_GROUP, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "type", "DECISION", "text", "Fyra springare à 1 200 kr")))
                .path("logEntryCreate.id").entity(String.class).get());

        createLine(STEERING_GROUP, Map.of("categoryId", "SHOW_TICKETS", "description", "Biljetter", "quantity", 450, "unitAmount", "180"));
        createLine(DIRECTEUR, Map.of("categoryId", "HELPERS", "description", "Springare", "quantity", 4, "unitAmount", 1200, "logEntryId", decision));

        final long removed = createLine(STEERING_GROUP, Map.of("categoryId", "VENUE", "description", "Lokal", "unitAmount", "12345.67",
                "comment", "Preliminärt"));

        execute(STEERING_GROUP, "mutation($id: ID!, $version: Int!) { budgetLineRemove(id: $id, version: $version) { removed } }",
                Map.of("id", removed, "version", 0)).path("budgetLineRemove.removed").entity(Boolean.class).isEqualTo(true);
        expectError(execute(STEERING_GROUP, LINE_CREATE, Map.of("input", Map.of("revivalId", revival, "categoryId", "FOOD", "description", "Mat",
                "unitAmount", "1.005"))), "ValidationError");

        execute(OBSERVER, BUDGET, Map.of("id", revival))
                .path("revival.budget.lines[*].category.id").entityList(String.class).containsExactly("SHOW_TICKETS", "VENUE", "HELPERS")
                .path("revival.budget.lines[0].amount").entity(String.class).isEqualTo("81000")
                .path("revival.budget.lines[1].amount").entity(String.class).isEqualTo("12345.67")
                .path("revival.budget.lines[1].removed").entity(Boolean.class).isEqualTo(true)
                .path("revival.budget.lines[2].logEntry.type").entity(String.class).isEqualTo("DECISION")
                .path("revival.budget.totals.budgetedIncome").entity(String.class).isEqualTo("81000")
                .path("revival.budget.totals.budgetedCost").entity(String.class).isEqualTo("4800")
                .path("revival.budget.totals.budgetedResult").entity(String.class).isEqualTo("76200")
                .path("revival.budget.categories[*].category.id").entityList(String.class)
                .containsExactly("SHOW_TICKETS", "PARTY_TICKETS", "FEES", "VENUE", "PARTY", "HELPERS");

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM revinfo WHERE operation = 'budgetLineCreate'").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void eventTypesProposeLinesForTheirEvents() {
        final long rehearsal = jdbcClient.sql("SELECT id FROM event_type WHERE name_sv = 'Rephelg 1'").query(Long.class).single();

        execute(SUPER_ADMIN, "mutation($input: EventTypeBudgetLineInput!) { eventTypeBudgetLineCreate(input: $input) { id budgetLines { id "
                + "category { id } description { sv en } quantityRule quantity unitAmount } } }", Map.of("input", Map.of("eventTypeId", rehearsal,
                "categoryId", "FOOD", "description", Map.of("sv", "Mat per deltagare", "en", "Food per participant"), "quantityRule", "PER_PARTICIPANT",
                "unitAmount", "150")))
                .path("eventTypeBudgetLineCreate.budgetLines[0].category.id").entity(String.class).isEqualTo("FOOD")
                .path("eventTypeBudgetLineCreate.budgetLines[0].quantity").valueIsNull();
        expectError(execute(DIRECTEUR, "mutation($input: EventTypeBudgetLineInput!) { eventTypeBudgetLineCreate(input: $input) { id } }",
                Map.of("input", Map.of("eventTypeId", rehearsal, "categoryId", "FOOD", "description", Map.of("sv", "Mat"), "quantityRule", "FIXED",
                        "quantity", 1, "unitAmount", "1"))), "FORBIDDEN");

        final long typeLine = jdbcClient.sql("SELECT id FROM event_type_budget_line").query(Long.class).single();
        final long event = Long.parseLong(execute(STEERING_GROUP, "mutation($input: EventCreateInput!) { eventCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "typeId", rehearsal, "startDate", "2027-12-11"))).path("eventCreate.id").entity(String.class).get());

        execute(STEERING_GROUP, BUDGET, Map.of("id", revival))
                .path("revival.budget.proposals[0].event.id").entity(Long.class).isEqualTo(event)
                .path("revival.budget.proposals[0].line.description.en").entity(String.class).isEqualTo("Food per participant")
                .path("revival.budget.proposals[0].quantity").valueIsNull();

        execute(STEERING_GROUP, "mutation($input: BudgetSettingsInput!) { budgetSettingsUpdate(input: $input) { revivalId } }",
                Map.of("input", Map.of("revivalId", revival, "version", 0, "participantsPlanned", 60)));
        execute(STEERING_GROUP, BUDGET, Map.of("id", revival))
                .path("revival.budget.proposals[0].quantity").entity(Double.class).isEqualTo(60.0)
                .path("revival.budget.proposals[0].amount").entity(String.class).isEqualTo("9000");

        createLine(STEERING_GROUP, Map.of("categoryId", "FOOD", "description", "Mat per deltagare", "quantity", 60, "unitAmount", "150",
                "eventId", event, "eventTypeLineId", typeLine));
        execute(STEERING_GROUP, BUDGET, Map.of("id", revival))
                .path("revival.budget.proposals").entityList(Object.class).hasSize(0)
                .path("revival.budget.lines[0].event.title").entity(String.class).isEqualTo("Rehearsal weekend 1");
        expectError(execute(STEERING_GROUP, LINE_CREATE, Map.of("input", Map.of("revivalId", revival, "categoryId", "FOOD", "description", "Igen",
                "unitAmount", "150", "eventId", event, "eventTypeLineId", typeLine))), "BAD_REQUEST");

        // Removing the template line leaves the budget line as it is
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { eventTypeBudgetLineRemove(id: $id, version: $version) { budgetLines { id } } }",
                Map.of("id", typeLine, "version", 0)).path("eventTypeBudgetLineRemove.budgetLines").entityList(Object.class).hasSize(0);
        assertThat(jdbcClient.sql("SELECT event_type_line_id FROM budget_line").query(Long.class).single()).isEqualTo(typeLine);
    }

    @Test
    void transactionsArePaidReimbursedAndCancelled() {
        final long me = personId(STEERING_GROUP);
        final long line = createLine(STEERING_GROUP, Map.of("categoryId", "FOOD", "description", "Mat", "quantity", 60, "unitAmount", "120"));
        final long paid = createTransaction(STEERING_GROUP, Map.of("date", "2027-12-11", "amount", "2350.50", "categoryId", "FOOD",
                "description", "Mat till rephelgen", "budgetLineId", line, "paidBy", Map.of("personId", me)));

        createTransaction(STEERING_GROUP, Map.of("date", "2027-12-01", "amount", 25200, "categoryId", "SHOW_TICKETS", "description", "Biljetter"));
        expectError(execute(STEERING_GROUP, TRANSACTION_CREATE, Map.of("input", Map.of("revivalId", revival, "date", "2027-12-01", "amount", "100",
                "categoryId", "SHOW_TICKETS", "description", "Biljetter", "paidBy", Map.of("name", "Kim")))), "BAD_REQUEST");
        expectError(execute(STEERING_GROUP, TRANSACTION_CREATE, Map.of("input", Map.of("revivalId", revival, "date", "2027-12-01", "amount", "0",
                "categoryId", "FOOD", "description", "Gratis"))), "BAD_REQUEST");

        execute(OBSERVER, BUDGET, Map.of("id", revival))
                .path("revival.budget.transactions[0].description").entity(String.class).isEqualTo("Mat till rephelgen")
                .path("revival.budget.transactions[0].paidBy.person.displayName").entity(String.class).isEqualTo("Stina Styrgrupp")
                .path("revival.budget.transactions[0].budgetLine.id").entity(Long.class).isEqualTo(line)
                .path("revival.budget.totals.actualIncome").entity(String.class).isEqualTo("25200")
                .path("revival.budget.totals.actualCost").entity(String.class).isEqualTo("2350.50")
                .path("revival.budget.totals.actualResult").entity(String.class).isEqualTo("22849.50")
                .path("revival.budget.totals.unreimbursed").entity(String.class).isEqualTo("2350.50")
                .path("revival.budget.totals.unreimbursedCount").entity(Integer.class).isEqualTo(1)
                .path("revival.budget.totals.missingReceipts").entity(Integer.class).isEqualTo(2);

        execute(DIRECTEUR, "mutation($id: ID!, $version: Int!) { transactionSetReimbursed(id: $id, version: $version, reimbursed: true) { reimbursed "
                + "reimbursedAt version } }", Map.of("id", paid, "version", 0))
                .path("transactionSetReimbursed.reimbursed").entity(Boolean.class).isEqualTo(true)
                .path("transactionSetReimbursed.reimbursedAt").hasValue();
        execute(STEERING_GROUP, "mutation($id: ID!, $version: Int!) { transactionCancel(id: $id, version: $version) { cancelled } }",
                Map.of("id", paid, "version", 1)).path("transactionCancel.cancelled").entity(Boolean.class).isEqualTo(true);
        expectError(execute(STEERING_GROUP, "mutation($input: TransactionUpdateInput!) { transactionUpdate(input: $input) { id } }",
                Map.of("input", Map.of("id", paid, "version", 2, "description", "Ändrad"))), "BAD_REQUEST");

        execute(STEERING_GROUP, BUDGET, Map.of("id", revival))
                .path("revival.budget.totals.actualCost").entity(String.class).isEqualTo("0")
                .path("revival.budget.transactions[*].cancelled").entityList(Boolean.class).containsExactly(true, false)
                .path("revival.budget.categories[?(@.category.id == 'FOOD')].budgeted").entityList(String.class).containsExactly("7200");
    }

    @Test
    void scenariosAreCalculatedSavedAndRemoved() {
        createLine(STEERING_GROUP, Map.of("categoryId", "SHOW_TICKETS", "description", "Biljetter", "quantity", 400, "unitAmount", "150"));
        createLine(STEERING_GROUP, Map.of("categoryId", "VENUE", "description", "Lokal", "unitAmount", "72000"));

        execute(OBSERVER, "query($id: ID!, $input: BudgetScenarioInput!) { budgetScenarioCalculate(revivalId: $id, input: $input) { baseline { "
                        + "showTickets showTicketPrice venueCost } used { showTickets showTicketPrice } income cost result breakEvenShowTickets partySubsidy } }",
                Map.of("id", revival, "input", Map.of("showTicketPrice", "200")))
                .path("budgetScenarioCalculate.baseline.showTickets").entity(Integer.class).isEqualTo(400)
                .path("budgetScenarioCalculate.baseline.showTicketPrice").entity(String.class).isEqualTo("150")
                .path("budgetScenarioCalculate.used.showTicketPrice").entity(String.class).isEqualTo("200")
                .path("budgetScenarioCalculate.income").entity(String.class).isEqualTo("80000")
                .path("budgetScenarioCalculate.result").entity(String.class).isEqualTo("8000")
                .path("budgetScenarioCalculate.breakEvenShowTickets").entity(Integer.class).isEqualTo(360);

        final long scenario = Long.parseLong(execute(STEERING_GROUP, "mutation($input: BudgetScenarioSaveInput!) { budgetScenarioSave(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "name", "Dyrare biljetter", "parameters", Map.of("showTickets", 500))))
                .path("budgetScenarioSave.id").entity(String.class).get());

        expectError(execute(OBSERVER, "mutation($input: BudgetScenarioSaveInput!) { budgetScenarioSave(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", revival, "name", "Nej", "parameters", Map.of()))), "FORBIDDEN");
        execute(STEERING_GROUP, BUDGET, Map.of("id", revival))
                .path("revival.budget.scenarios[0].parameters.showTickets").entity(Integer.class).isEqualTo(500)
                .path("revival.budget.scenarios[0].result.income").entity(String.class).isEqualTo("75000");
        execute(STEERING_GROUP, "mutation($id: ID!, $version: Int!) { budgetScenarioRemove(id: $id, version: $version) }",
                Map.of("id", scenario, "version", 0)).errors().verify();
        execute(STEERING_GROUP, BUDGET, Map.of("id", revival)).path("revival.budget.scenarios").entityList(Object.class).hasSize(0);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM budget_scenario WHERE removed_at IS NOT NULL").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void referenceFiguresAreKeptByTheSuperAdminAndShownBesideTheCategories() {
        final String create = "mutation($input: ReferenceRevivalInput!) { referenceRevivalCreate(input: $input) { id label year participants automatic "
                + "figures { category { id } budgeted actual } version } }";

        final long reference = Long.parseLong(execute(SUPER_ADMIN, create, Map.of("input", Map.of("label", "Nobel 2026", "year", 2026,
                "participants", 50, "figures", List.of(Map.of("categoryId", "VENUE", "budgeted", "40000", "actual", "41234.50"),
                        Map.of("categoryId", "SHOW_TICKETS", "actual", "81000")))))
                .path("referenceRevivalCreate.figures[*].category.id").entityList(String.class).containsExactly("SHOW_TICKETS", "VENUE")
                .path("referenceRevivalCreate.automatic").entity(Boolean.class).isEqualTo(false)
                .path("referenceRevivalCreate.id").entity(String.class).get());

        expectError(execute(STEERING_GROUP, create, Map.of("input", Map.of("label", "X", "year", 2026, "figures", List.of()))), "FORBIDDEN");
        execute(STEERING_GROUP, "{ referenceRevivals { label year figures { actual } } }", Map.of())
                .path("referenceRevivals[0].label").entity(String.class).isEqualTo("Nobel 2026");
        execute(STEERING_GROUP, BUDGET, Map.of("id", revival))
                .path("revival.budget.categories[?(@.category.id == 'VENUE')].references[0].actualPerParticipant").entityList(String.class)
                .containsExactly("825");
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!, $input: ReferenceRevivalInput!) { referenceRevivalUpdate(id: $id, version: $version, "
                + "input: $input) { figures { category { id } } version } }", Map.of("id", reference, "version", 0, "input", Map.of("label", "Nobel 2026",
                "year", 2026, "figures", List.of(Map.of("categoryId", "VENUE", "actual", "41000")))))
                .path("referenceRevivalUpdate.figures[*].category.id").entityList(String.class).containsExactly("VENUE");
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { referenceRevivalDelete(id: $id, version: $version) }",
                Map.of("id", reference, "version", version("reference_revival", reference))).errors().verify();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM reference_revival").query(Integer.class).single()).isZero();
    }

    @Test
    void aDeletedRevivalLeavesItsBudgetAsAnAnonymousReference() {
        createLine(STEERING_GROUP, Map.of("categoryId", "VENUE", "description", "Lokal", "unitAmount", "30000"));
        final long transaction = createTransaction(STEERING_GROUP, Map.of("date", "2027-12-11", "amount", "1450", "categoryId", "VENUE",
                "description", "Handpenning", "paidBy", Map.of("name", "Kim Kassör")));

        restTestClient().post().uri("/api/transactions/{id}/receipt", transaction)
                .header(HttpHeaders.AUTHORIZATION, token(STEERING_GROUP))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BudgetHttpApiIntegrationTest.receipt("kvitto.pdf", BudgetHttpApiIntegrationTest.PDF))
                .exchange().expectStatus().isOk();
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { revivalDelete(id: $id, version: $version) }",
                Map.of("id", revival, "version", version(revival))).errors().verify();

        assertThat(jdbcClient.sql("SELECT label FROM reference_revival WHERE automatic = 1").query(String.class).single()).isEqualTo("Ali Baba 2028");
        assertThat(jdbcClient.sql("SELECT CONCAT(category_id, ':', budgeted, ':', actual) FROM reference_figure").query(String.class).list())
                .containsExactly("VENUE:30000.00:1450.00");
        for (final String table : List.of("budget_line", "budget_transaction", "receipt_content", "budget_settings")) {
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single()).as(table).isZero();
        }
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM budget_transaction_audit WHERE revtype = 2").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theChalmersspexDatesAreKeptByTheSuperAdminAndReadByObservers() {
        final String fields = "id date label version";
        final long date = Long.parseLong(execute(SUPER_ADMIN, "mutation($input: SpexCalendarDateInput!) { spexCalendarDateCreate(input: $input) { "
                + fields + " } }", Map.of("input", Map.of("date", "2027-02-06", "label", "Chalmersspexet: premiär")))
                .path("spexCalendarDateCreate.id").entity(String.class).get());

        execute(OBSERVER, "query($from: Date) { spexCalendarDates(from: $from) { " + fields + " } }", Map.of("from", "2027-01-01"))
                .path("spexCalendarDates[0].label").entity(String.class).isEqualTo("Chalmersspexet: premiär");
        expectError(execute(DIRECTEUR, "{ spexCalendarDates { id } }", Map.of()), "FORBIDDEN");
        expectError(execute(OBSERVER, "mutation($input: SpexCalendarDateInput!) { spexCalendarDateCreate(input: $input) { id } }",
                Map.of("input", Map.of("date", "2027-02-07", "label", "Nej"))), "FORBIDDEN");
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!, $input: SpexCalendarDateInput!) { spexCalendarDateUpdate(id: $id, version: $version, "
                + "input: $input) { date version } }", Map.of("id", date, "version", 0, "input", Map.of("date", "2027-02-07", "label", "Premiär")))
                .path("spexCalendarDateUpdate.date").entity(String.class).isEqualTo("2027-02-07");
        execute(SUPER_ADMIN, "mutation($id: ID!, $version: Int!) { spexCalendarDateDelete(id: $id, version: $version) }",
                Map.of("id", date, "version", 1)).errors().verify();
        execute(SUPER_ADMIN, "{ spexCalendarDates { id } }", Map.of()).path("spexCalendarDates").entityList(Object.class).hasSize(0);
    }
}
