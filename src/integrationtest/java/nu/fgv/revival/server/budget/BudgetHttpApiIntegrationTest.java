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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receipts and the Excel export over HTTP against MySQL and Keycloak (M5-FR-11, M5-FR-12).
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
class BudgetHttpApiIntegrationTest extends AbstractIntegrationTest {

    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);

    private long revival;
    private long transaction;

    static MultiValueMap<String, HttpEntity<?>> receipt(final String filename, final byte[] content) {
        final MultipartBodyBuilder body = new MultipartBodyBuilder();

        body.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.IMAGE_PNG);
        return body.build();
    }

    @BeforeEach
    void revivalWithATransaction() {
        revival = organisingRevival();

        final long member = jdbcClient.sql("SELECT m.id FROM revival_member m JOIN person p ON p.id = m.person_id WHERE m.revival_id = ? AND p.email = ?")
                .params(revival, STEERING_GROUP).query(Long.class).single();

        execute(SUPER_ADMIN, Documents.MEMBER_CAPABILITIES, Map.of("input", Map.of("id", member, "version",
                jdbcClient.sql("SELECT version FROM revival_member WHERE id = ?").param(member).query(Long.class).single(), "capabilities", List.of("FINANCE"))));
        execute(STEERING_GROUP, "mutation($input: BudgetLineCreateInput!) { budgetLineCreate(input: $input) { id } }", Map.of("input", Map.of(
                "revivalId", revival, "categoryId", "FOOD", "description", "Mat under rephelgen", "quantity", 60, "unitAmount", "120")));
        transaction = Long.parseLong(execute(STEERING_GROUP, BudgetGraphqlApiIntegrationTest.TRANSACTION_CREATE, Map.of("input", Map.of(
                "revivalId", revival, "date", "2027-12-11", "amount", "2350.50", "categoryId", "FOOD", "description", "Mat till rephelgen",
                "paidBy", Map.of("personId", personId(STEERING_GROUP))))).path("transactionCreate.id").entity(String.class).get());
    }

    private RestTestClient.ResponseSpec upload(final String user, final String filename, final byte[] content) {
        return restTestClient().post().uri("/api/transactions/{id}/receipt", transaction)
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(receipt(filename, content))
                .exchange();
    }

    @Test
    void aReceiptIsUploadedDownloadedAndRemoved() {
        upload(STEERING_GROUP, "../../kvitto rephelg.pdf", PDF).expectStatus().isOk()
                .expectBody()
                .jsonPath("$.filename").isEqualTo("kvitto rephelg.pdf")
                .jsonPath("$.contentType").isEqualTo("application/pdf")
                .jsonPath("$.size").isEqualTo(PDF.length);

        final byte[] downloaded = restTestClient().get().uri("/api/transactions/{id}/receipt", transaction)
                .header(HttpHeaders.AUTHORIZATION, token(OBSERVER))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/pdf")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Content-Security-Policy", "default-src 'none'; sandbox")
                .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, value -> assertThat(value).startsWith("attachment").contains("kvitto"))
                .expectBody(byte[].class).returnResult().getResponseBody();

        assertThat(downloaded).isEqualTo(PDF);
        execute(STEERING_GROUP, "query($id: ID!) { revival(id: $id) { budget { transactions { receipt { filename } } totals { missingReceipts } } } }",
                Map.of("id", revival))
                .path("revival.budget.transactions[0].receipt.filename").entity(String.class).isEqualTo("kvitto rephelg.pdf")
                .path("revival.budget.totals.missingReceipts").entity(Integer.class).isEqualTo(0);

        restTestClient().delete().uri("/api/transactions/{id}/receipt", transaction).header(HttpHeaders.AUTHORIZATION, token(OBSERVER))
                .exchange().expectStatus().isForbidden();
        restTestClient().delete().uri("/api/transactions/{id}/receipt", transaction).header(HttpHeaders.AUTHORIZATION, token(DIRECTEUR))
                .exchange().expectStatus().isNoContent();
        restTestClient().get().uri("/api/transactions/{id}/receipt", transaction).header(HttpHeaders.AUTHORIZATION, token(DIRECTEUR))
                .exchange().expectStatus().isNotFound();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM receipt_content").query(Integer.class).single()).isZero();
    }

    @Test
    void onlyReceiptFilesAreAccepted() {
        upload(STEERING_GROUP, "kvitto.png", "<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST")
                .jsonPath("$.message").isEqualTo("A receipt must be a PDF, or a JPEG, PNG, HEIC or WebP image.");
        upload(STEERING_GROUP, "tom.pdf", new byte[0]).expectStatus().isBadRequest();
        upload(STEERING_GROUP, "stor.pdf", new byte[11 * 1024 * 1024]).expectStatus().value(status -> assertThat(status).isIn(400, 413));
    }

    @Test
    void onlyWritersUploadAndOnlyReadersSeeReceipts() {
        upload(OBSERVER, "kvitto.pdf", PDF).expectStatus().isForbidden();
        upload(PLAIN_USER, "kvitto.pdf", PDF).expectStatus().isNotFound();
        upload(STEERING_GROUP, "kvitto.pdf", PDF).expectStatus().isOk();
        restTestClient().get().uri("/api/transactions/{id}/receipt", transaction).header(HttpHeaders.AUTHORIZATION, token(PLAIN_USER))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void theBudgetIsExportedAsAWorkbookInTheLanguageAsked() throws IOException {
        upload(STEERING_GROUP, "kvitto.pdf", PDF).expectStatus().isOk();

        final byte[] workbook = Objects.requireNonNull(restTestClient().get().uri("/api/revivals/{id}/budget.xlsx?lang=en", revival)
                .header(HttpHeaders.AUTHORIZATION, token(OBSERVER))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectHeader().value(HttpHeaders.CONTENT_DISPOSITION, value -> assertThat(value).contains("budget-Ali-Baba-2028.xlsx"))
                .expectBody(byte[].class).returnResult().getResponseBody());

        try (XSSFWorkbook read = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
            assertThat(read.getSheetName(0)).isEqualTo("Summary");
            assertThat(read.getSheetName(3)).isEqualTo("Final report");
            assertThat(read.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Ali Baba 2028");

            final StringBuilder transactions = new StringBuilder();

            read.getSheetAt(2).forEach(row -> row.forEach(cell -> transactions.append(cell).append('|')));
            assertThat(transactions.toString()).contains("Mat till rephelgen", "Stina Styrgrupp", "kvitto.pdf").doesNotContain("@");
        }

        restTestClient().get().uri("/api/revivals/{id}/budget.xlsx", revival).header(HttpHeaders.AUTHORIZATION, token(PLAIN_USER))
                .exchange().expectStatus().isNotFound();
    }
}
