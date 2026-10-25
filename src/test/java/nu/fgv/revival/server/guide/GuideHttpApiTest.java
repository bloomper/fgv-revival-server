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

import nu.fgv.revival.server.util.AbstractApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.restdocs.headers.ResponseHeadersSnippet;
import org.springframework.restdocs.request.QueryParametersSnippet;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Locale;

import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest(GuideHttpApi.class)
class GuideHttpApiTest extends AbstractApiTest {

    private static final byte[] PDF = {'%', 'P', 'D', 'F'};

    private final QueryParametersSnippet languageParameter = queryParameters(
            parameterWithName("lang").description("`sv` or `en`; without it, the request's language").optional()
    );
    private final ResponseHeadersSnippet pdfResponseHeaders = responseHeaders(
            headerWithName(HttpHeaders.CONTENT_TYPE).description("`application/pdf`"),
            headerWithName(HttpHeaders.CONTENT_DISPOSITION).description("An attachment, with the file name"),
            headerWithName(HttpHeaders.CACHE_CONTROL).description("`no-store`")
    );

    @MockitoBean
    private GuideService guideService;

    @Test
    void servesAVersionAsPdf() throws Exception {
        when(guideService.versionPdf(1L, Locale.ENGLISH)).thenReturn(new GuideService.Pdf(PDF, "guide-v1.pdf"));

        mockMvc
                .perform(get("/api/guide/versions/{id}/pdf", 1L).queryParam("lang", "en").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"guide-v1.pdf\""))
                .andExpect(content().bytes(PDF))
                .andDo(document(
                        "guide-version-pdf-get",
                        pathParameters(parameterWithName("id").description("The id of the guide version")),
                        languageParameter,
                        secureRequestHeaders,
                        pdfResponseHeaders,
                        security(List.of("USER"))
                ));
    }

    @Test
    void servesTheVersionARevivalIsPinnedToAsPdf() throws Exception {
        when(guideService.revivalPdf(1L, Locale.of("sv"))).thenReturn(new GuideService.Pdf(PDF, "guide-v1.pdf"));

        mockMvc
                .perform(get("/api/revivals/{id}/guide/pdf", 1L).queryParam("lang", "sv").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF))
                .andDo(document(
                        "revival-guide-pdf-get",
                        pathParameters(parameterWithName("id").description("The id of the revival")),
                        languageParameter,
                        secureRequestHeaders,
                        pdfResponseHeaders,
                        security(List.of("USER"))
                ));
    }
}
