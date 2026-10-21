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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalGuideVersions;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static org.springframework.util.StringUtils.hasText;

/**
 * The draft: starting, editing, publishing and discarding it. Every editing method goes through {@link #requireDraft},
 * the single check that a published version never changes (research R3, SC-003).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Service
public class GuideDraftService {

    private final GuideVersionRepository versionRepository;
    private final GuideChapterRepository chapterRepository;
    private final GuideSectionRepository sectionRepository;
    private final GuideItemRepository itemRepository;
    private final GuideImportReportRepository reportRepository;
    private final GuideVersionViews views;
    private final RevivalGuideVersions revivalGuideVersions;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final List<DraftDiscardListener> discardListeners;

    public GuideDraftService(final GuideVersionRepository versionRepository,
                             final GuideChapterRepository chapterRepository,
                             final GuideSectionRepository sectionRepository,
                             final GuideItemRepository itemRepository,
                             final GuideImportReportRepository reportRepository,
                             final GuideVersionViews views,
                             final RevivalGuideVersions revivalGuideVersions,
                             final CallerService callerService,
                             final AccessPolicy accessPolicy,
                             @Lazy final List<DraftDiscardListener> discardListeners) {
        this.versionRepository = versionRepository;
        this.chapterRepository = chapterRepository;
        this.sectionRepository = sectionRepository;
        this.itemRepository = itemRepository;
        this.reportRepository = reportRepository;
        this.views = views;
        this.revivalGuideVersions = revivalGuideVersions;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.discardListeners = discardListeners;
    }

    /**
     * Told before a draft is deleted, so that what refers to it can let go (suggestions accepted into it reopen).
     */
    public interface DraftDiscardListener {

        void draftDiscarded(Long draftId);
    }

    /**
     * Refuses any change to a published version.
     */
    public static GuideVersion requireDraft(final GuideVersion version) {
        if (!version.isDraft()) {
            throw new BadRequestException("error.guide.published");
        }
        return version;
    }

    private Caller manager() {
        final Caller caller = callerService.current();

        accessPolicy.requireManageGuide(caller);
        return caller;
    }

    private GuideVersion version(final Long id) {
        return versionRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(GuideVersion.class, id));
    }

    /**
     * Starts the draft: a copy of the latest published version with the same keys (research R3), or ten empty chapters
     * before the first version is published (FR-012).
     */
    @Transactional
    public Long start() {
        manager();
        if (versionRepository.existsByStatus(GuideVersionStatus.DRAFT)) {
            throw new ConflictException("error.guide.draftExists");
        }
        // Keeps the operation of a caller that starts the draft as part of its own, e.g. accepting a suggestion
        if (AuditContext.current() == null || AuditContext.current().operation() == null) {
            AuditContext.operation("guideDraftStart");
        }

        final GuideVersion draft = new GuideVersion();
        final GuideVersion latest = versionRepository.findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED).orElse(null);

        draft.setStatus(GuideVersionStatus.DRAFT);
        if (latest == null) {
            versionRepository.saveAndFlush(draft);
            for (final Phase phase : Phase.values()) {
                final GuideChapter chapter = new GuideChapter();

                chapter.setVersionId(draft.getId());
                chapter.setPhase(phase);
                chapter.setTitleSv(phase.name());
                chapterRepository.save(chapter);
            }
            return draft.getId();
        }

        draft.setBasedOnId(latest.getId());
        draft.setIntroSv(latest.getIntroSv());
        draft.setIntroEn(latest.getIntroEn());
        versionRepository.saveAndFlush(draft);
        copyContent(latest.getId(), draft.getId());
        return draft.getId();
    }

    private void copyContent(final Long fromVersionId, final Long toVersionId) {
        final java.util.Map<Long, Long> chapterIds = new java.util.HashMap<>();
        final java.util.Map<Long, Long> sectionIds = new java.util.HashMap<>();

        for (final GuideChapter source : chapterRepository.findByVersionIdOrderByPhase(fromVersionId)) {
            final GuideChapter copy = new GuideChapter();

            copy.setVersionId(toVersionId);
            copy.setPhase(source.getPhase());
            copy.setTitleSv(source.getTitleSv());
            copy.setTitleEn(source.getTitleEn());
            chapterRepository.save(copy);
            chapterIds.put(source.getId(), copy.getId());
        }
        for (final GuideSection source : sectionRepository.findByVersionIdOrderByChapterIdAscPositionAsc(fromVersionId)) {
            final GuideSection copy = new GuideSection();

            copy.setVersionId(toVersionId);
            copy.setChapterId(chapterIds.get(source.getChapterId()));
            copy.setKey(source.getKey());
            copy.setPosition(source.getPosition());
            copy.setTitleSv(source.getTitleSv());
            copy.setTitleEn(source.getTitleEn());
            sectionRepository.save(copy);
            sectionIds.put(source.getId(), copy.getId());
        }
        for (final GuideItem source : itemRepository.findByVersionIdOrderByChapterIdAscPositionAsc(fromVersionId)) {
            final GuideItem copy = new GuideItem();

            copy.setVersionId(toVersionId);
            copy.setChapterId(chapterIds.get(source.getChapterId()));
            copy.setSectionId(source.getSectionId() == null ? null : sectionIds.get(source.getSectionId()));
            copy.setKey(source.getKey());
            copy.setPosition(source.getPosition());
            copy.setType(source.getType());
            copy.setTextSv(source.getTextSv());
            copy.setTextEn(source.getTextEn());
            itemRepository.save(copy);
        }
        itemRepository.flush();
    }

    // --- Editing the draft (FR-013) -----------------------------------------------------------------------------------

    private GuideVersion draftOf(final Long versionId) {
        return requireDraft(version(versionId));
    }

    private GuideChapter chapter(final Long id) {
        return chapterRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(GuideChapter.class, id));
    }

    private GuideSection section(final Long id) {
        return sectionRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(GuideSection.class, id));
    }

    private GuideItem item(final Long id) {
        return itemRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(GuideItem.class, id));
    }

    private static void requireVersion(final nu.fgv.revival.server.util.AbstractAuditable entity, final @Nullable Long expected) {
        if (!Objects.equals(entity.getVersion(), expected)) {
            throw new ConflictException("error.conflict");
        }
    }

    private static int insertAt(final @Nullable Integer position, final int size) {
        if (position == null) {
            return size;
        }
        if (position < 0 || position > size) {
            throw new BadRequestException("error.guide.position");
        }
        return position;
    }

    private void renumberSections(final List<GuideSection> sections) {
        for (int position = 0; position < sections.size(); position++) {
            final GuideSection section = sections.get(position);

            if (!Objects.equals(section.getPosition(), position)) {
                section.setPosition(position);
                sectionRepository.save(section);
            }
        }
    }

    private void renumberItems(final List<GuideItem> items) {
        for (int position = 0; position < items.size(); position++) {
            final GuideItem item = items.get(position);

            if (!Objects.equals(item.getPosition(), position)) {
                item.setPosition(position);
                itemRepository.save(item);
            }
        }
    }

    private List<GuideItem> itemsIn(final Long chapterId, final @Nullable Long sectionId) {
        return new java.util.ArrayList<>(sectionId == null
                ? itemRepository.findByChapterIdAndSectionIdIsNullOrderByPosition(chapterId)
                : itemRepository.findByChapterIdAndSectionIdOrderByPosition(chapterId, sectionId));
    }

    /**
     * A chapter of the draft, and a section of that chapter when one is given.
     */
    private @Nullable GuideSection requirePlace(final GuideChapter chapter, final @Nullable Long sectionId) {
        if (sectionId == null) {
            return null;
        }

        final GuideSection section = section(sectionId);

        if (!section.getChapterId().equals(chapter.getId())) {
            throw new BadRequestException("error.guide.section.otherChapter");
        }
        return section;
    }

    @Transactional
    public Long updateIntroduction(final Long versionId, final Long expectedVersion, final @Nullable String sv, final @Nullable String en) {
        manager();

        final GuideVersion draft = draftOf(versionId);

        requireVersion(draft, expectedVersion);
        AuditContext.operation("guideIntroductionUpdate");
        draft.setIntroSv(GuideRules.introductionSv(sv));
        draft.setIntroEn(GuideRules.introductionEn(en));
        versionRepository.saveAndFlush(draft);
        return draft.getId();
    }

    /**
     * Renames a chapter. Chapters are never added, removed or reordered (FR-008).
     */
    @Transactional
    public Long updateChapter(final Long id, final Long expectedVersion, final @Nullable String sv, final @Nullable String en) {
        manager();

        final GuideChapter chapter = chapter(id);

        draftOf(chapter.getVersionId());
        requireVersion(chapter, expectedVersion);
        AuditContext.operation("guideChapterUpdate");
        chapter.setTitleSv(GuideRules.titleSv(sv));
        chapter.setTitleEn(GuideRules.titleEn(en));
        chapterRepository.saveAndFlush(chapter);
        return chapter.getVersionId();
    }

    @Transactional
    public Long createSection(final Long chapterId, final @Nullable Integer position, final @Nullable String sv, final @Nullable String en) {
        manager();

        final GuideChapter chapter = chapter(chapterId);

        draftOf(chapter.getVersionId());
        AuditContext.operation("guideSectionCreate");

        final List<GuideSection> sections = new java.util.ArrayList<>(sectionRepository.findByChapterIdOrderByPosition(chapterId));
        final GuideSection section = new GuideSection();

        section.setVersionId(chapter.getVersionId());
        section.setChapterId(chapterId);
        section.setKey(java.util.UUID.randomUUID().toString());
        section.setPosition(sections.size());
        section.setTitleSv(GuideRules.titleSv(sv));
        section.setTitleEn(GuideRules.titleEn(en));
        sections.add(insertAt(position, sections.size()), section);
        sectionRepository.save(section);
        renumberSections(sections);
        sectionRepository.flush();
        return section.getId();
    }

    @Transactional
    public Long updateSection(final Long id, final Long expectedVersion, final @Nullable String sv, final @Nullable String en) {
        manager();

        final GuideSection section = section(id);

        draftOf(section.getVersionId());
        requireVersion(section, expectedVersion);
        AuditContext.operation("guideSectionUpdate");
        section.setTitleSv(GuideRules.titleSv(sv));
        section.setTitleEn(GuideRules.titleEn(en));
        sectionRepository.saveAndFlush(section);
        return section.getId();
    }

    /**
     * Moves a section, with its items, within its chapter or to another chapter of the draft.
     */
    @Transactional
    public Long moveSection(final Long id, final Long expectedVersion, final Long chapterId, final int position) {
        manager();

        final GuideSection section = section(id);
        final GuideChapter target = chapter(chapterId);

        draftOf(section.getVersionId());
        if (!target.getVersionId().equals(section.getVersionId())) {
            throw new BadRequestException("error.guide.section.otherChapter");
        }
        requireVersion(section, expectedVersion);
        AuditContext.operation("guideSectionMove");

        final Long fromChapterId = section.getChapterId();
        final List<GuideSection> source = new java.util.ArrayList<>(sectionRepository.findByChapterIdOrderByPosition(fromChapterId));

        source.removeIf(candidate -> candidate.getId().equals(id));
        if (!fromChapterId.equals(chapterId)) {
            renumberSections(source);

            final List<GuideSection> destination = new java.util.ArrayList<>(sectionRepository.findByChapterIdOrderByPosition(chapterId));

            destination.add(insertAt(position, destination.size()), section);
            section.setChapterId(chapterId);
            renumberSections(destination);
            sectionRepository.save(section);
            for (final GuideItem item : itemRepository.findByChapterIdAndSectionIdOrderByPosition(fromChapterId, id)) {
                item.setChapterId(chapterId);
                itemRepository.save(item);
            }
        } else {
            source.add(insertAt(position, source.size()), section);
            renumberSections(source);
        }
        sectionRepository.flush();
        itemRepository.flush();
        return chapterId;
    }

    /**
     * Removes an empty section (edge case: a section with items is refused).
     */
    @Transactional
    public Long removeSection(final Long id, final Long expectedVersion) {
        manager();

        final GuideSection section = section(id);

        draftOf(section.getVersionId());
        requireVersion(section, expectedVersion);
        if (itemRepository.existsBySectionId(id)) {
            throw new BadRequestException("error.guide.section.notEmpty");
        }
        AuditContext.operation("guideSectionRemove");
        sectionRepository.delete(section);
        sectionRepository.flush();

        final List<GuideSection> remaining = new java.util.ArrayList<>(sectionRepository.findByChapterIdOrderByPosition(section.getChapterId()));

        renumberSections(remaining);
        sectionRepository.flush();
        return section.getVersionId();
    }

    @Transactional
    public Long createItem(final Long chapterId, final @Nullable Long sectionId, final @Nullable Integer position,
                           final GuideItemType type, final @Nullable String sv, final @Nullable String en) {
        manager();

        final GuideChapter chapter = chapter(chapterId);

        draftOf(chapter.getVersionId());
        requirePlace(chapter, sectionId);
        AuditContext.operation("guideItemCreate");
        return addItem(chapter, sectionId, position, type, GuideRules.itemTextSv(sv), GuideRules.itemTextEn(en)).getId();
    }

    /**
     * Adds an item to the draft. No caller check: for {@link #createItem} and for accepted suggestions, whose callers
     * have been checked.
     */
    public GuideItem addItem(final GuideChapter chapter, final @Nullable Long sectionId, final @Nullable Integer position,
                      final GuideItemType type, final String sv, final @Nullable String en) {
        final List<GuideItem> items = itemsIn(chapter.getId(), sectionId);
        final GuideItem item = new GuideItem();

        item.setVersionId(chapter.getVersionId());
        item.setChapterId(chapter.getId());
        item.setSectionId(sectionId);
        item.setKey(java.util.UUID.randomUUID().toString());
        item.setPosition(items.size());
        item.setType(type);
        item.setTextSv(sv);
        item.setTextEn(en);
        items.add(insertAt(position, items.size()), item);
        itemRepository.save(item);
        renumberItems(items);
        itemRepository.flush();
        return item;
    }

    @Transactional
    public Long updateItem(final Long id, final Long expectedVersion, final GuideItemType type, final @Nullable String sv, final @Nullable String en) {
        manager();

        final GuideItem item = item(id);

        draftOf(item.getVersionId());
        requireVersion(item, expectedVersion);
        AuditContext.operation("guideItemUpdate");
        item.setType(type);
        item.setTextSv(GuideRules.itemTextSv(sv));
        item.setTextEn(GuideRules.itemTextEn(en));
        itemRepository.saveAndFlush(item);
        return item.getId();
    }

    /**
     * Moves an item within its section, to another section, or to another chapter. Its key stays (FR-011).
     */
    @Transactional
    public Long moveItem(final Long id, final Long expectedVersion, final Long chapterId, final @Nullable Long sectionId, final int position) {
        manager();

        final GuideItem item = item(id);
        final GuideChapter target = chapter(chapterId);

        draftOf(item.getVersionId());
        if (!target.getVersionId().equals(item.getVersionId())) {
            throw new BadRequestException("error.guide.section.otherChapter");
        }
        requirePlace(target, sectionId);
        requireVersion(item, expectedVersion);
        AuditContext.operation("guideItemMove");

        final boolean samePlace = item.getChapterId().equals(chapterId) && Objects.equals(item.getSectionId(), sectionId);
        final List<GuideItem> source = itemsIn(item.getChapterId(), item.getSectionId());

        source.removeIf(candidate -> candidate.getId().equals(id));
        if (samePlace) {
            source.add(insertAt(position, source.size()), item);
            renumberItems(source);
        } else {
            renumberItems(source);

            final List<GuideItem> destination = itemsIn(chapterId, sectionId);

            destination.add(insertAt(position, destination.size()), item);
            item.setChapterId(chapterId);
            item.setSectionId(sectionId);
            itemRepository.save(item);
            renumberItems(destination);
        }
        itemRepository.flush();
        return chapterId;
    }

    @Transactional
    public Long removeItem(final Long id, final Long expectedVersion) {
        manager();

        final GuideItem item = item(id);

        draftOf(item.getVersionId());
        requireVersion(item, expectedVersion);
        AuditContext.operation("guideItemRemove");
        removeItem(item);
        return item.getVersionId();
    }

    /**
     * Changes an item of the draft. No caller check: for accepted suggestions, whose callers have been checked.
     */
    public void changeItem(final GuideItem item, final GuideItemType type, final @Nullable String sv, final @Nullable String en) {
        requireDraft(version(item.getVersionId()));
        item.setType(type);
        item.setTextSv(GuideRules.itemTextSv(sv));
        item.setTextEn(GuideRules.itemTextEn(en));
        itemRepository.saveAndFlush(item);
    }

    /**
     * Removes an item from the draft. No caller check: for {@link #removeItem(Long, Long)} and accepted suggestions,
     * whose callers have been checked.
     */
    public void removeItem(final GuideItem item) {
        itemRepository.delete(item);
        itemRepository.flush();
        renumberItems(itemsIn(item.getChapterId(), item.getSectionId()));
        itemRepository.flush();
    }

    /**
     * Publishes the draft under a label unique among all versions (FR-014), pins every revival without a version to it
     * (FR-018) and drops the import report (FR-027). The draft's id becomes the version's id.
     */
    @Transactional
    public GuideVersionView publish(final Long id, final Long expectedVersion, final @Nullable String label, final @Nullable String note) {
        final Caller caller = manager();
        final GuideVersion draft = requireDraft(version(id));

        requireVersion(draft, expectedVersion);
        AuditContext.operation("guideDraftPublish");

        final String validLabel = validLabel(label);

        requireComplete(draft);
        draft.setStatus(GuideVersionStatus.PUBLISHED);
        draft.setLabel(validLabel);
        draft.setNote(validNote(note));
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(caller.person().getId());
        versionRepository.saveAndFlush(draft);
        revivalGuideVersions.pinUnpinned(draft, caller);
        reportRepository.deleteByVersionId(draft.getId());
        views.evict(draft.getId());
        return Objects.requireNonNull(views.find(draft.getId()));
    }

    /**
     * Deletes the draft and everything in it. A draft is not revival data, so it may be deleted (constitution IV); Envers
     * keeps the record of it.
     */
    @Transactional
    public void discard(final Long id, final Long expectedVersion) {
        manager();

        final GuideVersion draft = requireDraft(version(id));

        requireVersion(draft, expectedVersion);
        AuditContext.operation("guideDraftDiscard");
        discardListeners.forEach(listener -> listener.draftDiscarded(draft.getId()));
        deleteContent(draft.getId());
        versionRepository.delete(draft);
        versionRepository.flush();
        views.evict(draft.getId());
    }

    private void deleteContent(final Long versionId) {
        itemRepository.deleteByVersionId(versionId);
        itemRepository.flush();
        sectionRepository.deleteByVersionId(versionId);
        sectionRepository.flush();
        chapterRepository.deleteByVersionId(versionId);
        chapterRepository.flush();
        reportRepository.deleteByVersionId(versionId);
        reportRepository.flush();
    }

    private String validLabel(final @Nullable String label) {
        if (!hasText(label)) {
            throw new BadRequestException("error.guide.label.blank");
        }

        final String trimmed = label.strip();

        if (trimmed.length() > GuideRules.MAX_LABEL) {
            throw new BadRequestException("error.guide.label.tooLong", GuideRules.MAX_LABEL);
        }
        if (versionRepository.existsByLabelIgnoreCase(trimmed)) {
            throw new BadRequestException("error.guide.label.duplicate", trimmed);
        }
        return trimmed;
    }

    private static @Nullable String validNote(final @Nullable String note) {
        if (!hasText(note)) {
            return null;
        }
        if (note.strip().length() > GuideRules.MAX_NOTE) {
            throw new BadRequestException("error.guide.note.tooLong", GuideRules.MAX_NOTE);
        }
        return note.strip();
    }

    private void requireComplete(final GuideVersion draft) {
        final List<GuideChapter> chapters = chapterRepository.findByVersionIdOrderByPhase(draft.getId());
        final boolean blank = chapters.size() != Phase.values().length
                || chapters.stream().anyMatch(chapter -> !hasText(chapter.getTitleSv()))
                || sectionRepository.findByVersionIdOrderByChapterIdAscPositionAsc(draft.getId()).stream().anyMatch(section -> !hasText(section.getTitleSv()))
                || itemRepository.findByVersionIdOrderByChapterIdAscPositionAsc(draft.getId()).stream().anyMatch(item -> !hasText(item.getTextSv()));

        if (blank) {
            throw new BadRequestException("error.guide.incomplete");
        }
    }
}
