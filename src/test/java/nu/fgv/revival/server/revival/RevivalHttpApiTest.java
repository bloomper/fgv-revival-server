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

package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.AbstractApiTest;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest(RevivalHttpApi.class)
class RevivalHttpApiTest extends AbstractApiTest {

    @MockitoBean
    private RevivalService revivalService;

    @Test
    void servesThePosterSandboxed() throws Exception {
        when(revivalService.poster(1L)).thenReturn(Optional.of(new SpexregisterClient.Poster(new byte[]{1, 2}, "image/svg+xml;charset=UTF-8")));

        mockMvc
                .perform(get("/api/revivals/{id}/poster", 1L).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2}))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/svg+xml;charset=UTF-8"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andDo(document(
                        "revival-poster-get",
                        pathParameters(parameterWithName("id").description("The id of the revival")),
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("The poster's image type"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("Private, for an hour"),
                                headerWithName("Content-Security-Policy").description("Sandboxes the image, since an SVG opened on its own could otherwise run script"),
                                headerWithName("X-Content-Type-Options").description("`nosniff`")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void noPosterIsNoContent() throws Exception {
        when(revivalService.poster(1L)).thenReturn(Optional.empty());

        mockMvc
                .perform(get("/api/revivals/{id}/poster", 1L))
                .andExpect(status().isNoContent());
    }

    @Test
    void anythingButAnImageIsNoPoster() throws Exception {
        when(revivalService.poster(1L)).thenReturn(Optional.of(new SpexregisterClient.Poster(new byte[]{1}, "text/html")));
        when(revivalService.poster(2L)).thenReturn(Optional.of(new SpexregisterClient.Poster(new byte[]{1}, "not a type")));

        mockMvc.perform(get("/api/revivals/{id}/poster", 1L)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/revivals/{id}/poster", 2L)).andExpect(status().isNoContent());
    }

    @Test
    void aRevivalTheCallerMayNotSeeIsNotFound() throws Exception {
        when(revivalService.poster(1L)).thenThrow(new ResourceNotFoundException(Revival.class, 1L));

        mockMvc
                .perform(get("/api/revivals/{id}/poster", 1L).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andDo(document(
                        "error",
                        preprocessResponse(prettyPrint()),
                        errorResponseFields
                ));
    }
}
