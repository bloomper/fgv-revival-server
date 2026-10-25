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

import nu.fgv.revival.server.util.AbstractApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.multipart;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.partWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.restdocs.request.RequestDocumentation.requestParts;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest(BudgetHttpApi.class)
class BudgetHttpApiTest extends AbstractApiTest {

    private static final byte[] RECEIPT = {'%', 'P', 'D', 'F'};

    @MockitoBean
    private BudgetExportService exportService;

    @MockitoBean
    private TransactionService transactionService;

    @Test
    void exportsTheBudgetAsAWorkbook() throws Exception {
        when(exportService.export(1L, Locale.of("sv"))).thenReturn(new BudgetExportService.Export("budget.xlsx", new byte[]{'P', 'K'}));

        mockMvc
                .perform(get("/api/revivals/{id}/budget.xlsx", 1L).queryParam("lang", "sv").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, BudgetHttpApi.XLSX.toString()))
                .andExpect(content().bytes(new byte[]{'P', 'K'}))
                .andDo(document(
                        "revival-budget-export-get",
                        pathParameters(parameterWithName("id").description("The id of the revival")),
                        queryParameters(parameterWithName("lang").description("`sv` or `en`; without it, the request's language").optional()),
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("An Excel workbook"),
                                headerWithName(HttpHeaders.CONTENT_DISPOSITION).description("An attachment, with the file name"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("`no-store`")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void uploadsAReceipt() throws Exception {
        when(transactionService.uploadReceipt(eq(1L), eq("receipt.pdf"), any()))
                .thenReturn(new BudgetDtos.Receipt("receipt.pdf", "application/pdf", RECEIPT.length, Instant.parse("2026-09-01T10:15:00Z")));

        mockMvc
                .perform(multipart("/api/transactions/{id}/receipt", 1L)
                        .file(new MockMultipartFile("file", "receipt.pdf", "application/pdf", RECEIPT))
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("receipt.pdf"))
                .andDo(document(
                        "transaction-receipt-upload",
                        preprocessResponse(prettyPrint()),
                        pathParameters(parameterWithName("id").description("The id of the transaction")),
                        requestParts(partWithName("file").description("The receipt: PDF, JPEG, PNG, WebP or HEIC, at most 10 MB")),
                        secureRequestHeaders,
                        responseFields(
                                fieldWithPath("filename").description("The receipt's file name"),
                                fieldWithPath("contentType").description("The receipt's type"),
                                fieldWithPath("size").description("The receipt's size in bytes"),
                                fieldWithPath("uploadedAt").description("When the receipt was uploaded")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void aMissingFileIsABadRequest() throws Exception {
        mockMvc
                .perform(multipart("/api/transactions/{id}/receipt", 1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void servesAReceiptAsASandboxedAttachment() throws Exception {
        when(transactionService.receipt(1L)).thenReturn(new TransactionService.ReceiptFile("receipt.pdf", "application/pdf", RECEIPT));

        mockMvc
                .perform(get("/api/transactions/{id}/receipt", 1L).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(content().bytes(RECEIPT))
                .andDo(document(
                        "transaction-receipt-get",
                        pathParameters(parameterWithName("id").description("The id of the transaction")),
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("The receipt's type"),
                                headerWithName(HttpHeaders.CONTENT_DISPOSITION).description("Always an attachment, so a crafted file cannot run in the application's origin"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("`no-store`"),
                                headerWithName("Content-Security-Policy").description("Sandboxes the file"),
                                headerWithName("X-Content-Type-Options").description("`nosniff`")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void removesAReceipt() throws Exception {
        mockMvc
                .perform(delete("/api/transactions/{id}/receipt", 1L).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNoContent())
                .andDo(document(
                        "transaction-receipt-delete",
                        pathParameters(parameterWithName("id").description("The id of the transaction")),
                        secureRequestHeaders,
                        security(List.of("USER"))
                ));

        verify(transactionService).removeReceipt(1L);
    }
}
