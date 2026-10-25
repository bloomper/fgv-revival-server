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
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.Amounts;
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

import java.math.BigDecimal;
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
 * @since 1.0
 */
@Component
public class McpToolSupport {

    private final MessageSource messageSource;
    private final OwnerResolver ownerResolver;
    private final CallerService callerService;

    public McpToolSupport(final MessageSource messageSource, final OwnerResolver ownerResolver, final CallerService callerService) {
        this.messageSource = messageSource;
        this.ownerResolver = ownerResolver;
        this.callerService = callerService;
    }

    /**
     * An amount from a tool argument, in kronor with at most two decimals, or null when blank.
     */
    static @Nullable BigDecimal amount(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        final BigDecimal parsed = Amounts.parse(value);

        if (parsed == null) {
            throw new BadRequestException("error.budget.amount", "0", "10000000");
        }
        return parsed;
    }

    /**
     * The one item of a list with this id, or NOT_FOUND as the API says for it.
     */
    static <T> T byId(final List<T> items, final java.util.function.Function<T, ?> id, final Object wanted, final Class<?> type) {
        return items.stream()
                .filter(item -> String.valueOf(id.apply(item)).equals(String.valueOf(wanted)))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(type, wanted));
    }

    /**
     * An owner from a tool argument: a display name or "me". A unique member match is that member, none a free-text
     * name; several are refused with the candidates. Blank is no owner.
     */
    @Nullable OwnerInputDto owner(final Long revivalId, final @Nullable String owner) {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        return switch (ownerResolver.resolve(revivalId, owner, callerService.current())) {
            case OwnerResolver.Resolution.Member member -> new OwnerInputDto(member.personId(), null);
            case OwnerResolver.Resolution.FreeText name -> new OwnerInputDto(null, name.name());
            case OwnerResolver.Resolution.Ambiguous ambiguous ->
                    throw new BadRequestException("error.owner.ambiguous", owner.strip(), String.join(", ", ambiguous.candidates()));
        };
    }

    /**
     * Directeurs and steering group members by display name or "me", e.g. a meeting's attendees. Someone outside the
     * steering group is refused: they go in the free text of who else was there.
     */
    @Nullable List<Long> members(final Long revivalId, final @Nullable List<String> names) {
        if (names == null) {
            return null;
        }
        return names.stream()
                .map(name -> {
                    final OwnerInputDto resolved = owner(revivalId, name);

                    if (resolved == null || resolved.personId() == null) {
                        throw new BadRequestException("error.mcp.notMember", name.strip());
                    }
                    return resolved.personId();
                })
                .distinct()
                .toList();
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
     * Runs a tool with its audit stamp. A refusal becomes a tool error with the API's error code and the
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
     * Records {@link AuditSource#MCP} for anything the tool changes.
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
