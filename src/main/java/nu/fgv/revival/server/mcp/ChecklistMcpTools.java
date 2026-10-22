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

import nu.fgv.revival.server.checklist.ChecklistItemDto;
import nu.fgv.revival.server.checklist.ChecklistItemFilterDto;
import nu.fgv.revival.server.checklist.ChecklistItemState;
import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.ChecklistService;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.Phase;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Checklists over MCP (contracts/mcp.md, FR-043, FR-044). Marking items not applicable is not offered here.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Component
public class ChecklistMcpTools {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final ChecklistService checklistService;
    private final McpToolSupport support;

    public ChecklistMcpTools(final ChecklistService checklistService, final McpToolSupport support) {
        this.checklistService = checklistService;
        this.support = support;
    }

    @McpTool(name = "list_checklist_items",
            description = "List items in a revival's checklists: one per phase A-J with the guide's requirements, and event checklists. Filters combine: mine, open, overdue, phase, checklist name.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public ChecklistItems listChecklistItems(
            @McpToolParam(description = "A revival you can see") final Long revivalId,
            @McpToolParam(required = false, description = "Items you own") final @Nullable Boolean mine,
            @McpToolParam(required = false, description = "Items not done and not marked not applicable") final @Nullable Boolean open,
            @McpToolParam(required = false, description = "Open items past their deadline") final @Nullable Boolean overdue,
            @McpToolParam(required = false, description = "A-J: that phase's checklist") final @Nullable String phase,
            @McpToolParam(required = false, description = "Words in an event checklist's name, e.g. \"Rehearsal weekend 2\"") final @Nullable String checklist,
            @McpToolParam(required = false, description = "1-200, default 50") final @Nullable Integer limit) {
        return support.call("list_checklist_items", () -> {
            final List<ChecklistItemDto> items = checklistService.items(revivalId, new ChecklistItemFilterDto(mine, open, overdue, null, null),
                    McpToolSupport.phase(phase), checklist, Locale.ENGLISH);
            final int first = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

            return new ChecklistItems(items.stream().limit(first).map(ChecklistMcpTools::toMcp).toList(), items.size());
        });
    }

    @McpTool(name = "complete_checklist_item",
            description = "Mark a checklist item done, by its id from list_checklist_items. Optionally add a comment.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ChecklistItem completeChecklistItem(
            @McpToolParam(description = "The item's id") final Long itemId,
            @McpToolParam(required = false, description = "Replaces the comment") final @Nullable String comment) {
        return support.call("complete_checklist_item", () -> {
            return toMcp(checklistService.complete(itemId, comment, Locale.ENGLISH));
        });
    }


    static ChecklistItem toMcp(final ChecklistItemDto item) {
        return new ChecklistItem(item.id(),
                new ChecklistRef(item.checklist().kind(), item.checklist().name(), item.checklist().phase(), item.checklist().date()),
                item.required(), Text.of(item.text()), item.state(), item.overdue(), McpOwner.of(item.owner()),
                item.deadline(), item.comment());
    }

    public record ChecklistRef(ChecklistKind kind, @Nullable String name, @Nullable Phase phase, @Nullable LocalDate date) {
    }

    public record Text(String sv, @Nullable String en) {

        static Text of(final GuideVersionView.Text text) {
            return new Text(text.sv(), text.en());
        }
    }

    public record ChecklistItem(Long id, ChecklistRef checklist, boolean required, Text text, ChecklistItemState state, boolean overdue,
                                @Nullable McpOwner owner, @Nullable LocalDate deadline, @Nullable String comment) {
    }

    public record ChecklistItems(List<ChecklistItem> items, int total) {
    }
}
