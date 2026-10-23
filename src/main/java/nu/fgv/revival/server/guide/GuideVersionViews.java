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

package nu.fgv.revival.server.guide;

import nu.fgv.revival.server.config.CacheConfig;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads a version into a {@link GuideVersionView}. Published versions are cached without expiry because they never
 * change; the draft is read from the database every time (research R4). No access checks: callers check first.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Component
public class GuideVersionViews {

    private final GuideVersionRepository versionRepository;
    private final GuideChapterRepository chapterRepository;
    private final GuideSectionRepository sectionRepository;
    private final GuideItemRepository itemRepository;
    private final GuideItemTypeRepository typeRepository;
    private final GuideMarkdown markdown;

    public GuideVersionViews(final GuideVersionRepository versionRepository,
                             final GuideChapterRepository chapterRepository,
                             final GuideSectionRepository sectionRepository,
                             final GuideItemRepository itemRepository,
                             final GuideItemTypeRepository typeRepository,
                             final GuideMarkdown markdown) {
        this.versionRepository = versionRepository;
        this.chapterRepository = chapterRepository;
        this.sectionRepository = sectionRepository;
        this.itemRepository = itemRepository;
        this.typeRepository = typeRepository;
        this.markdown = markdown;
    }

    @Cacheable(cacheNames = CacheConfig.GUIDE_VERSIONS, unless = "#result == null || #result.draft()")
    @Transactional(readOnly = true)
    public @Nullable GuideVersionView find(final Long versionId) {
        return versionRepository.findById(versionId).map(this::load).orElse(null);
    }

    /**
     * Drops a version from the cache. Only needed when a draft id becomes a published version.
     */
    @CacheEvict(cacheNames = CacheConfig.GUIDE_VERSIONS)
    public void evict(final Long versionId) {
        // Eviction is the annotation's work
    }

    private GuideVersionView load(final GuideVersion version) {
        final List<GuideChapter> chapters = chapterRepository.findByVersionIdOrderByPosition(version.getId());
        final List<GuideSection> sections = sectionRepository.findByVersionIdOrderByChapterIdAscPositionAsc(version.getId());
        final List<GuideItem> items = itemRepository.findByVersionIdOrderByChapterIdAscPositionAsc(version.getId());
        final Map<Long, GuideChapter> chaptersById = chapters.stream().collect(Collectors.toMap(GuideChapter::getId, Function.identity()));
        final Map<Long, GuideSection> sectionsById = sections.stream().collect(Collectors.toMap(GuideSection::getId, Function.identity()));
        final Map<String, GuideItemKind> kinds = typeRepository.findAllByOrderByPositionAscIdAsc().stream()
                .collect(Collectors.toMap(GuideItemType::getId, GuideItemType::getKind));

        final List<GuideVersionView.Chapter> chapterViews = chapters.stream()
                .sorted(Comparator.comparing(GuideChapter::getPosition))
                .map(chapter -> new GuideVersionView.Chapter(
                        chapter.getId(),
                        chapter.getKey(),
                        GuideVersionView.Text.plain(chapter.getTitleSv(), chapter.getTitleEn()),
                        items.stream()
                                .filter(item -> item.getChapterId().equals(chapter.getId()) && item.getSectionId() == null)
                                .sorted(Comparator.comparing(GuideItem::getPosition))
                                .map(item -> toItem(item, kinds, chapter, null))
                                .toList(),
                        sections.stream()
                                .filter(section -> section.getChapterId().equals(chapter.getId()))
                                .sorted(Comparator.comparing(GuideSection::getPosition))
                                .map(section -> new GuideVersionView.Section(
                                        section.getId(),
                                        section.getKey(),
                                        chapter.getKey(),
                                        GuideVersionView.Text.plain(section.getTitleSv(), section.getTitleEn()),
                                        items.stream()
                                                .filter(item -> section.getId().equals(item.getSectionId()))
                                                .sorted(Comparator.comparing(GuideItem::getPosition))
                                                .map(item -> toItem(item, kinds, chaptersById.get(item.getChapterId()), sectionsById.get(item.getSectionId())))
                                                .toList(),
                                        versionOf(section.getVersion())))
                                .toList(),
                        versionOf(chapter.getVersion())))
                .toList();

        return new GuideVersionView(
                version.getId(),
                version.getStatus(),
                version.getLabel(),
                version.getNote(),
                version.getPublishedAt(),
                version.getPublishedBy(),
                version.getBasedOnId(),
                versionOf(version.getVersion()),
                text(version.getIntroSv(), version.getIntroEn()),
                chapterViews);
    }

    private GuideVersionView.Item toItem(final GuideItem item, final Map<String, GuideItemKind> kinds, final GuideChapter chapter,
                                         final @Nullable GuideSection section) {
        return new GuideVersionView.Item(
                item.getId(),
                item.getKey(),
                chapter.getKey(),
                section == null ? null : section.getKey(),
                item.getType(),
                kinds.get(item.getType()),
                text(item.getTextSv(), item.getTextEn()),
                versionOf(item.getVersion()));
    }

    private GuideVersionView.Text text(final String sv, final @Nullable String en) {
        return new GuideVersionView.Text(sv, en, markdown.plainText(sv), en == null ? null : markdown.plainText(en));
    }

    private static Long versionOf(final @Nullable Long version) {
        return version == null ? 0L : version;
    }
}
