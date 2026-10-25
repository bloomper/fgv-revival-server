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

import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateDto;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItem;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItemDto;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

/**
 * Checklist templates over MCP: chapter templates whose items the guide sync copies into new chapter checklists, and
 * event templates. Super admins write them; templates and their items are retired, never deleted.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ChecklistTemplateMcpTools {

    private final ChecklistTemplateService templateService;
    private final McpToolSupport support;

    public ChecklistTemplateMcpTools(final ChecklistTemplateService templateService, final McpToolSupport support) {
        this.templateService = templateService;
        this.support = support;
    }

    private static ChecklistTemplateService.@Nullable Texts texts(final @Nullable String sv, final @Nullable String en) {
        return sv == null ? null : new ChecklistTemplateService.Texts(sv, en);
    }

    private static @Nullable RelativeDeadline deadline(final MailingMcpTools.@Nullable Due due) {
        return due == null ? null : RelativeDeadline.of(due.kind(), due.amount(), due.weekday());
    }

    private ChecklistTemplateItemDto item(final Long templateId, final Long itemId) {
        return McpToolSupport.byId(Stream.concat(templateService.get(templateId).items().stream(), templateService.retiredItems(templateId).stream()).toList(),
                ChecklistTemplateItemDto::id, itemId, ChecklistTemplateItem.class);
    }

    @McpTool(name = "list_checklist_templates",
            description = "List the checklist templates: CHAPTER ones, one per guide chapter, and EVENT ones, e.g. for a rehearsal weekend, with their items.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<ChecklistTemplateDto> listChecklistTemplates(
            @McpToolParam(required = false, description = "Also retired ones") final @Nullable Boolean includeRetired) {
        return support.call("list_checklist_templates", () -> new McpToolSupport.ListResult<>(templateService.list(Boolean.TRUE.equals(includeRetired))));
    }

    @McpTool(name = "create_checklist_template",
            description = "Super admins: add a checklist template, for a guide chapter (CHAPTER, with its key) or for events (EVENT, with a name).",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public ChecklistTemplateDto createChecklistTemplate(
            @McpToolParam(description = "CHAPTER or EVENT") final ChecklistKind kind,
            @McpToolParam(required = false, description = "CHAPTER: the chapter's key, from search_guide or get_guide_draft") final @Nullable String chapterKey,
            @McpToolParam(required = false, description = "EVENT: the name in Swedish") final @Nullable String nameSv,
            @McpToolParam(required = false, description = "EVENT: the name in English") final @Nullable String nameEn) {
        return support.call("create_checklist_template", () -> templateService.create(new ChecklistTemplateService.NewTemplate(kind, chapterKey,
                texts(nameSv, nameEn))));
    }

    @McpTool(name = "update_checklist_template",
            description = "Super admins: rename an event checklist template.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ChecklistTemplateDto updateChecklistTemplate(
            @McpToolParam(description = "The template, by id") final Long templateId,
            @McpToolParam(description = "The name in Swedish") final String nameSv,
            @McpToolParam(required = false, description = "The name in English") final @Nullable String nameEn) {
        return support.call("update_checklist_template", () -> templateService.update(templateId, templateService.get(templateId).version(),
                texts(nameSv, nameEn)));
    }

    @McpTool(name = "set_checklist_template_retired",
            description = "Super admins: retire a checklist template so it is no longer used, or restore it.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ChecklistTemplateDto setChecklistTemplateRetired(
            @McpToolParam(description = "The template, by id") final Long templateId,
            @McpToolParam(description = "true to retire, false to restore") final Boolean retired) {
        return support.call("set_checklist_template_retired", () -> {
            final Long version = templateService.get(templateId).version();

            return retired ? templateService.retire(templateId, version) : templateService.restore(templateId, version);
        });
    }

    @McpTool(name = "create_checklist_template_item",
            description = "Super admins: add an item to a checklist template, last or at a position (counting from 0), with an optional guide item, area of responsibility (whose holder becomes its owner) and deadline relative to the checklist's date.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public ChecklistTemplateItemDto createChecklistTemplateItem(
            @McpToolParam(description = "The template, by id") final Long templateId,
            @McpToolParam(description = "The text in Swedish") final String textSv,
            @McpToolParam(required = false, description = "The text in English") final @Nullable String textEn,
            @McpToolParam(required = false, description = "A guide item's key it belongs to") final @Nullable String guideItemKey,
            @McpToolParam(required = false, description = "An area of responsibility's id, from list_catalog_entries with CAPABILITIES") final @Nullable String defaultCapability,
            @McpToolParam(required = false, description = "When it is due, relative to the checklist's date") final MailingMcpTools.@Nullable Due deadline,
            @McpToolParam(required = false, description = "Its place, counting from 0") final @Nullable Integer position) {
        return support.call("create_checklist_template_item", () -> templateService.createItem(new ChecklistTemplateService.NewItem(templateId, position,
                new ChecklistTemplateService.Texts(textSv, textEn), guideItemKey, defaultCapability, deadline(deadline))));
    }

    @McpTool(name = "update_checklist_template_item",
            description = "Super admins: change a template item: its text, guide item, area of responsibility or deadline, and optionally its position (counting from 0). Leave out what stays as it is; an empty string clears the guide item or the area.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ChecklistTemplateDto updateChecklistTemplateItem(
            @McpToolParam(description = "The template, by id") final Long templateId,
            @McpToolParam(description = "The item, by id") final Long itemId,
            @McpToolParam(required = false, description = "The text in Swedish") final @Nullable String textSv,
            @McpToolParam(required = false, description = "The text in English") final @Nullable String textEn,
            @McpToolParam(required = false, description = "A guide item's key; an empty string clears it") final @Nullable String guideItemKey,
            @McpToolParam(required = false, description = "An area of responsibility's id; an empty string clears it") final @Nullable String defaultCapability,
            @McpToolParam(required = false, description = "When it is due") final MailingMcpTools.@Nullable Due deadline,
            @McpToolParam(required = false, description = "true clears the deadline") final @Nullable Boolean clearDeadline,
            @McpToolParam(required = false, description = "Its place, counting from 0") final @Nullable Integer position) {
        return support.call("update_checklist_template_item", () -> {
            ChecklistTemplateItemDto item = item(templateId, itemId);

            if (textSv != null || textEn != null || guideItemKey != null || defaultCapability != null || deadline != null || clearDeadline != null) {
                item = templateService.updateItem(new ChecklistTemplateService.ItemChange(itemId, item.version(),
                        textSv == null && textEn == null ? null : new ChecklistTemplateService.Texts(textSv == null ? item.text().sv() : textSv,
                                textEn == null ? item.text().en() : textEn),
                        guideItemKey == null || guideItemKey.isBlank() ? null : guideItemKey, guideItemKey != null && guideItemKey.isBlank(),
                        defaultCapability == null || defaultCapability.isBlank() ? null : defaultCapability,
                        defaultCapability != null && defaultCapability.isBlank(), deadline(deadline), Boolean.TRUE.equals(clearDeadline)));
            }
            return position == null ? templateService.get(templateId) : templateService.moveItem(itemId, item.version(), position);
        });
    }

    @McpTool(name = "set_checklist_template_item_retired",
            description = "Super admins: retire a template item so new checklists no longer get it, or restore it. Checklists that have it keep it.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ChecklistTemplateDto setChecklistTemplateItemRetired(
            @McpToolParam(description = "The template, by id") final Long templateId,
            @McpToolParam(description = "The item, by id") final Long itemId,
            @McpToolParam(description = "true to retire, false to restore") final Boolean retired) {
        return support.call("set_checklist_template_item_retired", () -> {
            final Long version = item(templateId, itemId).version();

            return retired ? templateService.retireItem(itemId, version) : templateService.restoreItem(itemId, version);
        });
    }
}
