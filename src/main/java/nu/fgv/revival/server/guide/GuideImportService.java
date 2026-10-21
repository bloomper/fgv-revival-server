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
import nu.fgv.revival.server.guide.GuideDocxParser.ParsedChapter;
import nu.fgv.revival.server.guide.GuideDocxParser.ParsedGuide;
import nu.fgv.revival.server.guide.GuideDocxParser.ParsedItem;
import nu.fgv.revival.server.guide.GuideDocxParser.ParsedSection;
import nu.fgv.revival.server.guide.GuideImportReportDto.ChapterCounts;
import nu.fgv.revival.server.guide.GuideImportReportDto.Counts;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Imports a Word document as the first draft (FR-022–FR-027). Only while no version has been published and no draft
 * exists: later versions are edited in the application (clarification 2026-10-21).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Service
public class GuideImportService {

    static final int MAX_FILE_NAME = 255;

    private final GuideDocxParser parser;
    private final GuideVersionRepository versionRepository;
    private final GuideChapterRepository chapterRepository;
    private final GuideSectionRepository sectionRepository;
    private final GuideItemRepository itemRepository;
    private final GuideImportReportRepository reportRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;

    public GuideImportService(final GuideDocxParser parser,
                              final GuideVersionRepository versionRepository,
                              final GuideChapterRepository chapterRepository,
                              final GuideSectionRepository sectionRepository,
                              final GuideItemRepository itemRepository,
                              final GuideImportReportRepository reportRepository,
                              final CallerService callerService,
                              final AccessPolicy accessPolicy) {
        this.parser = parser;
        this.versionRepository = versionRepository;
        this.chapterRepository = chapterRepository;
        this.sectionRepository = sectionRepository;
        this.itemRepository = itemRepository;
        this.reportRepository = reportRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
    }

    public record ImportResult(Long draftId, GuideImportReportDto report) {
    }

    @Transactional
    public ImportResult importDocx(final @Nullable String fileName, final byte[] bytes) {
        final Caller caller = callerService.current();

        accessPolicy.requireManageGuide(caller);
        if (versionRepository.existsByStatus(GuideVersionStatus.PUBLISHED)) {
            throw new BadRequestException("error.guide.import.published");
        }
        if (versionRepository.existsByStatus(GuideVersionStatus.DRAFT)) {
            throw new ConflictException("error.guide.import.draftExists");
        }
        AuditContext.operation("guideImport");

        final ParsedGuide parsed = parser.parse(bytes);
        final GuideVersion draft = new GuideVersion();

        draft.setStatus(GuideVersionStatus.DRAFT);
        draft.setIntroSv(parsed.introduction());
        versionRepository.saveAndFlush(draft);
        for (final ParsedChapter parsedChapter : parsed.chapters()) {
            final GuideChapter chapter = new GuideChapter();

            chapter.setVersionId(draft.getId());
            chapter.setPhase(parsedChapter.phase());
            chapter.setTitleSv(parsedChapter.title().isBlank() ? parsedChapter.phase().name() : truncate(parsedChapter.title(), GuideRules.MAX_TITLE));
            chapterRepository.save(chapter);
            saveItems(draft.getId(), chapter.getId(), null, parsedChapter.items());

            int position = 0;

            for (final ParsedSection parsedSection : parsedChapter.sections()) {
                final GuideSection section = new GuideSection();

                section.setVersionId(draft.getId());
                section.setChapterId(chapter.getId());
                section.setKey(UUID.randomUUID().toString());
                section.setPosition(position++);
                section.setTitleSv(truncate(parsedSection.title(), GuideRules.MAX_TITLE));
                sectionRepository.save(section);
                saveItems(draft.getId(), chapter.getId(), section.getId(), parsedSection.items());
            }
        }

        final List<ChapterCounts> counts = parsed.chapters().stream()
                .map(chapter -> new ChapterCounts(chapter.phase(), counts(chapter)))
                .toList();
        final GuideImportReport report = new GuideImportReport();

        report.setVersionId(draft.getId());
        report.setFileName(fileName == null || fileName.isBlank() ? "guide.docx" : truncate(fileName, MAX_FILE_NAME));
        report.setCounts(counts);
        report.setWarnings(parsed.warnings());
        reportRepository.saveAndFlush(report);

        return new ImportResult(draft.getId(), toDto(report));
    }

    /**
     * The report kept with a draft, if the draft came from an import.
     */
    @Transactional(readOnly = true)
    public Optional<GuideImportReportDto> report(final Long versionId) {
        return reportRepository.findByVersionId(versionId).map(this::toDto);
    }

    private GuideImportReportDto toDto(final GuideImportReport report) {
        final Counts totals = report.getCounts().stream().map(ChapterCounts::counts).reduce(Counts.NONE, Counts::plus);

        return new GuideImportReportDto(report.getFileName(), totals, report.getCounts(), report.getWarnings(), report.getCreatedAt());
    }

    private void saveItems(final Long versionId, final Long chapterId, final @Nullable Long sectionId, final List<ParsedItem> items) {
        int position = 0;

        for (final ParsedItem parsedItem : items) {
            final GuideItem item = new GuideItem();

            item.setVersionId(versionId);
            item.setChapterId(chapterId);
            item.setSectionId(sectionId);
            item.setKey(UUID.randomUUID().toString());
            item.setPosition(position++);
            item.setType(parsedItem.type());
            item.setTextSv(truncate(parsedItem.text(), GuideRules.MAX_ITEM_TEXT));
            itemRepository.save(item);
        }
    }

    private static Counts counts(final ParsedChapter chapter) {
        final List<ParsedItem> items = new java.util.ArrayList<>(chapter.items());

        chapter.sections().forEach(section -> items.addAll(section.items()));
        return new Counts(
                chapter.sections().size(),
                count(items, GuideItemType.TIP),
                count(items, GuideItemType.REQUIREMENT),
                count(items, GuideItemType.QUESTION),
                count(items, GuideItemType.TEXT));
    }

    private static int count(final List<ParsedItem> items, final GuideItemType type) {
        return (int) items.stream().filter(item -> item.type() == type).count();
    }

    private static String truncate(final String text, final int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
