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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PDF endpoints.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class GuideHttpApiPdfIntegrationTest extends AbstractIntegrationTest {

    private long version;
    private long revival;

    private static String firstPage(final byte[] pdf) throws IOException {
        return new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
    }

    @BeforeEach
    void publishedGuideAndARevival() {
        version = publishedGuide();
        revival = createRevival(ALI_BABA, 2028);
        setStatus(revival, "APPROVED");
        login(DIRECTEUR);
        login(STEERING_GROUP);
        addMember(revival, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(revival, STEERING_GROUP, "STEERING_GROUP", List.of());
        setStatus(revival, "ORGANISING");
    }

    private RestTestClient.ResponseSpec get(final String user, final String path, final String language) {
        return restTestClient().get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .header(HttpHeaders.ACCEPT_LANGUAGE, language)
                .exchange();
    }

    @Test
    void readersGetTheVersionAsAPdf() throws IOException {
        for (final String user : List.of(SUPER_ADMIN, OBSERVER, DIRECTEUR, STEERING_GROUP)) {
            final byte[] pdf = Objects.requireNonNull(get(user, "/api/guide/versions/" + version + "/pdf", "sv")
                    .expectStatus().isOk()
                    .expectHeader().contentType("application/pdf")
                    .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nyuppsattningsguide-v1-sv.pdf\"")
                    .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                    .expectBody(byte[].class).returnResult().getResponseBody());

            assertThat(firstPage(pdf)).contains("Version v1");
        }
    }

    @Test
    void theRevivalsGuideNamesTheRevival() throws IOException {
        final byte[] pdf = Objects.requireNonNull(get(STEERING_GROUP, "/api/revivals/" + revival + "/guide/pdf?lang=en", "sv")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nyuppsattningsguide-ali-baba-2028-en.pdf\"")
                .expectBody(byte[].class).returnResult().getResponseBody());

        assertThat(firstPage(pdf)).contains("Revival guide").contains("For Ali Baba 2028");
    }

    @Test
    void theLanguageFollowsAcceptLanguageWithoutLang() throws IOException {
        final byte[] pdf = Objects.requireNonNull(get(DIRECTEUR, "/api/guide/versions/" + version + "/pdf", "en")
                .expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody());

        assertThat(firstPage(pdf)).contains("Revival guide");
    }

    @Test
    void theDraftIsOnlyForSuperAdmins() {
        graphQl(SUPER_ADMIN).document("mutation { guideDraftStart { draft { id } } }").execute();

        final long draft = jdbcClient.sql("SELECT id FROM guide_version WHERE status = 'DRAFT'").query(Long.class).single();

        get(SUPER_ADMIN, "/api/guide/versions/" + draft + "/pdf", "sv")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nyuppsattningsguide-utkast-sv.pdf\"");
        for (final String user : List.of(OBSERVER, DIRECTEUR, STEERING_GROUP)) {
            get(user, "/api/guide/versions/" + draft + "/pdf", "sv").expectStatus().isNotFound();
        }
    }

    @Test
    void othersAreRefused() {
        get(PLAIN_USER, "/api/guide/versions/" + version + "/pdf", "sv").expectStatus().isNotFound();
        get(PLAIN_USER, "/api/revivals/" + revival + "/guide/pdf", "sv").expectStatus().isNotFound();
        restTestClient().get().uri("/api/guide/versions/" + version + "/pdf").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void aRevivalWithoutAVersionIsNotFound() {
        jdbcClient.sql("UPDATE revival SET guide_version_id = NULL WHERE id = ?").param(revival).update();

        final String body = new String(Objects.requireNonNull(get(SUPER_ADMIN, "/api/revivals/" + revival + "/guide/pdf", "en")
                .expectStatus().isNotFound()
                .expectBody(byte[].class).returnResult().getResponseBody()));

        assertThat(body).contains("The guide has not been published yet.");
    }

    @Test
    void itWorksWhileSpexregisterIsDown() {
        spexregisterDown(false);

        get(DIRECTEUR, "/api/revivals/" + revival + "/guide/pdf", "sv").expectStatus().isOk();
        get(DIRECTEUR, "/api/guide/versions/" + version + "/pdf", "sv").expectStatus().isOk();
    }
}
