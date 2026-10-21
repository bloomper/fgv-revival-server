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

package nu.fgv.revival.server.guide;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/guide/import} (contracts/http.md, FR-022–FR-027, SC-006).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
class GuideHttpApiImportIntegrationTest extends AbstractIntegrationTest {

    private static final String DRAFT = """
            {
                guideDraft {
                    draft { id status chapters { phase } }
                    importReport {
                        fileName
                        totals { sections tips requirements questions texts }
                        chapters { phase counts { sections tips requirements questions texts } }
                        warnings { code phase sectionTitle detail }
                    }
                    untranslatedCount
                }
            }""";

    private JsonNode body(final byte[] bytes) {
        return objectMapper.readTree(bytes);
    }

    @Test
    void aSuperAdminImportsADraftAndItsReport() {
        final JsonNode response = body(Objects.requireNonNull(importGuide(SUPER_ADMIN, GuideDocxFixtures.v5Layout().bytes(), "guide-v5.docx")
                .expectStatus().isCreated()
                .expectBody(byte[].class).returnResult().getResponseBody()));

        assertThat(response.get("draftId").asLong()).isPositive();
        assertThat(response.at("/report/fileName").asString()).isEqualTo("guide-v5.docx");
        assertThat(response.at("/report/totals/tips").asInt()).isEqualTo(6);
        assertThat(response.at("/report/totals/requirements").asInt()).isEqualTo(3);
        assertThat(response.at("/report/totals/questions").asInt()).isEqualTo(1);
        assertThat(response.at("/report/totals/texts").asInt()).isEqualTo(GuideDocxFixtures.V5_LAYOUT_TEXTS);
        assertThat(response.at("/report/totals/sections").asInt()).isEqualTo(4);
        assertThat(response.at("/report/chapters")).hasSize(10);
        assertThat(response.at("/report/warnings")).hasSize(3);

        graphQl(SUPER_ADMIN).document(DRAFT).execute()
                .path("guideDraft.draft.status").entity(String.class).isEqualTo("DRAFT")
                .path("guideDraft.draft.chapters").entityList(Object.class).hasSize(10)
                .path("guideDraft.importReport.fileName").entity(String.class).isEqualTo("guide-v5.docx")
                .path("guideDraft.importReport.totals.tips").entity(Integer.class).isEqualTo(6)
                .path("guideDraft.importReport.warnings[0].code").entity(String.class).isEqualTo("UNKNOWN_LABEL")
                .path("guideDraft.importReport.warnings[1].code").entity(String.class).isEqualTo("IMAGE")
                .path("guideDraft.importReport.warnings[1].sectionTitle").entity(String.class).isEqualTo("Tidslinjen");
    }

    @Test
    void everyoneElseIsRefused() {
        for (final String user : List.of(OBSERVER, DIRECTEUR, STEERING_GROUP, PLAIN_USER)) {
            final JsonNode response = body(Objects.requireNonNull(importGuide(user, GuideDocxFixtures.v5Layout().bytes(), "guide.docx")
                    .expectStatus().isForbidden()
                    .expectBody(byte[].class).returnResult().getResponseBody()));

            assertThat(response.get("code").asString()).isEqualTo("FORBIDDEN");
        }
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_version").query(Long.class).single()).isZero();
    }

    @Test
    void withoutATokenIsUnauthorized() {
        restTestClient().post().uri("/api/guide/import")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void aSecondImportIsAConflict() {
        importV5Layout();

        final JsonNode response = body(Objects.requireNonNull(importGuide(SUPER_ADMIN, GuideDocxFixtures.v5Layout().bytes(), "guide.docx")
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(byte[].class).returnResult().getResponseBody()));

        assertThat(response.get("code").asString()).isEqualTo("CONFLICT");
        assertThat(response.get("message").asString()).isEqualTo("A draft already exists. Publish or discard it first.");
    }

    @Test
    void anImportAfterPublishingIsRefused() {
        publishedGuide();

        final JsonNode response = body(Objects.requireNonNull(importGuide(SUPER_ADMIN, GuideDocxFixtures.v5Layout().bytes(), "guide.docx")
                .expectStatus().isBadRequest()
                .expectBody(byte[].class).returnResult().getResponseBody()));

        assertThat(response.get("message").asString()).startsWith("The guide has already been published.");
    }

    @Test
    void aFileOver10MegabytesIsTooLarge() {
        importGuide(SUPER_ADMIN, new byte[10 * 1024 * 1024 + 1], "big.docx")
                .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void aPdfIsNotAWordDocument() {
        final JsonNode response = body(Objects.requireNonNull(importGuide(SUPER_ADMIN, "%PDF-1.7".getBytes(StandardCharsets.US_ASCII), "guide.pdf")
                .expectStatus().isBadRequest()
                .expectBody(byte[].class).returnResult().getResponseBody()));

        assertThat(response.get("code").asString()).isEqualTo("BAD_REQUEST");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM guide_version").query(Long.class).single()).isZero();
    }

    @Test
    void errorsAreInSwedishByDefault() {
        importV5Layout();

        final JsonNode response = body(Objects.requireNonNull(importGuide(SUPER_ADMIN, GuideDocxFixtures.v5Layout().bytes(), "guide.docx", "sv")
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(byte[].class).returnResult().getResponseBody()));

        assertThat(response.get("message").asString()).isEqualTo("Det finns redan ett utkast. Publicera eller kasta det först.");
    }

    @Test
    void theImportIsAuditedAsWeb() {
        importV5Layout();

        assertThat(jdbcClient.sql("SELECT DISTINCT source FROM revinfo WHERE operation = 'guideImport'").query(String.class).list())
                .containsExactly("WEB");
    }
}
