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

import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The Excel import's preview and import (contracts/http.md, constitution II). A thin adapter: every rule is in
 * {@link LogImportService}.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@RestController
public class LogHttpApi {

    private final LogImportService importService;
    private final ObjectMapper objectMapper;

    public LogHttpApi(final LogImportService importService, final ObjectMapper objectMapper) {
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    /**
     * The {@code choices} part as JSON. Owner ids arrive as strings or numbers, as GraphQL ids do.
     */
    record ChoicesInput(@Nullable Map<String, @Nullable Object> owners, @Nullable LogEntryType nobelType) {
    }

    @PostMapping(path = "/api/revivals/{id}/log/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public LogImportPreviewDto preview(@PathVariable final Long id, @RequestPart("file") final MultipartFile file) {
        return importService.preview(id, fileName(file), bytes(file));
    }

    @PostMapping(path = "/api/revivals/{id}/log/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public LogImportDto importLog(@PathVariable final Long id, @RequestPart("file") final MultipartFile file,
                                  @RequestPart(name = "choices", required = false) final @Nullable String choices) {
        return importService.importLog(id, fileName(file), bytes(file), choices(choices));
    }

    private static String fileName(final MultipartFile file) {
        final String name = file.getOriginalFilename();

        return name == null || name.isBlank() ? "logg.xlsx" : name;
    }

    private static byte[] bytes(final MultipartFile file) {
        try {
            return file.getBytes();
        } catch (final IOException e) {
            throw new BadRequestException("error.log.import.notExcel");
        }
    }

    private LogImportService.Choices choices(final @Nullable String json) {
        if (json == null || json.isBlank()) {
            return LogImportService.Choices.none();
        }
        try {
            final ChoicesInput input = objectMapper.readValue(json, ChoicesInput.class);
            final Map<String, @Nullable Long> owners = new HashMap<>();

            if (input.owners() != null) {
                input.owners().forEach((name, id) -> owners.put(name, id == null || id.toString().isBlank() ? null : Long.valueOf(id.toString())));
            }
            return new LogImportService.Choices(owners, input.nobelType());
        } catch (final JacksonException | NumberFormatException e) {
            throw new BadRequestException("error.log.import.choices");
        }
    }
}
