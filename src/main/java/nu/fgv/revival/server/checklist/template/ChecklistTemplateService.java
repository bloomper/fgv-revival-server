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
import nu.fgv.revival.server.guide.GuideVersionRepository;
import nu.fgv.revival.server.guide.GuideVersionStatus;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.GuideVersionViews;
import nu.fgv.revival.server.guide.Phase;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Capability;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
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
 * Checklist templates for phases and event kinds (FR-030, FR-031). Only super admins change them; whoever reads the
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
    private final Clock clock;

    public ChecklistTemplateService(final ChecklistTemplateRepository templateRepository,
                                    final ChecklistTemplateItemRepository itemRepository,
                                    final GuideVersionRepository versionRepository,
                                    final GuideVersionViews guideVersionViews,
                                    final GuideAccess guideAccess,
                                    final CallerService callerService,
                                    final AccessPolicy accessPolicy,
                                    final Clock clock) {
        this.templateRepository = templateRepository;
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.guideVersionViews = guideVersionViews;
        this.guideAccess = guideAccess;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    public record Texts(String sv, @Nullable String en) {
    }

    public record NewTemplate(ChecklistKind kind, @Nullable Phase phase, @Nullable Texts name) {
    }

    public record NewItem(Long templateId, @Nullable Integer position, Texts text, @Nullable String guideItemKey,
                          @Nullable Capability defaultCapability, @Nullable RelativeDeadline deadline) {
    }

    /**
     * Fields left null are unchanged; the {@code clear*} flags remove a value.
     */
    public record ItemChange(Long id, Long version, @Nullable Texts text, @Nullable String guideItemKey, boolean clearGuideItem,
                             @Nullable Capability defaultCapability, boolean clearDefaultCapability, @Nullable RelativeDeadline deadline,
                             boolean clearDeadline) {
    }

    // --- Reading ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChecklistTemplateDto> list(final boolean includeRetired) {
        final Caller caller = readable();
        final List<ChecklistTemplate> templates = includeRetired && accessPolicy.canManageChecklistTemplates(caller)
                ? templateRepository.findAllByOrderByKindAscPhaseAscNameSvAscIdAsc()
                : templateRepository.findByRetiredAtIsNullOrderByKindAscPhaseAscNameSvAscIdAsc();
        final Map<Long, List<ChecklistTemplateItem>> items = itemRepository.findByTemplateIdIn(templates.stream().map(ChecklistTemplate::getId).toList())
                .stream()
                .filter(item -> !item.isRetired())
                .sorted(ITEM_ORDER)
                .collect(Collectors.groupingBy(ChecklistTemplateItem::getTemplateId));

        return templates.stream().map(template -> ChecklistTemplateDto.of(template, items.getOrDefault(template.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public ChecklistTemplateDto get(final Long id) {
        readable();
        final ChecklistTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(ChecklistTemplate.class, id));

        return toDto(template);
    }

    /**
     * Template items' guide items in the latest published version, where the key is still there.
     */
    @Transactional(readOnly = true)
    public Map<ChecklistTemplateItemDto, GuideVersionView.Item> guideItems(final Collection<ChecklistTemplateItemDto> items) {
        final Optional<GuideVersionView> latest = versionRepository.findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED)
                .map(version -> guideVersionViews.find(version.getId()));
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
        if (newTemplate.kind() == ChecklistKind.PHASE) {
            if (newTemplate.phase() == null) {
                throw new BadRequestException("error.template.phase.required");
            }
            if (templateRepository.findByPhaseAndRetiredAtIsNull(newTemplate.phase()).isPresent()) {
                throw new ConflictException("error.template.phase.exists", newTemplate.phase());
            }
            template.setPhase(newTemplate.phase());
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
        if (deadline != null && template.getKind() == ChecklistKind.PHASE) {
            throw new BadRequestException("error.template.phaseDeadline");
        }
        return deadline;
    }
}
