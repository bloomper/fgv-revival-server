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

import nu.fgv.revival.server.guide.GuideDraftDto;
import nu.fgv.revival.server.guide.GuideDraftService;
import nu.fgv.revival.server.guide.GuideService;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionService;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The super admin's guide work over MCP: deciding suggestions and editing the draft. Nothing here removes a part of
 * the draft, discards it or publishes it: publishing can delete revivals' checklists, so it stays in the web, behind
 * its warnings.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class GuideAdminMcpTools {

    private final GuideService guideService;
    private final GuideDraftService draftService;
    private final GuideSuggestionService suggestionService;
    private final GuideMcpTools guideTools;
    private final McpToolSupport support;

    public GuideAdminMcpTools(final GuideService guideService, final GuideDraftService draftService, final GuideSuggestionService suggestionService,
                              final GuideMcpTools guideTools, final McpToolSupport support) {
        this.guideService = guideService;
        this.draftService = draftService;
        this.suggestionService = suggestionService;
        this.guideTools = guideTools;
        this.support = support;
    }

    private GuideVersionView draft() {
        final GuideDraftDto draft = guideService.draft();

        if (draft == null) {
            throw new BadRequestException("error.mcp.noDraft");
        }
        return draft.draft();
    }

    @McpTool(name = "decide_guide_suggestion",
            description = "Super admins: accept an open suggestion into the draft, or reject it with a reason. Accepting applies it to the draft as proposed.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public GuideMcpTools.Suggestion decideGuideSuggestion(
            @McpToolParam(description = "The suggestion, by id") final Long suggestionId,
            @McpToolParam(description = "true to accept, false to reject") final Boolean accept,
            @McpToolParam(required = false, description = "A note when accepting, the reason when rejecting") final @Nullable String note) {
        return support.call("decide_guide_suggestion", () -> {
            final Long version = guideTools.suggestion(suggestionId).version();

            return GuideMcpTools.Suggestion.of(accept ? suggestionService.accept(suggestionId, version, note, null)
                    : suggestionService.reject(suggestionId, version, note));
        });
    }

    @McpTool(name = "get_guide_draft",
            description = "Super admins: get the guide's draft with every chapter, section and item, their ids, and how many accepted suggestions and untranslated texts it has.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public @Nullable GuideDraftDto getGuideDraft() {
        return support.call("get_guide_draft", guideService::draft);
    }

    @McpTool(name = "start_guide_draft",
            description = "Super admins: start a draft of the next guide version, a copy of the latest published one. There is at most one draft.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public @Nullable GuideDraftDto startGuideDraft() {
        return support.call("start_guide_draft", () -> {
            draftService.start();
            return guideService.draft();
        });
    }

    @McpTool(name = "update_guide_introduction",
            description = "Super admins: replace the draft's introduction, Markdown in Swedish and optionally English.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public GuideVersionView updateGuideIntroduction(
            @McpToolParam(description = "Swedish, Markdown") final String sv,
            @McpToolParam(required = false, description = "English, Markdown") final @Nullable String en) {
        return support.call("update_guide_introduction", () -> {
            final GuideVersionView draft = draft();

            return guideService.findById(draftService.updateIntroduction(draft.id(), draft.version(), sv, en));
        });
    }

    @McpTool(name = "create_guide_chapter",
            description = "Super admins: add a chapter to the draft, last or at a position (counting from 0).",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public GuideVersionView.Chapter createGuideChapter(
            @McpToolParam(description = "The title in Swedish") final String titleSv,
            @McpToolParam(required = false, description = "The title in English") final @Nullable String titleEn,
            @McpToolParam(required = false, description = "Its place, counting from 0") final @Nullable Integer position) {
        return support.call("create_guide_chapter", () -> guideService.draftChapter(draftService.createChapter(draft().id(), position, titleSv, titleEn)));
    }

    @McpTool(name = "update_guide_chapter",
            description = "Super admins: rename a chapter of the draft.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public GuideVersionView.Chapter updateGuideChapter(
            @McpToolParam(description = "The chapter, by id from get_guide_draft") final Long chapterId,
            @McpToolParam(description = "The title in Swedish") final String titleSv,
            @McpToolParam(required = false, description = "The title in English") final @Nullable String titleEn) {
        return support.call("update_guide_chapter", () -> {
            draftService.updateChapter(chapterId, guideService.draftChapter(chapterId).version(), titleSv, titleEn);
            return guideService.draftChapter(chapterId);
        });
    }

    @McpTool(name = "create_guide_section",
            description = "Super admins: add a section to a chapter of the draft, last or at a position (counting from 0).",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public GuideVersionView.Section createGuideSection(
            @McpToolParam(description = "The chapter, by id") final Long chapterId,
            @McpToolParam(description = "The title in Swedish") final String titleSv,
            @McpToolParam(required = false, description = "The title in English") final @Nullable String titleEn,
            @McpToolParam(required = false, description = "Its place, counting from 0") final @Nullable Integer position) {
        return support.call("create_guide_section", () -> guideService.draftSection(draftService.createSection(chapterId, position, titleSv, titleEn)));
    }

    @McpTool(name = "update_guide_section",
            description = "Super admins: rename a section of the draft.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public GuideVersionView.Section updateGuideSection(
            @McpToolParam(description = "The section, by id from get_guide_draft") final Long sectionId,
            @McpToolParam(description = "The title in Swedish") final String titleSv,
            @McpToolParam(required = false, description = "The title in English") final @Nullable String titleEn) {
        return support.call("update_guide_section", () -> guideService.draftSection(draftService.updateSection(sectionId,
                guideService.draftSection(sectionId).version(), titleSv, titleEn)));
    }

    @McpTool(name = "create_guide_item",
            description = "Super admins: add an item to the draft: in a chapter, or in one of its sections, last or at a position (counting from 0). The type is an item type's id, such as TIP, REQUIREMENT, QUESTION or TEXT.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public GuideVersionView.Item createGuideItem(
            @McpToolParam(description = "The chapter, by id") final Long chapterId,
            @McpToolParam(description = "An active item type's id, from list_catalog_entries with GUIDE_ITEM_TYPES") final String type,
            @McpToolParam(description = "The text in Swedish, Markdown") final String textSv,
            @McpToolParam(required = false, description = "The text in English, Markdown") final @Nullable String textEn,
            @McpToolParam(required = false, description = "A section of the chapter, by id") final @Nullable Long sectionId,
            @McpToolParam(required = false, description = "Its place, counting from 0") final @Nullable Integer position) {
        return support.call("create_guide_item", () -> guideService.draftItem(draftService.createItem(chapterId, sectionId, position, type, textSv, textEn)));
    }

    @McpTool(name = "update_guide_item",
            description = "Super admins: change an item of the draft: its type and text.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public GuideVersionView.Item updateGuideItem(
            @McpToolParam(description = "The item, by id from get_guide_draft") final Long itemId,
            @McpToolParam(description = "An item type's id") final String type,
            @McpToolParam(description = "The text in Swedish, Markdown") final String textSv,
            @McpToolParam(required = false, description = "The text in English, Markdown") final @Nullable String textEn) {
        return support.call("update_guide_item", () -> guideService.draftItem(draftService.updateItem(itemId, guideService.draftItem(itemId).version(), type,
                textSv, textEn)));
    }

    @McpTool(name = "move_guide_part",
            description = "Super admins: move a chapter, section or item of the draft to a position (counting from 0). A section can move to another chapter, an item to another chapter or section.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public GuideVersionView moveGuidePart(
            @McpToolParam(description = "CHAPTER, SECTION or ITEM") final Part part,
            @McpToolParam(description = "Its id") final Long id,
            @McpToolParam(description = "Its new place, counting from 0") final Integer position,
            @McpToolParam(required = false, description = "SECTION, ITEM: the chapter to move to, by id; default its own") final @Nullable Long chapterId,
            @McpToolParam(required = false, description = "ITEM: the section to move to, by id; leave out for the chapter itself") final @Nullable Long sectionId) {
        return support.call("move_guide_part", () -> {
            switch (part) {
                case CHAPTER -> draftService.moveChapter(id, guideService.draftChapter(id).version(), position);
                case SECTION -> {
                    final GuideVersionView.Section section = guideService.draftSection(id);

                    draftService.moveSection(id, section.version(), chapterId == null ? chapterIdOf(section.chapterKey()) : chapterId, position);
                }
                case ITEM -> {
                    final GuideVersionView.Item item = guideService.draftItem(id);

                    draftService.moveItem(id, item.version(), chapterId == null ? chapterIdOf(item.chapterKey()) : chapterId, sectionId, position);
                }
            }
            return draft();
        });
    }

    private Long chapterIdOf(final String chapterKey) {
        return draft().chapter(chapterKey).map(GuideVersionView.Chapter::id).orElseThrow(() -> new BadRequestException("error.mcp.noDraft"));
    }

    public enum Part {
        CHAPTER, SECTION, ITEM
    }
}
