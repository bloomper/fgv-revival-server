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

import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static nu.fgv.revival.server.guide.GuideMapper.GUIDE_MAPPER;

/**
 * Reading the guide: published versions, the draft for super admins, and a revival's pinned version. Every method asks
 * {@link AccessPolicy} first, through {@link GuideAccess} (constitution III). The guide never calls Spexregister
 * (FR-006).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Service
public class GuideService {

    private final GuideVersionRepository versionRepository;
    private final GuideVersionViews views;
    private final GuideImportService importService;
    private final RevivalRepository revivalRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final GuideAccess guideAccess;
    private final Optional<DraftSuggestions> draftSuggestions;
    private final GuidePdfService pdfService;

    public GuideService(final GuideVersionRepository versionRepository,
                        final GuideVersionViews views,
                        final GuideImportService importService,
                        final RevivalRepository revivalRepository,
                        final CallerService callerService,
                        final AccessPolicy accessPolicy,
                        final GuideAccess guideAccess,
                        final Optional<DraftSuggestions> draftSuggestions,
                        final GuidePdfService pdfService) {
        this.versionRepository = versionRepository;
        this.views = views;
        this.importService = importService;
        this.revivalRepository = revivalRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.guideAccess = guideAccess;
        this.draftSuggestions = draftSuggestions;
        this.pdfService = pdfService;
    }

    private GuideVersionView load(final Long id) {
        final GuideVersionView view = views.find(id);

        if (view == null) {
            throw new ResourceNotFoundException(GuideVersion.class, id);
        }
        return view;
    }

    /**
     * Published versions, newest first.
     */
    @Transactional(readOnly = true)
    public List<GuideVersionSummaryDto> findPublished() {
        guideAccess.requireRead(callerService.current());
        return versionRepository.findByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED).stream()
                .map(GUIDE_MAPPER::toSummary)
                .toList();
    }

    /**
     * The latest published version, or empty before the first is published.
     */
    @Transactional(readOnly = true)
    public Optional<GuideVersionView> findLatest() {
        guideAccess.requireRead(callerService.current());
        return versionRepository.findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED)
                .map(version -> load(version.getId()));
    }

    /**
     * A version the caller may read: any published version for readers, the draft for super admins. Anything else is
     * not found.
     */
    @Transactional(readOnly = true)
    public GuideVersionView findById(final Long id) {
        final Caller caller = callerService.current();
        final GuideVersionView view = load(id);

        guideAccess.requireReadable(caller, view);
        return view;
    }

    /**
     * Searches a version the caller may read, the latest published one when none is given (FR-021). GraphQL and
     * {@code search_guide} both call this, so they cannot disagree (SC-007).
     */
    @Transactional(readOnly = true)
    public SearchResult search(final @Nullable Long versionId, final @Nullable String query, final int first) {
        final GuideVersionView version = versionId == null
                ? findLatest().orElseThrow(() -> new ResourceNotFoundException(GuideVersion.class, "latest"))
                : findById(versionId);

        return new SearchResult(version, GuideSearch.search(version, query, first));
    }

    public record SearchResult(GuideVersionView version, List<GuideSearch.Hit> hits) {
    }

    public record Pdf(byte[] content, String fileName) {
    }

    /**
     * A version the caller may read, as a PDF (FR-028).
     */
    @Transactional(readOnly = true)
    public Pdf versionPdf(final Long versionId, final Locale locale) {
        final GuideVersionView version = findById(versionId);

        return new Pdf(pdfService.render(version, locale, null), fileName(version.draft() ? draftName(locale) : version.label(), locale));
    }

    /**
     * A revival's guide as a PDF, with the revival on the title page. Its working title is FGV Revival's own, so this
     * never calls Spexregister (FR-006).
     */
    @Transactional(readOnly = true)
    public Pdf revivalPdf(final Long revivalId, final Locale locale) {
        final Revival revival = visibleRevival(callerService.current(), revivalId);
        final GuideVersionView version = Optional.ofNullable(revival.getGuideVersionId()).map(this::load)
                .orElseThrow(() -> new ResourceNotFoundException("error.guide.notPublished"));

        return new Pdf(pdfService.render(version, locale, new GuidePdfService.RevivalTitle(revival.getWorkingTitle(), revival.getYear())),
                fileName(revival.getWorkingTitle(), locale));
    }

    private String draftName(final Locale locale) {
        return Locale.ENGLISH.getLanguage().equals(locale.getLanguage()) ? "draft" : "utkast";
    }

    /**
     * {@code nyuppsattningsguide-<name>-<lang>.pdf}, ASCII-folded and slugged (contracts/http.md).
     */
    static String fileName(final @Nullable String name, final Locale locale) {
        final String slug = GuideSearch.fold(name == null ? "" : name.toLowerCase(Locale.ROOT))
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");

        return "nyuppsattningsguide-%s%s.pdf".formatted(slug.isEmpty() ? "" : slug + "-", locale.getLanguage());
    }

    /**
     * What changed between two versions the caller may read (FR-016). Only super admins can compare with the draft.
     */
    @Transactional(readOnly = true)
    public GuideChanges.Result changes(final Long fromId, final Long toId) {
        return GuideChanges.compare(findById(fromId), findById(toId));
    }

    private GuideVersionView draftView() {
        accessPolicy.requireManageGuide(callerService.current());
        return versionRepository.findFirstByStatus(GuideVersionStatus.DRAFT)
                .map(version -> load(version.getId()))
                .orElseThrow(() -> new ResourceNotFoundException(GuideVersion.class, "draft"));
    }

    /**
     * A chapter of the draft, as an editing mutation returns it.
     */
    @Transactional(readOnly = true)
    public GuideVersionView.Chapter draftChapter(final Long id) {
        return draftView().chapters().stream()
                .filter(chapter -> chapter.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(GuideChapter.class, id));
    }

    @Transactional(readOnly = true)
    public GuideVersionView.Section draftSection(final Long id) {
        return draftView().chapters().stream()
                .flatMap(chapter -> chapter.sections().stream())
                .filter(section -> section.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(GuideSection.class, id));
    }

    @Transactional(readOnly = true)
    public GuideVersionView.Item draftItem(final Long id) {
        return draftView().items()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(GuideItem.class, id));
    }

    /**
     * The version a revival the caller can see is pinned to, or empty before the first version is published.
     */
    @Transactional(readOnly = true)
    public Optional<GuideVersionView> forRevival(final Long revivalId) {
        final Revival revival = visibleRevival(callerService.current(), revivalId);

        return Optional.ofNullable(revival.getGuideVersionId()).map(this::load);
    }

    /**
     * The working title and year of a revival the caller can see, for the PDF title page.
     */
    @Transactional(readOnly = true)
    public Revival visibleRevival(final Long revivalId) {
        return visibleRevival(callerService.current(), revivalId);
    }

    private Revival visibleRevival(final Caller caller, final Long revivalId) {
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        if (!accessPolicy.canReadRevivalGuide(caller, revival)) {
            throw new ResourceNotFoundException(Revival.class, revivalId);
        }
        return revival;
    }

    /**
     * The draft, for super admins, or null when there is none.
     */
    @Transactional(readOnly = true)
    public @Nullable GuideDraftDto draft() {
        accessPolicy.requireManageGuide(callerService.current());

        return versionRepository.findFirstByStatus(GuideVersionStatus.DRAFT)
                .map(version -> {
                    final GuideVersionView view = load(version.getId());

                    return new GuideDraftDto(
                            view,
                            importService.report(version.getId()).orElse(null),
                            draftSuggestions.map(suggestions -> suggestions.acceptedInto(version.getId())).orElse(0),
                            untranslated(view));
                })
                .orElse(null);
    }

    /**
     * A version's summary, for fields on objects the caller has already been allowed to see.
     */
    @Transactional(readOnly = true)
    public @Nullable GuideVersionSummaryDto summary(final @Nullable Long versionId) {
        return versionId == null ? null : versionRepository.findById(versionId).map(GUIDE_MAPPER::toSummary).orElse(null);
    }

    /**
     * The latest published version, unchecked, for fields on revivals the caller can see.
     */
    @Transactional(readOnly = true)
    public Optional<GuideVersionSummaryDto> latestSummary() {
        return versionRepository.findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED).map(GUIDE_MAPPER::toSummary);
    }

    static int untranslated(final GuideVersionView view) {
        final long introduction = !view.introduction().sv().isBlank() && !view.introduction().translated() ? 1 : 0;
        final long titles = view.chapters().stream()
                .flatMap(chapter -> Stream.concat(Stream.of(chapter.title()), chapter.sections().stream().map(GuideVersionView.Section::title)))
                .filter(title -> !title.translated())
                .count();
        final long items = view.items().filter(item -> !item.text().translated()).count();

        return (int) (introduction + titles + items);
    }
}
