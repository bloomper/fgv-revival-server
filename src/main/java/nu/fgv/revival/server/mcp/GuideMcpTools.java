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

import nu.fgv.revival.server.guide.GuideItemType;
import nu.fgv.revival.server.guide.GuideSearch;
import nu.fgv.revival.server.guide.GuideService;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionDto;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionKind;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionService;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionStatus;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * MCP tools for the guide (specs/003-m2-guide/contracts/mcp.md). A thin adapter over {@link GuideService} and
 * {@link GuideSuggestionService}: the same rules as GraphQL (FR-038). Output never names a person (constitution V).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Component
public class GuideMcpTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final GuideService guideService;
    private final GuideSuggestionService suggestionService;
    private final McpToolSupport support;

    public GuideMcpTools(final GuideService guideService, final GuideSuggestionService suggestionService, final McpToolSupport support) {
        this.guideService = guideService;
        this.suggestionService = suggestionService;
        this.support = support;
    }

    /**
     * The version to read: the one a revival follows when one is given, otherwise the latest published one. Never the
     * draft (FR-037).
     */
    private GuideVersionView version(final @Nullable Long revivalId) {
        return revivalId == null
                ? guideService.findLatest().orElseThrow(() -> new ResourceNotFoundException("error.guide.notPublished"))
                : guideService.forRevival(revivalId).orElseThrow(() -> new ResourceNotFoundException("error.guide.notPublished"));
    }

    @McpTool(name = "search_guide",
            description = "Search the revival guide for a word or phrase. Give a revival id to search the version that revival follows; otherwise the latest published version is searched.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public SearchResult searchGuide(
            @McpToolParam(description = "Word or phrase, at least 2 characters") final String query,
            @McpToolParam(required = false, description = "A revival you can see") final @Nullable Long revivalId,
            @McpToolParam(required = false, description = "1-50, default 20") final @Nullable Integer limit) {
        return support.call("search_guide", () -> {
            final GuideVersionView version = version(revivalId);
            final int first = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
            final GuideService.SearchResult result = guideService.search(version.id(), query, first);

            return new SearchResult(Version.of(result.version()), result.hits().stream().map(Hit::of).toList());
        });
    }

    @McpTool(name = "get_guide_section",
            description = "Read a chapter or one of its sections from the revival guide, with every item's type and text. Give a revival id to read the version that revival follows.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public SectionResult getGuideSection(
            @McpToolParam(description = "The chapter's number, counting from 1, or its key from search_guide") final String chapter,
            @McpToolParam(required = false, description = "A section key from search_guide. Without it, the whole chapter.") final @Nullable String sectionKey,
            @McpToolParam(required = false, description = "A revival you can see") final @Nullable Long revivalId) {
        return support.call("get_guide_section", () -> {
            final GuideVersionView version = version(revivalId);
            final GuideVersionView.Chapter found = McpToolSupport.chapter(version, chapter);

            if (sectionKey == null) {
                return new SectionResult(Version.of(version), found.key(), Text.of(found.title()),
                        found.items().stream().map(Item::of).toList(), found.sections().stream().map(Section::of).toList());
            }

            final GuideVersionView.Section section = found.sections().stream()
                    .filter(candidate -> candidate.key().equals(sectionKey))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(GuideVersionView.Section.class, sectionKey));

            return new SectionResult(Version.of(version), found.key(), Text.of(found.title()), List.of(), List.of(Section.of(section)));
        });
    }

    @McpTool(name = "suggest_guide_change",
            description = "Suggest a change to the revival guide on behalf of your revival: new text or type for an item, removing an item, or a new item in a chapter or section. The super admin decides; nothing in the guide changes now.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public SuggestionResult suggestGuideChange(
            @McpToolParam(description = "The revival you suggest from") final Long revivalId,
            @McpToolParam(description = "CHANGE, REMOVE or ADD") final GuideSuggestionKind kind,
            @McpToolParam(required = false, description = "CHANGE, REMOVE: the item's key from search_guide or get_guide_section") final @Nullable String itemKey,
            @McpToolParam(required = false, description = "ADD: the chapter's number, counting from 1, or its key") final @Nullable String chapter,
            @McpToolParam(required = false, description = "ADD: the section to add to") final @Nullable String sectionKey,
            @McpToolParam(required = false, description = "TIP, REQUIREMENT, QUESTION or TEXT") final @Nullable GuideItemType type,
            @McpToolParam(required = false, description = "Swedish text, Markdown") final @Nullable String textSv,
            @McpToolParam(required = false, description = "English text, Markdown") final @Nullable String textEn,
            @McpToolParam(required = false, description = "Why; required for CHANGE and REMOVE") final @Nullable String reason) {
        return support.call("suggest_guide_change", () -> {
            final GuideVersionView version = version(revivalId);
            final String chapterKey = chapter == null || chapter.isBlank() ? null : McpToolSupport.chapter(version, chapter).key();
            final GuideSuggestionDto created = suggestionService.create(new GuideSuggestionService.Proposal(
                    revivalId, version.id(), kind, itemKey, chapterKey, sectionKey, type, textSv, textEn, reason));

            return new SuggestionResult(created.id(), created.status(), created.kind(), new Version(version.id(), version.label(), version.publishedAt()));
        });
    }

    public record Text(String sv, @Nullable String en) {

        static Text of(final GuideVersionView.Text text) {
            return new Text(text.sv(), text.en());
        }
    }

    public record Version(Long id, @Nullable String label, @Nullable Instant publishedAt) {

        static Version of(final GuideVersionView version) {
            return new Version(version.id(), version.label(), version.publishedAt());
        }
    }

    public record Hit(GuideSearch.HitKind kind, @Nullable String chapterKey, @Nullable Text chapterTitle, @Nullable String sectionKey,
                      @Nullable Text sectionTitle, @Nullable String itemKey, @Nullable GuideItemType itemType, String snippet) {

        static Hit of(final GuideSearch.Hit hit) {
            return new Hit(hit.kind(), hit.chapterKey(), hit.chapterTitle() == null ? null : Text.of(hit.chapterTitle()), hit.sectionKey(),
                    hit.sectionTitle() == null ? null : Text.of(hit.sectionTitle()), hit.itemKey(), hit.itemType(), hit.snippet());
        }
    }

    public record SearchResult(Version version, List<Hit> items) {
    }

    public record Item(String key, GuideItemType type, Text text) {

        static Item of(final GuideVersionView.Item item) {
            return new Item(item.key(), item.type(), Text.of(item.text()));
        }
    }

    public record Section(String key, Text title, List<Item> items) {

        static Section of(final GuideVersionView.Section section) {
            return new Section(section.key(), Text.of(section.title()), section.items().stream().map(Item::of).toList());
        }
    }

    public record SectionResult(Version version, String chapterKey, Text chapterTitle, List<Item> items, List<Section> sections) {
    }

    public record SuggestionResult(Long id, GuideSuggestionStatus status, GuideSuggestionKind kind, Version version) {
    }
}
