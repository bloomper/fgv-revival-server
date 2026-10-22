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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Excel import over HTTP against MySQL and Keycloak (US2, FR-036–FR-042, SC-001).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class LogHttpApiIntegrationTest extends AbstractIntegrationTest {

    private long revival;

    @BeforeEach
    void revival() {
        revival = organisingRevival();
    }

    private RestTestClient.ResponseSpec post(final String user, final String path, final byte[] file, final @Nullable String choices, final String language) {
        final MultipartBodyBuilder body = new MultipartBodyBuilder();

        body.part("file", new ByteArrayResource(file) {
            @Override
            public String getFilename() {
                return "beslutslogg.xlsx";
            }
        }).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        if (choices != null) {
            body.part("choices", choices).contentType(MediaType.APPLICATION_JSON);
        }
        return restTestClient().post()
                .uri("/api/revivals/%d/log/%s".formatted(revival, path))
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .header(HttpHeaders.ACCEPT_LANGUAGE, language)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build())
                .exchange();
    }

    private JsonNode json(final RestTestClient.ResponseSpec response) {
        return objectMapper.readTree(Objects.requireNonNull(response.expectBody(byte[].class).returnResult().getResponseBody()));
    }

    private int rows() {
        return jdbcClient.sql("SELECT COUNT(*) FROM log_entry WHERE revival_id = ?").param(revival).query(Integer.class).single();
    }

    @Test
    void thePreviewShowsWhatAnImportWouldDoAndStoresNothing() {
        final JsonNode preview = json(post(DIRECTEUR, "import/preview", LogWorkbookFixtures.aliBaba(), null, "en").expectStatus().isOk());

        assertThat(preview.get("layout").asString()).isEqualTo("ALI_BABA");
        assertThat(preview.get("sheet").asString()).isEqualTo("Logg");
        assertThat(preview.get("ignoredSheets").toString()).isEqualTo("[\"Checklistor\",\"Så funkar det\"]");
        assertThat(preview.get("rowCount").asInt()).isEqualTo(12);
        assertThat(preview.at("/byType/DECISION").asInt()).isEqualTo(7);
        assertThat(preview.at("/byType/TODO").asInt()).isEqualTo(4);
        assertThat(preview.at("/byType/QUESTION").asInt()).isEqualTo(1);
        assertThat(preview.at("/byStatus/DONE").asInt()).isEqualTo(7);
        assertThat(preview.at("/byStatus/OPEN").asInt()).isEqualTo(5);
        assertThat(preview.at("/numbers/first").asInt()).isEqualTo(1);
        assertThat(preview.at("/numbers/last").asInt()).isEqualTo(12);
        assertThat(preview.at("/owners/0/name").asString()).isEqualTo("Kim");
        assertThat(preview.at("/owners/0/rows").asInt()).isEqualTo(8);
        assertThat(preview.at("/members").size()).isEqualTo(2);
        assertThat(preview.get("warnings").size()).isZero();
        assertThat(rows()).isZero();
    }

    @Test
    void theImportKeepsEveryFieldAndTheNumbers() {
        final long dana = personId(DIRECTEUR);
        final JsonNode result = json(post(DIRECTEUR, "import", LogWorkbookFixtures.aliBaba(), "{\"owners\": {\"Kim\": \"%d\", \"Robin\": null}}".formatted(dana), "en")
                .expectStatus().isCreated());

        assertThat(result.get("rowCount").asInt()).isEqualTo(12);
        assertThat(result.at("/importedBy/displayName").asString()).isEqualTo("Dana Directeur");
        assertThat(rows()).isEqualTo(12);

        final Map<String, Object> first = jdbcClient.sql("""
                        SELECT number, entry_date, type, text, owner_person_id, owner_name, deadline, status, comment, origin
                        FROM log_entry WHERE revival_id = ? AND number = 9""")
                .param(revival).query().singleRow();

        assertThat(first).containsEntry("number", 9).containsEntry("entry_date", java.sql.Date.valueOf(LocalDate.of(2026, 10, 19)))
                .containsEntry("type", "TODO").containsEntry("text", "Rad 9 i loggen").containsEntry("owner_person_id", dana)
                .containsEntry("deadline", java.sql.Date.valueOf(LocalDate.of(2027, 1, 22))).containsEntry("status", "OPEN")
                .containsEntry("comment", "Kommentar 9").containsEntry("origin", "IMPORT");
        assertThat(jdbcClient.sql("SELECT owner_name FROM log_entry WHERE revival_id = ? AND number = 6").param(revival).query(String.class).single())
                .isEqualTo("Robin");

        final String created = execute(STEERING_GROUP, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { number } }",
                Map.of("input", Map.of("revivalId", revival, "type", "TODO", "text", "Ny rad")))
                .path("logEntryCreate.number").entity(String.class).get();

        assertThat(created).isEqualTo("13");
        execute(STEERING_GROUP, "query($id: ID!) { logImport(revivalId: $id) { fileName layout rowCount importedBy { displayName } } }", Map.of("id", revival))
                .path("logImport.layout").entity(String.class).isEqualTo("ALI_BABA")
                .path("logImport.rowCount").entity(Integer.class).isEqualTo(12)
                .path("logImport.importedBy.displayName").entity(String.class).isEqualTo("Dana Directeur");
    }

    @Test
    void canImportLogFollowsTheRulesAndTheLog() {
        final String query = "query($id: ID!) { revival(id: $id) { permissions { canImportLog canWriteLog } } }";

        execute(DIRECTEUR, query, Map.of("id", revival)).path("revival.permissions.canImportLog").entity(Boolean.class).isEqualTo(true);
        execute(STEERING_GROUP, query, Map.of("id", revival)).path("revival.permissions.canImportLog").entity(Boolean.class).isEqualTo(false);
        execute(SUPER_ADMIN, query, Map.of("id", revival)).path("revival.permissions.canImportLog").entity(Boolean.class).isEqualTo(false);

        post(DIRECTEUR, "import", LogWorkbookFixtures.nobel(), null, "en").expectStatus().isCreated();
        execute(DIRECTEUR, query, Map.of("id", revival)).path("revival.permissions.canImportLog").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void refusalsStoreNothing() {
        post(STEERING_GROUP, "import/preview", LogWorkbookFixtures.aliBaba(), null, "en").expectStatus().isForbidden();
        post(STEERING_GROUP, "import", LogWorkbookFixtures.aliBaba(), null, "en").expectStatus().isForbidden();
        post(SUPER_ADMIN, "import", LogWorkbookFixtures.aliBaba(), null, "en").expectStatus().isForbidden();
        post(OBSERVER, "import", LogWorkbookFixtures.aliBaba(), null, "en").expectStatus().isForbidden();

        final JsonNode notExcel = json(post(DIRECTEUR, "import", "plain text".getBytes(StandardCharsets.UTF_8), null, "en").expectStatus().isBadRequest());

        assertThat(notExcel.get("code").asString()).isEqualTo("BAD_REQUEST");
        assertThat(notExcel.get("message").asString()).isEqualTo("The file is not an Excel workbook (.xlsx).");

        final JsonNode numbers = json(post(DIRECTEUR, "import", LogWorkbookFixtures.aliBabaWith(
                new Object[]{1, LocalDate.of(2026, 1, 1), "Beslut", "a", null, null, "Klar", null},
                new Object[]{1, LocalDate.of(2026, 1, 1), "Beslut", "b", null, null, "Klar", null}).bytes(), null, "sv").expectStatus().isBadRequest());

        assertThat(numbers.get("message").asString()).isEqualTo("Varje rad behöver ett eget nummer i Nr. Kontrollera rad 3 i bladet.");
        post(DIRECTEUR, "import", LogWorkbookFixtures.workbook().sheet("Blad1").row("Nr", "Vad").row(1, "a").bytes(), null, "en")
                .expectStatus().isBadRequest();
        post(DIRECTEUR, "import", LogWorkbookFixtures.aliBaba(), "{\"owners\": {\"Kim\": \"%d\"}}".formatted(personId(SUPER_ADMIN)), "en")
                .expectStatus().isBadRequest();
        post(DIRECTEUR, "import", LogWorkbookFixtures.aliBaba(), "not json", "en").expectStatus().isBadRequest();
        assertThat(rows()).isZero();
    }

    @Test
    void aSecondImportOrAnImportIntoALogWithRowsIsAConflict() {
        post(DIRECTEUR, "import", LogWorkbookFixtures.nobel(), null, "en").expectStatus().isCreated();
        post(DIRECTEUR, "import", LogWorkbookFixtures.nobel(), null, "en").expectStatus().isEqualTo(409);

        final long other = createRevival(ALI_BABA, 2029, "Ali Baba 2029");

        setStatus(other, "APPROVED");
        addMember(other, DIRECTEUR, "DIRECTEUR", List.of());
        execute(DIRECTEUR, "mutation($input: LogEntryCreateInput!) { logEntryCreate(input: $input) { id } }",
                Map.of("input", Map.of("revivalId", other, "type", "TODO", "text", "Först")));

        final MultipartBodyBuilder body = new MultipartBodyBuilder();

        body.part("file", new ByteArrayResource(LogWorkbookFixtures.aliBaba()) {
            @Override
            public String getFilename() {
                return "logg.xlsx";
            }
        });
        restTestClient().post().uri("/api/revivals/%d/log/import".formatted(other)).header(HttpHeaders.AUTHORIZATION, token(DIRECTEUR))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(body.build()).exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void aFileOver10MbIsTooLarge() {
        final JsonNode error = json(post(DIRECTEUR, "import", new byte[11 * 1024 * 1024], null, "en").expectStatus().isEqualTo(413));

        assertThat(error.get("message").asString()).isEqualTo("The file is too large. At most 10 MB.");
    }

    @Test
    void anImportIsAudited() {
        post(DIRECTEUR, "import", LogWorkbookFixtures.nobel(), null, "en").expectStatus().isCreated();

        assertThat(jdbcClient.sql("""
                        SELECT DISTINCT r.operation FROM log_entry_audit a JOIN revinfo r ON r.id = a.rev
                        JOIN log_entry e ON e.id = a.id WHERE e.revival_id = ?""")
                .param(revival).query(String.class).list()).containsExactly("logImport");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM log_import_audit").query(Integer.class).single()).isEqualTo(1);
    }
}
