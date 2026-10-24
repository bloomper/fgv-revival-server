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

package nu.fgv.revival.server.guide.suggestion;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.guide.DraftSuggestions;
import nu.fgv.revival.server.guide.GuideChanges;
import nu.fgv.revival.server.guide.GuideChapter;
import nu.fgv.revival.server.guide.GuideChapterRepository;
import nu.fgv.revival.server.guide.GuideDraftService;
import nu.fgv.revival.server.guide.GuideItem;
import nu.fgv.revival.server.guide.GuideItemRepository;
import nu.fgv.revival.server.guide.GuideRules;
import nu.fgv.revival.server.guide.GuideSection;
import nu.fgv.revival.server.guide.GuideSectionRepository;
import nu.fgv.revival.server.guide.GuideVersion;
import nu.fgv.revival.server.guide.GuideVersionRepository;
import nu.fgv.revival.server.guide.GuideVersionStatus;
import nu.fgv.revival.server.guide.GuideVersionSummaryDto;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.GuideVersionViews;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static nu.fgv.revival.server.guide.GuideMapper.GUIDE_MAPPER;
import static org.springframework.util.StringUtils.hasText;

/**
 * Suggestions from running revivals and the super admin's decisions (FR-031–FR-035, research R11). Accepting applies the
 * change to the draft through {@link GuideDraftService}, so the draft's own checks apply. Discarding a draft reopens what
 * was accepted into it.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Service
public class GuideSuggestionService implements DraftSuggestions, GuideDraftService.DraftDiscardListener {

    static final int MAX_REASON = 2_000;

    private final GuideSuggestionRepository repository;
    private final RevivalRepository revivalRepository;
    private final GuideVersionRepository versionRepository;
    private final GuideChapterRepository chapterRepository;
    private final GuideSectionRepository sectionRepository;
    private final GuideItemRepository itemRepository;
    private final GuideVersionViews views;
    private final GuideDraftService draftService;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;

    public GuideSuggestionService(final GuideSuggestionRepository repository,
                                  final RevivalRepository revivalRepository,
                                  final GuideVersionRepository versionRepository,
                                  final GuideChapterRepository chapterRepository,
                                  final GuideSectionRepository sectionRepository,
                                  final GuideItemRepository itemRepository,
                                  final GuideVersionViews views,
                                  final GuideDraftService draftService,
                                  final CallerService callerService,
                                  final AccessPolicy accessPolicy) {
        this.repository = repository;
        this.revivalRepository = revivalRepository;
        this.versionRepository = versionRepository;
        this.chapterRepository = chapterRepository;
        this.sectionRepository = sectionRepository;
        this.itemRepository = itemRepository;
        this.views = views;
        this.draftService = draftService;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
    }

    private static void requireOpen(final GuideSuggestion suggestion, final Long expectedVersion) {
        if (!Objects.equals(suggestion.getVersion(), expectedVersion)) {
            throw new ConflictException("error.conflict");
        }
        if (suggestion.getStatus() != GuideSuggestionStatus.OPEN) {
            throw new BadRequestException("error.suggestion.notOpen");
        }
    }

    private static @Nullable String text(final @Nullable String value) {
        return hasText(value) ? value.strip() : null;
    }

    private Revival visibleRevival(final Caller caller, final Long revivalId) {
        return accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);
    }

    private GuideSuggestion suggestion(final Caller caller, final Long id) {
        final GuideSuggestion suggestion = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(GuideSuggestion.class, id));
        final Revival revival = revivalRepository.findById(suggestion.getRevivalId()).orElseThrow();

        if (!accessPolicy.canSeeSuggestion(caller, revival)) {
            throw new ResourceNotFoundException(GuideSuggestion.class, id);
        }
        return suggestion;
    }

    /**
     * Records a suggestion on a published version, from a revival the author may suggest from (FR-031).
     */
    @Transactional
    public GuideSuggestionDto create(final Proposal proposal) {
        final Caller caller = callerService.current();
        final Revival revival = visibleRevival(caller, proposal.revivalId());

        accessPolicy.requireSuggest(caller, revival);

        final GuideVersion version = versionRepository.findById(proposal.versionId())
                .filter(candidate -> candidate.getStatus() == GuideVersionStatus.PUBLISHED)
                .orElseThrow(() -> new BadRequestException("error.guide.version.notPublished"));
        final GuideVersionView view = Objects.requireNonNull(views.find(version.getId()));
        final GuideSuggestion suggestion = new GuideSuggestion();
        final String sv = text(proposal.proposedTextSv());
        final String en = text(proposal.proposedTextEn());
        final String reason = text(proposal.reason());

        if (sv != null) {
            GuideRules.itemTextSv(sv);
        }
        if (en != null) {
            GuideRules.itemTextEn(en);
        }
        if (reason != null && reason.length() > MAX_REASON) {
            throw new BadRequestException("error.suggestion.reason.tooLong", MAX_REASON);
        }
        switch (proposal.kind()) {
            case CHANGE, REMOVE -> {
                final GuideVersionView.Item item = proposal.itemKey() == null ? null : view.item(proposal.itemKey()).orElse(null);

                if (item == null) {
                    throw new BadRequestException("error.suggestion.item.unknown");
                }
                if (reason == null) {
                    throw new BadRequestException("error.suggestion.reason.required");
                }
                if (proposal.kind() == GuideSuggestionKind.CHANGE && proposal.proposedType() == null && sv == null && en == null) {
                    throw new BadRequestException("error.suggestion.content.required");
                }
                suggestion.setItemKey(item.key());
                suggestion.setChapterKey(item.chapterKey());
                if (proposal.kind() == GuideSuggestionKind.CHANGE) {
                    if (proposal.proposedType() != null) {
                        draftService.requireItemType(proposal.proposedType(), List.of(item.type()));
                    }
                    suggestion.setProposedType(proposal.proposedType());
                    suggestion.setProposedTextSv(sv);
                    suggestion.setProposedTextEn(en);
                }
            }
            case ADD -> {
                if (proposal.chapterKey() == null) {
                    throw new BadRequestException("error.suggestion.chapter.required");
                }

                final GuideVersionView.Chapter chapter = view.chapter(proposal.chapterKey())
                        .orElseThrow(() -> new BadRequestException("error.suggestion.chapter.unknown"));

                if (proposal.proposedType() == null) {
                    throw new BadRequestException("error.suggestion.type.required");
                }
                draftService.requireItemType(proposal.proposedType(), List.of());
                if (sv == null && en == null) {
                    throw new BadRequestException("error.guide.text.blank");
                }
                if (proposal.sectionKey() != null && chapter.sections().stream().noneMatch(section -> section.key().equals(proposal.sectionKey()))) {
                    throw new BadRequestException("error.suggestion.section.unknown");
                }
                suggestion.setChapterKey(chapter.key());
                suggestion.setSectionKey(proposal.sectionKey());
                suggestion.setProposedType(proposal.proposedType());
                suggestion.setProposedTextSv(sv);
                suggestion.setProposedTextEn(en);
            }
        }
        AuditContext.operation("guideSuggestionCreate");
        suggestion.setRevivalId(revival.getId());
        suggestion.setAuthorId(caller.person().getId());
        suggestion.setVersionId(version.getId());
        suggestion.setKind(proposal.kind());
        suggestion.setReason(reason);
        return toDto(caller, repository.saveAndFlush(suggestion), revival);
    }

    /**
     * Applies an open suggestion to the draft, starting one from the latest version when there is none (FR-033).
     */
    @Transactional
    public GuideSuggestionDto accept(final Long id, final Long expectedVersion, final @Nullable String note, final @Nullable Resolution resolution) {
        final Caller caller = callerService.current();

        accessPolicy.requireDecideSuggestion(caller);

        final GuideSuggestion suggestion = suggestion(caller, id);

        requireOpen(suggestion, expectedVersion);
        if (resolution != null && resolution.type() != null) {
            draftService.requireItemType(resolution.type(), held(suggestion));
        }
        AuditContext.operation("guideSuggestionAccept");

        final Long draftId = versionRepository.findFirstByStatus(GuideVersionStatus.DRAFT).map(GuideVersion::getId).orElseGet(draftService::start);

        switch (suggestion.getKind()) {
            case CHANGE, REMOVE -> applyToItem(suggestion, draftId, resolution);
            case ADD -> {
                final GuideChapter chapter = draftChapter(draftId, suggestion.getChapterKey());
                final Long sectionId = suggestion.getSectionKey() == null ? null : sectionRepository.findByVersionIdAndKey(draftId, suggestion.getSectionKey())
                        .filter(section -> section.getChapterId().equals(chapter.getId()))
                        .map(GuideSection::getId)
                        .orElse(null);
                final String type = resolution != null && resolution.type() != null ? resolution.type() : Objects.requireNonNull(suggestion.getProposedType());
                final String sv = resolution != null && resolution.sv() != null ? resolution.sv()
                        : suggestion.getProposedTextSv() != null ? suggestion.getProposedTextSv() : suggestion.getProposedTextEn();
                final String en = resolution != null && resolution.sv() != null ? resolution.en() : suggestion.getProposedTextEn();

                draftService.addItem(chapter, sectionId, null, type, GuideRules.itemTextSv(sv), GuideRules.itemTextEn(en));
            }
        }
        suggestion.setStatus(GuideSuggestionStatus.ACCEPTED);
        suggestion.setDecidedBy(caller.person().getId());
        suggestion.setDecidedAt(Instant.now());
        suggestion.setDecisionReason(text(note));
        suggestion.setAcceptedIntoVersionId(draftId);
        suggestion.setReopenedAt(null);
        return toDto(caller, repository.saveAndFlush(suggestion), null);
    }

    /**
     * The item types a suggestion's resolution may keep although they are retired: the proposed one and the item's.
     */
    private List<String> held(final GuideSuggestion suggestion) {
        final List<String> held = new ArrayList<>();

        if (suggestion.getProposedType() != null) {
            held.add(suggestion.getProposedType());
        }
        if (suggestion.getItemKey() != null) {
            Objects.requireNonNull(views.find(suggestion.getVersionId())).item(suggestion.getItemKey()).ifPresent(item -> held.add(item.type()));
        }
        return held;
    }

    private void applyToItem(final GuideSuggestion suggestion, final Long draftId, final @Nullable Resolution resolution) {
        final GuideVersionView madeOn = Objects.requireNonNull(views.find(suggestion.getVersionId()));
        final GuideVersionView.Item original = madeOn.item(Objects.requireNonNull(suggestion.getItemKey())).orElseThrow();
        final GuideItem current = itemRepository.findByVersionIdAndKey(draftId, suggestion.getItemKey()).orElse(null);
        final boolean changed = current == null
                || !current.getType().equals(original.type())
                || !Objects.equals(current.getTextSv(), original.text().sv())
                || !Objects.equals(current.getTextEn(), original.text().en());

        if (changed && resolution == null) {
            throw new ConflictException("error.suggestion.conflict");
        }
        if (suggestion.getKind() == GuideSuggestionKind.REMOVE) {
            if (current != null && (resolution == null || !resolution.keep())) {
                draftService.removeItem(current);
            }
            return;
        }

        final String type = resolution != null && resolution.type() != null ? resolution.type()
                : suggestion.getProposedType() != null ? suggestion.getProposedType() : original.type();
        final String sv = resolution != null && resolution.sv() != null ? resolution.sv()
                : suggestion.getProposedTextSv() != null ? suggestion.getProposedTextSv() : current != null ? current.getTextSv() : original.text().sv();
        final String en = resolution != null && resolution.sv() != null ? resolution.en()
                : suggestion.getProposedTextEn() != null ? suggestion.getProposedTextEn() : current != null ? current.getTextEn() : original.text().en();

        if (resolution != null && resolution.keep()) {
            return;
        }
        if (current != null) {
            draftService.changeItem(current, type, sv, en);
        } else {
            final GuideChapter chapter = draftChapter(draftId, original.chapterKey());

            draftService.addItem(chapter, null, null, type, GuideRules.itemTextSv(sv), GuideRules.itemTextEn(en));
        }
    }

    /**
     * The draft's chapter with the key. CONFLICT when the draft no longer has it: the super admin removed it after the
     * suggestion was made.
     */
    private GuideChapter draftChapter(final Long draftId, final String chapterKey) {
        return chapterRepository.findByVersionIdAndKey(draftId, chapterKey).orElseThrow(() -> new ConflictException("error.suggestion.chapter.removed"));
    }

    @Transactional
    public GuideSuggestionDto reject(final Long id, final Long expectedVersion, final @Nullable String reason) {
        final Caller caller = callerService.current();

        accessPolicy.requireDecideSuggestion(caller);

        final GuideSuggestion suggestion = suggestion(caller, id);

        requireOpen(suggestion, expectedVersion);
        AuditContext.operation("guideSuggestionReject");
        suggestion.setStatus(GuideSuggestionStatus.REJECTED);
        suggestion.setDecidedBy(caller.person().getId());
        suggestion.setDecidedAt(Instant.now());
        suggestion.setDecisionReason(text(reason));
        return toDto(caller, repository.saveAndFlush(suggestion), null);
    }

    /**
     * Only the author withdraws, while they may still suggest from the revival (FR-034).
     */
    @Transactional
    public GuideSuggestionDto withdraw(final Long id, final Long expectedVersion) {
        final Caller caller = callerService.current();
        final GuideSuggestion suggestion = suggestion(caller, id);
        final Revival revival = revivalRepository.findById(suggestion.getRevivalId()).orElseThrow();

        accessPolicy.requireWithdrawSuggestion(caller, revival, suggestion.getAuthorId());
        requireOpen(suggestion, expectedVersion);
        AuditContext.operation("guideSuggestionWithdraw");
        suggestion.setStatus(GuideSuggestionStatus.WITHDRAWN);
        return toDto(caller, repository.saveAndFlush(suggestion), revival);
    }

    /**
     * The suggestions the caller may see (FR-035), newest first.
     */
    @Transactional(readOnly = true)
    public List<GuideSuggestionDto> findVisible(final @Nullable Collection<GuideSuggestionStatus> statuses, final @Nullable Long revivalId) {
        final Caller caller = callerService.current();
        final List<Revival> revivals = caller.isSuperAdmin() || caller.isObserver()
                ? revivalRepository.findAll()
                : revivalRepository.findAll(accessPolicy.visibilityPredicate(caller)).stream()
                .filter(revival -> accessPolicy.canSuggest(caller, revival))
                .toList();
        final Map<Long, Revival> byId = revivals.stream()
                .filter(revival -> revivalId == null || revival.getId().equals(revivalId))
                .collect(Collectors.toMap(Revival::getId, Function.identity()));

        if (byId.isEmpty()) {
            return List.of();
        }
        return repository.findAll((root, query, cb) -> {
                    final var predicate = root.get("revivalId").in(byId.keySet());

                    return statuses == null || statuses.isEmpty() ? predicate : cb.and(predicate, root.get("status").in(statuses));
                }, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))).stream()
                .map(suggestion -> toDto(caller, suggestion, byId.get(suggestion.getRevivalId())))
                .toList();
    }

    @Override
    public int acceptedInto(final Long draftId) {
        return repository.countByAcceptedIntoVersionId(draftId);
    }

    @Override
    public int madeOn(final Long versionId) {
        return repository.countByVersionId(versionId);
    }

    /**
     * The draft a suggestion was accepted into is being discarded: the suggestion opens again (clarification
     * 2026-10-21).
     */
    @Override
    public void draftDiscarded(final Long draftId) {
        for (final GuideSuggestion suggestion : repository.findByAcceptedIntoVersionId(draftId)) {
            suggestion.setStatus(GuideSuggestionStatus.OPEN);
            suggestion.setAcceptedIntoVersionId(null);
            suggestion.setDecidedBy(null);
            suggestion.setDecidedAt(null);
            suggestion.setDecisionReason(null);
            suggestion.setReopenedAt(Instant.now());
            repository.save(suggestion);
        }
        repository.flush();
    }

    private GuideSuggestionDto toDto(final Caller caller, final GuideSuggestion suggestion, final @Nullable Revival known) {
        final Revival revival = known != null ? known : revivalRepository.findById(suggestion.getRevivalId()).orElseThrow();
        final GuideVersionView madeOn = Objects.requireNonNull(views.find(suggestion.getVersionId()));
        final GuideChanges.ItemSnapshot item = suggestion.getItemKey() == null ? null : madeOn.item(suggestion.getItemKey())
                .map(found -> new GuideChanges.ItemSnapshot(new GuideChanges.ChapterRef(found.chapterKey(),
                        madeOn.chapter(found.chapterKey()).orElseThrow().title()), found.sectionKey(),
                        found.sectionKey() == null ? null : madeOn.section(found.sectionKey()).map(GuideVersionView.Section::title).orElse(null),
                        found.type(), found.text()))
                .orElse(null);
        final GuideVersionSummaryDto publishedIn = suggestion.getAcceptedIntoVersionId() == null ? null
                : versionRepository.findById(suggestion.getAcceptedIntoVersionId())
                .filter(version -> version.getStatus() == GuideVersionStatus.PUBLISHED)
                .map(GUIDE_MAPPER::toSummary)
                .orElse(null);
        final String sv = suggestion.getProposedTextSv();
        final String en = suggestion.getProposedTextEn();

        return new GuideSuggestionDto(
                suggestion.getId(),
                suggestion.getKind(),
                suggestion.getStatus(),
                new GuideSuggestionDto.Revival(revival.getId(), revival.getSpexId(), revival.getYear()),
                suggestion.getAuthorId(),
                suggestion.getAuthorId().equals(caller.person().getId()),
                GUIDE_MAPPER.toSummary(madeOn),
                suggestion.getChapterKey(),
                madeOn.chapter(suggestion.getChapterKey()).map(GuideVersionView.Chapter::title).orElse(null),
                suggestion.getSectionKey(),
                suggestion.getItemKey(),
                item,
                suggestion.getProposedType(),
                sv == null && en == null ? null : new GuideSuggestionDto.ProposedText(sv == null ? "" : sv, en),
                suggestion.getReason(),
                suggestion.getDecidedBy(),
                suggestion.getDecidedAt(),
                suggestion.getDecisionReason(),
                publishedIn,
                suggestion.getReopenedAt(),
                suggestion.getCreatedAt(),
                suggestion.getVersion() == null ? 0L : suggestion.getVersion());
    }

    public record Proposal(Long revivalId,
                           Long versionId,
                           GuideSuggestionKind kind,
                           @Nullable String itemKey,
                           @Nullable String chapterKey,
                           @Nullable String sectionKey,
                           @Nullable String proposedType,
                           @Nullable String proposedTextSv,
                           @Nullable String proposedTextEn,
                           @Nullable String reason) {
    }

    /**
     * What the draft gets when its item differs from the suggestion's version (FR-033).
     */
    public record Resolution(boolean keep, @Nullable String type, @Nullable String sv, @Nullable String en) {
    }
}
