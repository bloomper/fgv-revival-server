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

import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A revival's budget over plain HTTP, for what GraphQL does not suit: the Excel export, and uploading,
 * downloading and removing receipts.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class BudgetHttpApi {

    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BudgetExportService exportService;
    private final TransactionService transactionService;

    public BudgetHttpApi(final BudgetExportService exportService, final TransactionService transactionService) {
        this.exportService = exportService;
        this.transactionService = transactionService;
    }

    private static byte[] bytes(final MultipartFile file) {
        try {
            return file.getBytes();
        } catch (final IOException e) {
            throw new BadRequestException("error.receipt.empty");
        }
    }

    /**
     * The budget as a workbook, in {@code lang} (sv or en) or the request's language.
     */
    @GetMapping("/api/revivals/{id}/budget.xlsx")
    public ResponseEntity<byte[]> export(@PathVariable final Long id, @RequestParam(name = "lang", required = false) final @Nullable String lang,
                                         final Locale locale) {
        final BudgetExportService.Export export = exportService.export(id, lang == null || lang.isBlank() ? locale : Locale.of(lang.strip()));

        return ResponseEntity.ok()
                .contentType(XLSX)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(export.content());
    }

    @PostMapping(path = "/api/transactions/{id}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public BudgetDtos.Receipt uploadReceipt(@PathVariable final Long id, @RequestPart(name = "file", required = false) final @Nullable MultipartFile file) {
        if (file == null) {
            throw new BadRequestException("error.receipt.empty");
        }
        return transactionService.uploadReceipt(id, file.getOriginalFilename(), bytes(file));
    }

    /**
     * The file as an attachment, never shown inline, so a crafted file cannot run in the application's origin.
     */
    @GetMapping("/api/transactions/{id}/receipt")
    public ResponseEntity<byte[]> receipt(@PathVariable final Long id) {
        final TransactionService.ReceiptFile receipt = transactionService.receipt(id);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(receipt.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(receipt.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(receipt.content());
    }

    @DeleteMapping("/api/transactions/{id}/receipt")
    public ResponseEntity<Void> removeReceipt(@PathVariable final Long id) {
        transactionService.removeReceipt(id);
        return ResponseEntity.noContent().build();
    }
}
