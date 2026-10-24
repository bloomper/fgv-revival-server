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

package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.audit.AuditSource;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Shared by the MCP tools, trimmed from Spexregister's: the audit stamp, refusals as readable messages, and the result
 * record.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Component
public class McpToolSupport {

    private final MessageSource messageSource;

    public McpToolSupport(final MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * A chapter of the version from a tool argument: its key, or its number counting from 1.
     */
    static GuideVersionView.Chapter chapter(final GuideVersionView version, final String chapter) {
        final String wanted = chapter.strip();

        if (wanted.chars().allMatch(Character::isDigit) && !wanted.isEmpty()) {
            final int number = Integer.parseInt(wanted);

            if (number >= 1 && number <= version.chapters().size()) {
                return version.chapters().get(number - 1);
            }
        }
        return version.chapter(wanted).orElseThrow(() -> new BadRequestException("error.chapter.invalid", chapter, version.chapters().size()));
    }

    /**
     * A date from a tool argument, {@code yyyy-MM-dd}, or null when blank.
     */
    static @Nullable LocalDate date(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (final DateTimeParseException e) {
            throw new BadRequestException("error.date.invalid");
        }
    }

    /**
     * Runs a tool with its audit stamp. A refusal becomes a tool error with the code from contracts/README.md and the
     * same English message the web shows, e.g. "BAD_REQUEST: Several members match ...".
     */
    <T> T call(final String tool, final Supplier<T> body) {
        audit(tool);
        try {
            return body.get();
        } catch (final AccessDeniedException e) {
            throw refusal("FORBIDDEN", "error.forbidden", null, e);
        } catch (final BadRequestException e) {
            throw refusal("BAD_REQUEST", e);
        } catch (final ResourceNotFoundException e) {
            throw refusal("NOT_FOUND", e);
        } catch (final ConflictException e) {
            throw refusal("CONFLICT", e);
        } catch (final OptimisticLockingFailureException e) {
            throw refusal("CONFLICT", "error.conflict", null, e);
        } catch (final SpexregisterUnavailableException e) {
            throw refusal("SPEXREGISTER_UNAVAILABLE", e);
        }
    }

    private McpToolRefusal refusal(final String code, final ResponseStatusException e) {
        return refusal(code, e.getDetailMessageCode(), e.getDetailMessageArguments(), e);
    }

    private McpToolRefusal refusal(final String code, final String messageCode, final Object @Nullable [] arguments, final Exception cause) {
        return new McpToolRefusal("%s: %s".formatted(code, messageSource.getMessage(messageCode, arguments, messageCode, Locale.ENGLISH)), cause);
    }

    /**
     * Records {@link AuditSource#MCP} for anything the tool changes (research R10).
     */
    void audit(final String tool) {
        AuditContext.stamp(AuditContext.currentOrSystem()
                .withSource(AuditSource.MCP)
                .withOperation("MCP %s".formatted(tool)));
    }

    static final class McpToolRefusal extends RuntimeException {

        McpToolRefusal(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    public record ListResult<T>(List<T> items) {
    }
}
