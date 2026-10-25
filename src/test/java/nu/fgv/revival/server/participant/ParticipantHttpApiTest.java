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

package nu.fgv.revival.server.participant;

import nu.fgv.revival.server.util.AbstractApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest(ParticipantHttpApi.class)
class ParticipantHttpApiTest extends AbstractApiTest {

    @MockitoBean
    private ParticipationService participation;

    @Test
    void servesTheSwishQrCodeSandboxed() throws Exception {
        when(participation.swishQr(eq(1L), any(Locale.class))).thenReturn("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));

        mockMvc
                .perform(get("/api/revivals/{id}/swish-qr", 1L).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/svg+xml"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox"))
                .andDo(document(
                        "revival-swish-qr-get",
                        pathParameters(parameterWithName("id").description("The id of the revival")),
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("`image/svg+xml`"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("Private, for an hour"),
                                headerWithName("Content-Security-Policy").description("Sandboxes the image, since an SVG opened on its own could otherwise run script"),
                                headerWithName("X-Content-Type-Options").description("`nosniff`")
                        ),
                        security(List.of("USER"))
                ));
    }
}
