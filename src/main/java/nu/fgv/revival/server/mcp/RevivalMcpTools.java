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

import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalDto;
import nu.fgv.revival.server.revival.RevivalService;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * MCP tools for revivals. A thin adapter over {@link RevivalService}: the same rules as GraphQL (FR-025). Members,
 * emails and contact details are never returned (constitution V).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Component
public class RevivalMcpTools {

    private final RevivalService revivalService;
    private final CallerService callerService;
    private final McpToolSupport support;

    public RevivalMcpTools(final RevivalService revivalService, final CallerService callerService, final McpToolSupport support) {
        this.revivalService = revivalService;
        this.callerService = callerService;
        this.support = support;
    }

    @McpTool(name = "list_revivals",
            description = "List the revivals you can see, with spex, year, working title, status and premiere date. Optionally filter by status.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<McpRevival> listRevivals(
            @McpToolParam(required = false, description = "Only these statuses: PROPOSED, APPROVED, ORGANISING, OPEN, CLOSING, SETTLING, ARCHIVED or REJECTED")
            final @Nullable List<RevivalStatus> status) {
        support.audit("list_revivals");

        final List<RevivalDto> revivals = revivalService.findVisible(status);
        final Caller caller = callerService.current();

        return new McpToolSupport.ListResult<>(revivals.stream().map(revival -> toMcpRevival(revival, caller)).toList());
    }

    private static McpRevival toMcpRevival(final RevivalDto revival, final Caller caller) {
        final RevivalRoleAssignment assignment = caller.roleIn(revival.id());

        return new McpRevival(
                revival.id(),
                revival.spex().title(),
                revival.spex().available(),
                revival.year(),
                revival.workingTitle(),
                revival.status(),
                revival.premiereDate(),
                assignment == null ? null : assignment.role());
    }

    /**
     * A revival as {@code list_revivals} returns it (contracts/mcp.md).
     */
    public record McpRevival(Long id,
                             @Nullable String spexTitle,
                             boolean spexAvailable,
                             Integer year,
                             String workingTitle,
                             RevivalStatus status,
                             @Nullable LocalDate premiereDate,
                             @Nullable RevivalRole role) {
    }
}
