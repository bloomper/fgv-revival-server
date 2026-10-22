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

import nu.fgv.revival.server.util.Locales;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * The guide's plain HTTP endpoints, for what GraphQL does not suit (constitution II, contracts/http.md). A thin adapter:
 * the services decide.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@RestController
public class GuideHttpApi {

    private final GuideService guideService;

    public GuideHttpApi(final GuideService guideService) {
        this.guideService = guideService;
    }

    @GetMapping("/api/guide/versions/{id}/pdf")
    public ResponseEntity<byte[]> versionPdf(@PathVariable final Long id, @RequestParam(required = false) final @Nullable String lang, final Locale locale) {
        return pdf(guideService.versionPdf(id, language(lang, locale)));
    }

    @GetMapping("/api/revivals/{id}/guide/pdf")
    public ResponseEntity<byte[]> revivalPdf(@PathVariable final Long id, @RequestParam(required = false) final @Nullable String lang, final Locale locale) {
        return pdf(guideService.revivalPdf(id, language(lang, locale)));
    }

    private static Locale language(final @Nullable String lang, final Locale requested) {
        return Locales.supported(lang == null || lang.isBlank() ? requested : Locale.of(lang));
    }

    private static ResponseEntity<byte[]> pdf(final GuideService.Pdf pdf) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(pdf.fileName()).build().toString())
                .body(pdf.content());
    }
}
