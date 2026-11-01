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

import nu.fgv.revival.server.checklist.Checklist;
import nu.fgv.revival.server.checklist.ChecklistDto;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.checklist.ChecklistItemDto;
import nu.fgv.revival.server.checklist.ChecklistItemFilterDto;
import nu.fgv.revival.server.checklist.ChecklistItemState;
import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.ChecklistService;
import nu.fgv.revival.server.checklist.ChecklistSummaryDto;
import nu.fgv.revival.server.checklist.template.ChecklistTemplate;
import nu.fgv.revival.server.guide.GuideService;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.util.Locales;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Checklists over MCP. Marking items not applicable is not offered here.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ChecklistMcpTools {

    private final ExternalIds ids;

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final ChecklistService checklistService;
    private final GuideService guideService;
    private final McpToolSupport support;

    public ChecklistMcpTools(final ChecklistService checklistService, final GuideService guideService, final McpToolSupport support, final ExternalIds ids) {
        this.ids = ids;
        this.checklistService = checklistService;
        this.guideService = guideService;
        this.support = support;
    }

    static ChecklistItem toMcp(final ChecklistItemDto item) {
        return new ChecklistItem(item.id(),
                new ChecklistRef(item.checklist().kind(), item.checklist().name(), item.checklist().chapterKey(),
                        item.checklist().chapterTitle() == null ? null : Text.of(item.checklist().chapterTitle()), item.checklist().date()),
                item.required(), Text.of(item.text()), item.state(), item.overdue(), McpOwner.of(item.owner()),
                item.deadline(), item.comment());
    }

    @McpTool(name = "list_checklist_items",
            description = "List items in a revival's checklists: one per guide chapter with the guide's requirements, and event checklists. Filters combine: mine, open, overdue, chapter, checklist name.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public ChecklistItems listChecklistItems(
            @McpToolParam(description = "A revival you can see") final UUID revivalId,
            @McpToolParam(required = false, description = "Items you own") final @Nullable Boolean mine,
            @McpToolParam(required = false, description = "Items not done and not marked not applicable") final @Nullable Boolean open,
            @McpToolParam(required = false, description = "Open items past their deadline") final @Nullable Boolean overdue,
            @McpToolParam(required = false, description = "That chapter's checklist: the chapter's number, counting from 1, or its key") final @Nullable String chapter,
            @McpToolParam(required = false, description = "Words in an event checklist's name, e.g. \"Rehearsal weekend 2\"") final @Nullable String checklist,
            @McpToolParam(required = false, description = "1-200, default 50") final @Nullable Integer limit) {
        return support.call("list_checklist_items", () -> {
            final String chapterKey = chapter == null || chapter.isBlank() ? null
                    : McpToolSupport.chapter(guideService.forRevival(ids.idOf(Revival.class, revivalId)).orElseThrow(() -> new ResourceNotFoundException("error.guide.notPublished")),
                    chapter).key();
            final List<ChecklistItemDto> items = checklistService.items(ids.idOf(Revival.class, revivalId), new ChecklistItemFilterDto(mine, open, overdue, null, null),
                    chapterKey, checklist, Locale.ENGLISH);
            final int first = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

            return new ChecklistItems(items.stream().limit(first).map(ChecklistMcpTools::toMcp).toList(), items.size());
        });
    }

    @McpTool(name = "complete_checklist_item",
            description = "Mark a checklist item done, by its id from list_checklist_items. Optionally add a comment.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ChecklistItem completeChecklistItem(
            @McpToolParam(description = "The item's id") final UUID itemId,
            @McpToolParam(required = false, description = "Replaces the comment") final @Nullable String comment) {
        return support.call("complete_checklist_item", () -> toMcp(checklistService.complete(ids.idOf(ChecklistItem.class, itemId), comment, Locale.ENGLISH)));
    }

    static Checklist toMcp(final ChecklistDto checklist) {
        return new Checklist(checklist.id(), checklist.kind(), checklist.chapterKey(), checklist.chapterTitle() == null ? null : Text.of(checklist.chapterTitle()),
                checklist.name(), checklist.date(), checklist.eventId(), checklist.templateId(), checklist.cancelled(),
                checklist.items().stream().map(ChecklistMcpTools::toMcp).toList());
    }

    private static @Nullable LocalDate dateOrNull(final @Nullable String value) {
        return value == null || value.isBlank() ? null : McpToolSupport.date(value);
    }

    @McpTool(name = "list_checklists",
            description = "List a revival's checklists: one per chapter of its guide, and dated ones for events, with how many items are open and overdue.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<ChecklistSummaryDto> listChecklists(
            @McpToolParam(description = "A revival you can see") final UUID revivalId,
            @McpToolParam(required = false, description = "Also cancelled event checklists") final @Nullable Boolean includeCancelled) {
        return support.call("list_checklists", () -> new McpToolSupport.ListResult<>(checklistService.list(ids.idOf(Revival.class, revivalId), Boolean.TRUE.equals(includeCancelled))));
    }

    @McpTool(name = "get_checklist",
            description = "Get one checklist with all its items, guide requirements first.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Checklist getChecklist(@McpToolParam(description = "The checklist, by id from list_checklists") final UUID checklistId) {
        return support.call("get_checklist", () -> toMcp(checklistService.get(ids.idOf(Checklist.class, checklistId), Locale.ENGLISH)));
    }

    @McpTool(name = "set_checklist_item_state",
            description = "Mark a checklist item DONE, reopen it (OPEN) or mark it NOT_APPLICABLE. A guide requirement is marked not applicable only by a directeur, with a reason.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ChecklistItem setChecklistItemState(
            @McpToolParam(description = "The item's id") final UUID itemId,
            @McpToolParam(description = "OPEN, DONE or NOT_APPLICABLE") final ChecklistItemState state,
            @McpToolParam(required = false, description = "Why it does not apply") final @Nullable String reason) {
        return support.call("set_checklist_item_state", () -> {
            final ChecklistItemDto item = checklistService.getItem(ids.idOf(ChecklistItem.class, itemId), Locale.ENGLISH);

            return toMcp(checklistService.setState(ids.idOf(ChecklistItem.class, itemId), item.version(), state, reason, Locale.ENGLISH));
        });
    }

    @McpTool(name = "create_checklist_item",
            description = "Add an item to a checklist, with an optional owner, deadline and comment. Items are never deleted; mark one NOT_APPLICABLE instead.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public ChecklistItem createChecklistItem(
            @McpToolParam(description = "The checklist, by id") final UUID checklistId,
            @McpToolParam(description = "What to do") final String text,
            @McpToolParam(required = false, description = "A display name, or me. A unique member match is that member; several matches are refused with the candidates; none is a free-text owner.") final @Nullable String owner,
            @McpToolParam(required = false, description = "yyyy-MM-dd") final @Nullable String deadline,
            @McpToolParam(required = false, description = "A comment") final @Nullable String comment) {
        return support.call("create_checklist_item", () -> {
            final ChecklistDto checklist = checklistService.get(ids.idOf(Checklist.class, checklistId), Locale.ENGLISH);

            return toMcp(checklistService.createItem(new ChecklistService.NewItem(ids.idOf(Checklist.class, checklistId), text, support.owner(ids.idOf(Revival.class, checklist.revivalId()), owner),
                    dateOrNull(deadline), comment, null), Locale.ENGLISH));
        });
    }

    @McpTool(name = "update_checklist_item",
            description = "Change a checklist item's text (not a guide requirement's), owner, deadline or comment. Leave out what stays as it is; an empty string clears it.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ChecklistItem updateChecklistItem(
            @McpToolParam(description = "The item's id") final UUID itemId,
            @McpToolParam(required = false, description = "The text") final @Nullable String text,
            @McpToolParam(required = false, description = "As in create_checklist_item. An empty string clears it.") final @Nullable String owner,
            @McpToolParam(required = false, description = "yyyy-MM-dd. An empty string clears it.") final @Nullable String deadline,
            @McpToolParam(required = false, description = "An empty string clears it.") final @Nullable String comment) {
        return support.call("update_checklist_item", () -> {
            final ChecklistItemDto item = checklistService.getItem(ids.idOf(ChecklistItem.class, itemId), Locale.ENGLISH);
            final Long revivalId = ids.idOf(Revival.class, checklistService.get(ids.idOf(Checklist.class, item.checklist().id()), Locale.ENGLISH).revivalId());

            return toMcp(checklistService.updateItem(new ChecklistService.ItemChange(ids.idOf(ChecklistItem.class, itemId), item.version(), text,
                    support.owner(revivalId, owner), owner != null && owner.isBlank(), dateOrNull(deadline), deadline != null && deadline.isBlank(),
                    comment == null || comment.isBlank() ? null : comment, comment != null && comment.isBlank()), Locale.ENGLISH));
        });
    }

    @McpTool(name = "create_event_checklist",
            description = "Add a dated checklist to a revival that is not tied to a chapter, e.g. for a party, optionally filled from an event checklist template.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Checklist createEventChecklist(
            @McpToolParam(description = "A revival you can work in") final UUID revivalId,
            @McpToolParam(description = "Its name") final String name,
            @McpToolParam(required = false, description = "yyyy-MM-dd") final @Nullable String date,
            @McpToolParam(required = false, description = "An event checklist template, by id from list_checklist_templates") final @Nullable UUID templateId) {
        return support.call("create_event_checklist", () -> toMcp(checklistService.createEventChecklist(new ChecklistService.NewEventChecklist(ids.idOf(Revival.class, revivalId), name,
                dateOrNull(date), ids.idOfNullable(ChecklistTemplate.class, templateId)), Locales.SWEDISH)));
    }

    @McpTool(name = "update_event_checklist",
            description = "Rename an event checklist or change its date. An empty date clears it.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Checklist updateEventChecklist(
            @McpToolParam(description = "The checklist, by id") final UUID checklistId,
            @McpToolParam(required = false, description = "Its name") final @Nullable String name,
            @McpToolParam(required = false, description = "yyyy-MM-dd. An empty string clears it.") final @Nullable String date) {
        return support.call("update_event_checklist", () -> {
            final ChecklistDto checklist = checklistService.get(ids.idOf(Checklist.class, checklistId), Locale.ENGLISH);

            return toMcp(checklistService.updateEventChecklist(new ChecklistService.EventChecklistChange(ids.idOf(Checklist.class, checklistId), checklist.version(), name,
                    dateOrNull(date), date != null && date.isBlank()), Locale.ENGLISH));
        });
    }

    @McpTool(name = "cancel_event_checklist",
            description = "Cancel an event checklist that is no longer needed. It stays readable; checklists are never deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Checklist cancelEventChecklist(@McpToolParam(description = "The checklist, by id") final UUID checklistId) {
        return support.call("cancel_event_checklist", () -> {
            final ChecklistDto checklist = checklistService.get(ids.idOf(Checklist.class, checklistId), Locale.ENGLISH);

            return toMcp(checklistService.cancelEventChecklist(ids.idOf(Checklist.class, checklistId), checklist.version(), Locale.ENGLISH));
        });
    }

    @McpTool(name = "add_checklist_template_items",
            description = "Add a checklist template's items to a checklist, in Swedish: a chapter checklist gets its chapter's template, an event checklist the template given. Items it already has are not added twice.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Checklist addChecklistTemplateItems(
            @McpToolParam(description = "The checklist, by id") final UUID checklistId,
            @McpToolParam(required = false, description = "For an event checklist: the template, by id") final @Nullable UUID templateId) {
        return support.call("add_checklist_template_items", () -> {
            final ChecklistDto checklist = checklistService.get(ids.idOf(Checklist.class, checklistId), Locale.ENGLISH);

            return toMcp(checklistService.addFromTemplate(ids.idOf(Checklist.class, checklistId), checklist.version(), ids.idOfNullable(ChecklistTemplate.class, templateId), Locales.SWEDISH));
        });
    }

    /**
     * A checklist as get_checklist returns it.
     */
    public record Checklist(UUID id, ChecklistKind kind, @Nullable String chapterKey, @Nullable Text chapterTitle, @Nullable String name,
                            @Nullable LocalDate date, @Nullable UUID eventId, @Nullable UUID templateId, boolean cancelled,
                            List<ChecklistItem> items) {
    }

    public record ChecklistRef(ChecklistKind kind, @Nullable String name, @Nullable String chapterKey,
                               @Nullable Text chapterTitle,
                               @Nullable LocalDate date) {
    }

    public record Text(String sv, @Nullable String en) {

        static Text of(final GuideVersionView.Text text) {
            return new Text(text.sv(), text.en());
        }
    }

    public record ChecklistItem(UUID id, ChecklistRef checklist, boolean required, Text text, ChecklistItemState state,
                                boolean overdue,
                                @Nullable McpOwner owner, @Nullable LocalDate deadline, @Nullable String comment) {
    }

    public record ChecklistItems(List<ChecklistItem> items, int total) {
    }
}
