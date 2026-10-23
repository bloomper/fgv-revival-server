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


package nu.fgv.revival.server.checklist.template;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.guide.GuideAccess;
import nu.fgv.revival.server.guide.GuideVersionPublished;
import nu.fgv.revival.server.guide.GuideVersionRepository;
import nu.fgv.revival.server.guide.GuideVersionStatus;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.GuideVersionViews;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.CapabilityService;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Checklist templates for guide chapters and event kinds (FR-030, FR-031). Only super admins change them; whoever reads the
 * guide reads them. Templates and their items are retired, never deleted, so checklists keep their links (FR-032a).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Service
public class ChecklistTemplateService {

    private static final Comparator<ChecklistTemplateItem> ITEM_ORDER = Comparator
            .comparingInt(ChecklistTemplateItem::getPosition)
            .thenComparing(ChecklistTemplateItem::getId);

    private final ChecklistTemplateRepository templateRepository;
    private final ChecklistTemplateItemRepository itemRepository;
    private final GuideVersionRepository versionRepository;
    private final GuideVersionViews guideVersionViews;
    private final GuideAccess guideAccess;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final CapabilityService capabilityService;
    private final Clock clock;

    public ChecklistTemplateService(final ChecklistTemplateRepository templateRepository,
                                    final ChecklistTemplateItemRepository itemRepository,
                                    final GuideVersionRepository versionRepository,
                                    final GuideVersionViews guideVersionViews,
                                    final GuideAccess guideAccess,
                                    final CallerService callerService,
                                    final AccessPolicy accessPolicy,
                                    final CapabilityService capabilityService,
                                    final Clock clock) {
        this.templateRepository = templateRepository;
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.guideVersionViews = guideVersionViews;
        this.guideAccess = guideAccess;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.capabilityService = capabilityService;
        this.clock = clock;
    }

    public record Texts(String sv, @Nullable String en) {
    }

    public record NewTemplate(ChecklistKind kind, @Nullable String chapterKey, @Nullable Texts name) {
    }

    public record NewItem(Long templateId, @Nullable Integer position, Texts text, @Nullable String guideItemKey,
                          @Nullable String defaultCapability, @Nullable RelativeDeadline deadline) {
    }

    /**
     * Fields left null are unchanged; the {@code clear*} flags remove a value.
     */
    public record ItemChange(Long id, Long version, @Nullable Texts text, @Nullable String guideItemKey, boolean clearGuideItem,
                             @Nullable String defaultCapability, boolean clearDefaultCapability, @Nullable RelativeDeadline deadline,
                             boolean clearDeadline) {
    }

    // --- Reading ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChecklistTemplateDto> list(final boolean includeRetired) {
        final Caller caller = readable();
        final List<ChecklistTemplate> templates = includeRetired && accessPolicy.canManageChecklistTemplates(caller)
                ? templateRepository.findAllByOrderByNameSvAscIdAsc()
                : templateRepository.findByRetiredAtIsNullOrderByNameSvAscIdAsc();
        final Map<Long, List<ChecklistTemplateItem>> items = itemRepository.findByTemplateIdIn(templates.stream().map(ChecklistTemplate::getId).toList())
                .stream()
                .filter(item -> !item.isRetired())
                .sorted(ITEM_ORDER)
                .collect(Collectors.groupingBy(ChecklistTemplateItem::getTemplateId));

        return templates.stream()
                .sorted(order())
                .map(template -> ChecklistTemplateDto.of(template, items.getOrDefault(template.getId(), List.of())))
                .toList();
    }

    /**
     * Chapter templates first, in the order of the latest published version's chapters, then event templates by name
     * (the repository's order). Templates for chapters the latest version lacks come last among the chapter templates.
     */
    private Comparator<ChecklistTemplate> order() {
        final List<GuideVersionView.Chapter> chapters = latest().map(GuideVersionView::chapters).orElse(List.of());
        final Map<String, Integer> positions = new HashMap<>();

        for (int index = 0; index < chapters.size(); index++) {
            positions.put(chapters.get(index).key(), index);
        }
        return Comparator
                .comparing((ChecklistTemplate template) -> template.getKind() == ChecklistKind.CHAPTER ? 0 : 1)
                .thenComparing(template -> template.getChapterKey() == null ? 0 : positions.getOrDefault(template.getChapterKey(), Integer.MAX_VALUE));
    }

    private Optional<GuideVersionView> latest() {
        return versionRepository.findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED)
                .map(version -> guideVersionViews.find(version.getId()));
    }

    /**
     * A chapter template's chapter title in the latest published version, or null when the version no longer has it.
     */
    @Transactional(readOnly = true)
    public GuideVersionView.@Nullable Text chapterTitle(final @Nullable String chapterKey) {
        return chapterKey == null ? null : latest().flatMap(view -> view.chapter(chapterKey)).map(GuideVersionView.Chapter::title).orElse(null);
    }

    /**
     * Retires the chapter templates for chapters the newly published version does not have, as part of publishing it.
     */
    @EventListener
    public void guideVersionPublished(final GuideVersionPublished event) {
        final GuideVersionView version = guideVersionViews.find(event.versionId());

        if (version == null) {
            return;
        }
        templateRepository.findByKindAndRetiredAtIsNullOrderByNameSvAscIdAsc(ChecklistKind.CHAPTER).stream()
                .filter(template -> template.getChapterKey() != null && version.chapter(template.getChapterKey()).isEmpty())
                .forEach(template -> {
                    template.setRetiredAt(clock.instant());
                    templateRepository.saveAndFlush(template);
                });
    }

    @Transactional(readOnly = true)
    public ChecklistTemplateDto get(final Long id) {
        readable();
        final ChecklistTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(ChecklistTemplate.class, id));

        return toDto(template);
    }

    /**
     * A template's retired items, the most recently retired first. Only super admins see them, as only they can restore
     * them.
     */
    @Transactional(readOnly = true)
    public List<ChecklistTemplateItemDto> retiredItems(final Long templateId) {
        if (!accessPolicy.canManageChecklistTemplates(callerService.current())) {
            return List.of();
        }
        return itemRepository.findByTemplateIdAndRetiredAtIsNotNullOrderByRetiredAtDescIdDesc(templateId).stream()
                .map(ChecklistTemplateItemDto::of)
                .toList();
    }

    /**
     * Template items' guide items in the latest published version, where the key is still there.
     */
    @Transactional(readOnly = true)
    public Map<ChecklistTemplateItemDto, GuideVersionView.Item> guideItems(final Collection<ChecklistTemplateItemDto> items) {
        final Optional<GuideVersionView> latest = latest();
        final Map<ChecklistTemplateItemDto, GuideVersionView.Item> found = new HashMap<>();

        for (final ChecklistTemplateItemDto item : items) {
            if (item.guideItemKey() != null) {
                latest.flatMap(view -> view.item(item.guideItemKey())).ifPresent(guideItem -> found.put(item, guideItem));
            }
        }
        return found;
    }

    // --- Templates --------------------------------------------------------------------------------------------------

    @Transactional
    public ChecklistTemplateDto create(final NewTemplate newTemplate) {
        manageable();

        final ChecklistTemplate template = new ChecklistTemplate();

        template.setKind(newTemplate.kind());
        if (newTemplate.kind() == ChecklistKind.CHAPTER) {
            final String chapterKey = newTemplate.chapterKey();

            if (chapterKey == null) {
                throw new BadRequestException("error.template.chapter.required");
            }
            if (latest().flatMap(view -> view.chapter(chapterKey)).isEmpty()) {
                throw new BadRequestException("error.template.chapter.unknown");
            }
            if (templateRepository.findByChapterKeyAndRetiredAtIsNull(chapterKey).isPresent()) {
                throw new ConflictException("error.template.chapter.exists");
            }
            template.setChapterKey(chapterKey);
        } else {
            setName(template, newTemplate.name());
        }
        AuditContext.operation("checklistTemplateCreate");
        return toDto(templateRepository.saveAndFlush(template));
    }

    @Transactional
    public ChecklistTemplateDto update(final Long id, final Long version, final @Nullable Texts name) {
        manageable();

        final ChecklistTemplate template = changeable(id, version);

        if (template.getKind() == ChecklistKind.EVENT && name != null) {
            AuditContext.operation("checklistTemplateUpdate");
            setName(template, name);
        }
        return toDto(templateRepository.saveAndFlush(template));
    }

    @Transactional
    public ChecklistTemplateDto retire(final Long id, final Long version) {
        manageable();

        final ChecklistTemplate template = changeable(id, version);

        AuditContext.operation("checklistTemplateRetire");
        template.setRetiredAt(clock.instant());
        return toDto(templateRepository.saveAndFlush(template));
    }

    /**
     * Makes a retired template active again. A chapter template only while the latest published version has its chapter
     * and no other active template has it, as when creating one.
     */
    @Transactional
    public ChecklistTemplateDto restore(final Long id, final Long version) {
        manageable();

        final ChecklistTemplate template = templateRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(ChecklistTemplate.class, id));
        final String chapterKey = template.getChapterKey();

        // No longer retired: someone else restored it meanwhile.
        if (!template.isRetired() || !Objects.equals(version, template.getVersion())) {
            throw new ConflictException("error.conflict");
        }

        if (chapterKey != null) {
            if (latest().flatMap(view -> view.chapter(chapterKey)).isEmpty()) {
                throw new BadRequestException("error.template.chapter.unknown");
            }
            if (templateRepository.findByChapterKeyAndRetiredAtIsNull(chapterKey).isPresent()) {
                throw new ConflictException("error.template.chapter.exists");
            }
        }
        AuditContext.operation("checklistTemplateRestore");
        template.setRetiredAt(null);
        return toDto(templateRepository.saveAndFlush(template));
    }

    // --- Items ------------------------------------------------------------------------------------------------------

    @Transactional
    public ChecklistTemplateItemDto createItem(final NewItem newItem) {
        manageable();

        final ChecklistTemplate template = active(newItem.templateId());
        final List<ChecklistTemplateItem> items = new ArrayList<>(activeItems(template.getId()));
        final ChecklistTemplateItem item = new ChecklistTemplateItem();

        AuditContext.operation("checklistTemplateItemCreate");
        item.setTemplateId(template.getId());
        setText(item, newItem.text());
        item.setGuideItemKey(newItem.guideItemKey());
        if (newItem.defaultCapability() != null) {
            capabilityService.requireAssignable(List.of(newItem.defaultCapability()), List.of());
        }
        item.setDefaultCapability(newItem.defaultCapability());
        item.setDeadline(validDeadline(template, newItem.deadline()));
        item.setPosition(items.size());
        items.add(clamp(newItem.position(), items.size()), item);

        final ChecklistTemplateItem saved = itemRepository.save(item);

        renumber(items);
        itemRepository.saveAndFlush(saved);
        return ChecklistTemplateItemDto.of(saved);
    }

    @Transactional
    public ChecklistTemplateItemDto updateItem(final ItemChange change) {
        manageable();

        final ChecklistTemplateItem item = changeableItem(change.id(), change.version());
        final ChecklistTemplate template = active(item.getTemplateId());

        AuditContext.operation("checklistTemplateItemUpdate");
        if (change.text() != null) {
            setText(item, change.text());
        }
        if (change.clearGuideItem()) {
            item.setGuideItemKey(null);
        } else if (change.guideItemKey() != null) {
            item.setGuideItemKey(change.guideItemKey());
        }
        if (change.clearDefaultCapability()) {
            item.setDefaultCapability(null);
        } else if (change.defaultCapability() != null) {
            capabilityService.requireAssignable(List.of(change.defaultCapability()),
                    item.getDefaultCapability() == null ? List.of() : List.of(item.getDefaultCapability()));
            item.setDefaultCapability(change.defaultCapability());
        }
        if (change.clearDeadline()) {
            item.setDeadline(null);
        } else if (change.deadline() != null) {
            item.setDeadline(validDeadline(template, change.deadline()));
        }
        return ChecklistTemplateItemDto.of(itemRepository.saveAndFlush(item));
    }

    @Transactional
    public ChecklistTemplateDto moveItem(final Long id, final Long version, final int position) {
        manageable();

        final ChecklistTemplateItem item = changeableItem(id, version);
        final ChecklistTemplate template = active(item.getTemplateId());
        final List<ChecklistTemplateItem> items = new ArrayList<>(activeItems(template.getId()));

        AuditContext.operation("checklistTemplateItemMove");
        items.remove(item);
        items.add(clamp(position, items.size()), item);
        renumber(items);
        itemRepository.saveAndFlush(item);
        return toDto(template);
    }

    @Transactional
    public ChecklistTemplateDto retireItem(final Long id, final Long version) {
        manageable();

        final ChecklistTemplateItem item = changeableItem(id, version);
        final ChecklistTemplate template = active(item.getTemplateId());

        AuditContext.operation("checklistTemplateItemRetire");
        item.setRetiredAt(clock.instant());
        itemRepository.saveAndFlush(item);
        renumber(activeItems(template.getId()));
        return toDto(template);
    }

    /**
     * Puts a retired item back, last in its template, which must be active.
     */
    @Transactional
    public ChecklistTemplateDto restoreItem(final Long id, final Long version) {
        manageable();

        final ChecklistTemplateItem item = itemRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(ChecklistTemplateItem.class, id));

        if (!item.isRetired() || !Objects.equals(version, item.getVersion())) {
            throw new ConflictException("error.conflict");
        }

        final ChecklistTemplate template = active(item.getTemplateId());

        AuditContext.operation("checklistTemplateItemRestore");
        item.setPosition(activeItems(template.getId()).size());
        item.setRetiredAt(null);
        itemRepository.saveAndFlush(item);
        return toDto(template);
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    private Caller readable() {
        final Caller caller = callerService.current();

        accessPolicy.requireReadChecklistTemplates(caller, guideAccess.callerRevivals(caller));
        return caller;
    }

    private void manageable() {
        accessPolicy.requireManageChecklistTemplates(callerService.current());
    }

    private ChecklistTemplate changeable(final Long id, final Long version) {
        final ChecklistTemplate template = active(id);

        if (!Objects.equals(version, template.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return template;
    }

    private ChecklistTemplate active(final Long id) {
        final ChecklistTemplate template = templateRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(ChecklistTemplate.class, id));

        if (template.isRetired()) {
            throw new BadRequestException("error.template.retired");
        }
        return template;
    }

    private ChecklistTemplateItem changeableItem(final Long id, final Long version) {
        final ChecklistTemplateItem item = itemRepository.findById(id).filter(found -> !found.isRetired())
                .orElseThrow(() -> new ResourceNotFoundException(ChecklistTemplateItem.class, id));

        if (!Objects.equals(version, item.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return item;
    }

    private List<ChecklistTemplateItem> activeItems(final Long templateId) {
        return itemRepository.findByTemplateIdAndRetiredAtIsNullOrderByPositionAscIdAsc(templateId);
    }

    private ChecklistTemplateDto toDto(final ChecklistTemplate template) {
        return ChecklistTemplateDto.of(template, template.getId() == null ? List.of() : activeItems(template.getId()));
    }

    private static void renumber(final List<ChecklistTemplateItem> items) {
        for (int position = 0; position < items.size(); position++) {
            if (items.get(position).getPosition() != position) {
                items.get(position).setPosition(position);
            }
        }
    }

    private static int clamp(final @Nullable Integer position, final int size) {
        return position == null ? size : Math.clamp(position, 0, size);
    }

    private static void setName(final ChecklistTemplate template, final @Nullable Texts name) {
        if (name == null || name.sv().isBlank()) {
            throw new BadRequestException("error.template.name.blank");
        }
        template.setNameSv(validName(name.sv()));
        template.setNameEn(name.en() == null || name.en().isBlank() ? null : validName(name.en()));
    }

    private static String validName(final String name) {
        final String trimmed = name.strip();

        if (trimmed.length() > ChecklistTemplate.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.template.name.tooLong", ChecklistTemplate.NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    private static void setText(final ChecklistTemplateItem item, final Texts text) {
        if (text.sv().isBlank()) {
            throw new BadRequestException("error.checklist.text.blank");
        }
        item.setTextSv(validText(text.sv()));
        item.setTextEn(text.en() == null || text.en().isBlank() ? null : validText(text.en()));
    }

    private static String validText(final String text) {
        final String trimmed = text.strip();

        if (trimmed.length() > ChecklistTemplateItem.TEXT_MAX_LENGTH) {
            throw new BadRequestException("error.checklist.text.tooLong", ChecklistTemplateItem.TEXT_MAX_LENGTH);
        }
        return trimmed;
    }

    private static @Nullable RelativeDeadline validDeadline(final ChecklistTemplate template, final @Nullable RelativeDeadline deadline) {
        if (deadline != null && template.getKind() == ChecklistKind.CHAPTER) {
            throw new BadRequestException("error.template.chapterDeadline");
        }
        return deadline;
    }
}
