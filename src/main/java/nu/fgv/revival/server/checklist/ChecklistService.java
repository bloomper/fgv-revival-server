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


package nu.fgv.revival.server.checklist;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.Capability;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateSummaryDto;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateRepository;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItemRepository;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItem;
import nu.fgv.revival.server.checklist.template.ChecklistTemplate;
import nu.fgv.revival.server.guide.GuideItemType;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.Phase;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A revival's checklists and their items (FR-022–FR-029). Items are never deleted: they are done, reopened or marked
 * not applicable, and only directeurs waive a guide requirement (FR-025).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Service
public class ChecklistService {

    private static final Locale SWEDISH = Locale.of("sv");

    private static final Comparator<Checklist> ORDER = Comparator
            .comparing((Checklist checklist) -> checklist.getKind() == ChecklistKind.PHASE ? 0 : 1)
            .thenComparing(checklist -> checklist.getPhase() == null ? "" : checklist.getPhase().name())
            .thenComparing(Checklist::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Checklist::getId);

    private final ChecklistRepository checklistRepository;
    private final ChecklistItemRepository itemRepository;
    private final ChecklistTexts texts;
    private final ChecklistTemplateRepository templateRepository;
    private final ChecklistTemplateItemRepository templateItemRepository;
    private final RevivalMemberRepository memberRepository;
    private final RevivalRepository revivalRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final OwnerResolver ownerResolver;
    private final OwnerViews ownerViews;
    private final PersonNames personNames;
    private final MessageSource messageSource;
    private final Clock clock;

    public ChecklistService(final ChecklistRepository checklistRepository,
                            final ChecklistItemRepository itemRepository,
                            final ChecklistTexts texts,
                            final ChecklistTemplateRepository templateRepository,
                            final ChecklistTemplateItemRepository templateItemRepository,
                            final RevivalMemberRepository memberRepository,
                            final RevivalRepository revivalRepository,
                            final CallerService callerService,
                            final AccessPolicy accessPolicy,
                            final OwnerResolver ownerResolver,
                            final OwnerViews ownerViews,
                            final PersonNames personNames,
                            final MessageSource messageSource,
                            final Clock clock) {
        this.checklistRepository = checklistRepository;
        this.itemRepository = itemRepository;
        this.texts = texts;
        this.templateRepository = templateRepository;
        this.templateItemRepository = templateItemRepository;
        this.memberRepository = memberRepository;
        this.revivalRepository = revivalRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.ownerResolver = ownerResolver;
        this.ownerViews = ownerViews;
        this.personNames = personNames;
        this.messageSource = messageSource;
        this.clock = clock;
    }

    public record NewItem(Long checklistId, String text, @Nullable OwnerInputDto owner, @Nullable LocalDate deadline,
                          @Nullable String comment, @Nullable String guideItemKey) {
    }

    /**
     * Fields left null are unchanged; the {@code clear*} flags remove a value. Setting or clearing the deadline marks it
     * as set by hand.
     */
    public record ItemChange(Long id, Long version, @Nullable String text, @Nullable OwnerInputDto owner, boolean clearOwner,
                             @Nullable LocalDate deadline, boolean clearDeadline, @Nullable String comment, boolean clearComment) {
    }

    public record NewEventChecklist(Long revivalId, String name, @Nullable LocalDate date, @Nullable Long templateId) {
    }

    /**
     * Fields left null are unchanged; {@code clearDate} removes the date.
     */
    public record EventChecklistChange(Long id, Long version, @Nullable String name, @Nullable LocalDate date, boolean clearDate) {
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // --- Reading ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChecklistSummaryDto> list(final Long revivalId, final boolean includeCancelled) {
        visible(callerService.current(), revivalId);

        final List<Checklist> checklists = checklistRepository.findByRevivalId(revivalId).stream()
                .filter(checklist -> includeCancelled || !checklist.isCancelled())
                .sorted(ORDER)
                .toList();

        return new ArrayList<>(summaries(checklists).values());
    }

    @Transactional(readOnly = true)
    public ChecklistDto get(final Long id, final Locale locale) {
        final Checklist checklist = readable(callerService.current(), id);
        final List<ChecklistItem> items = itemRepository.findByChecklistIdOrderByPositionAscIdAsc(id);
        final Optional<GuideVersionView> version = texts.pinnedVersion(checklist.getRevivalId());
        final List<GuideVersionView.Item> tips = checklist.getPhase() == null ? List.of()
                : version.map(view -> view.items().filter(item -> item.phase() == checklist.getPhase() && item.type() == GuideItemType.TIP).toList())
                .orElse(List.of());

        return new ChecklistDto(checklist.getId(), checklist.getRevivalId(), checklist.getKind(), checklist.getPhase(),
                checklist.getPhase() == null ? null : version.map(view -> view.chapter(checklist.getPhase()).title()).orElse(null),
                checklist.getName(), checklist.getDate(), checklist.getTemplateId(), checklist.isCancelled(), toDtos(items, locale), tips,
                checklist.getVersion());
    }

    @Transactional(readOnly = true)
    public ChecklistItemDto getItem(final Long id, final Locale locale) {
        final ChecklistItem item = itemRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(ChecklistItem.class, id));
        final Revival revival = revivalRepository.findById(item.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canReadRevivalWork(callerService.current(), revival)) {
            throw new ResourceNotFoundException(ChecklistItem.class, id);
        }
        return toDtos(List.of(item), locale).getFirst();
    }

    @Transactional(readOnly = true)
    public List<ChecklistItemDto> items(final Long revivalId, final @Nullable ChecklistItemFilterDto filter, final Locale locale) {
        return items(revivalId, filter, null, null, locale);
    }

    /**
     * As {@link #items(Long, ChecklistItemFilterDto, Locale)}, also limited to one phase's checklist or to event
     * checklists whose name has every given word, ignoring case (MCP's list_checklist_items).
     */
    @Transactional(readOnly = true)
    public List<ChecklistItemDto> items(final Long revivalId, final @Nullable ChecklistItemFilterDto filter, final @Nullable Phase phase,
                                        final @Nullable String checklistName, final Locale locale) {
        final Caller caller = callerService.current();

        visible(caller, revivalId);

        final ChecklistItemFilterDto actual = filter == null ? ChecklistItemFilterDto.none() : filter;
        final List<String> words = checklistName == null ? List.of()
                : Arrays.stream(checklistName.toLowerCase(Locale.ROOT).split("\\s+")).filter(word -> !word.isEmpty()).toList();
        final LocalDate today = today();
        final Map<Long, Checklist> checklists = checklistRepository.findByRevivalId(revivalId).stream()
                .collect(Collectors.toMap(Checklist::getId, Function.identity()));
        final List<ChecklistItem> items = itemRepository.findByRevivalId(revivalId).stream()
                .filter(item -> checklists.containsKey(item.getChecklistId()) && !checklists.get(item.getChecklistId()).isCancelled())
                .filter(item -> !Boolean.TRUE.equals(actual.mine()) || Objects.equals(item.getOwner().getPersonId(), caller.person().getId()))
                .filter(item -> !Boolean.TRUE.equals(actual.open()) || item.getState() == ChecklistItemState.OPEN)
                .filter(item -> !Boolean.TRUE.equals(actual.overdue()) || item.isOverdue(today))
                .filter(item -> actual.checklistId() == null || item.getChecklistId().equals(actual.checklistId()))
                .filter(item -> !Boolean.TRUE.equals(actual.requiredOnly()) || item.isRequired())
                .filter(item -> phase == null || checklists.get(item.getChecklistId()).getPhase() == phase)
                .filter(item -> words.isEmpty() || hasWords(checklists.get(item.getChecklistId()).getName(), words))
                .sorted(Comparator.comparing((ChecklistItem item) -> checklists.get(item.getChecklistId()), ORDER)
                        .thenComparingInt(ChecklistItem::getPosition)
                        .thenComparing(ChecklistItem::getId))
                .toList();

        return toDtos(items, locale);
    }

    /**
     * The template a checklist was made from, retired or not.
     */
    @Transactional(readOnly = true)
    public @Nullable ChecklistTemplateSummaryDto template(final ChecklistDto checklist) {
        return checklist.templateId() == null ? null
                : templateRepository.findById(checklist.templateId()).map(ChecklistTemplateSummaryDto::of).orElse(null);
    }

    /**
     * The templates the caller may add items from (FR-032a): a phase checklist's phase template, or any event template.
     * None for those who cannot write, or on a cancelled checklist.
     */
    @Transactional(readOnly = true)
    public List<ChecklistTemplateSummaryDto> templatesAvailable(final ChecklistDto checklist) {
        final Revival revival = revivalRepository.findById(checklist.revivalId()).orElse(null);

        if (revival == null || checklist.cancelled() || !accessPolicy.canWriteRevivalWork(callerService.current(), revival)) {
            return List.of();
        }

        final List<ChecklistTemplate> templates = checklist.kind() == ChecklistKind.PHASE
                ? templateRepository.findByPhaseAndRetiredAtIsNull(Objects.requireNonNull(checklist.phase())).stream().toList()
                : templateRepository.findByKindAndRetiredAtIsNullOrderByNameSvAscIdAsc(ChecklistKind.EVENT);

        return templates.stream().map(ChecklistTemplateSummaryDto::of).toList();
    }

    // --- Writing ----------------------------------------------------------------------------------------------------

    @Transactional
    public ChecklistDto createEventChecklist(final NewEventChecklist newChecklist, final Locale locale) {
        final Caller caller = callerService.current();

        writable(caller, newChecklist.revivalId());

        final @Nullable ChecklistTemplate template = newChecklist.templateId() == null ? null : eventTemplate(newChecklist.templateId());
        final Checklist checklist = new Checklist();

        AuditContext.operation("eventChecklistCreate");
        checklist.setRevivalId(newChecklist.revivalId());
        checklist.setKind(ChecklistKind.EVENT);
        checklist.setName(validName(newChecklist.name()));
        checklist.setDate(newChecklist.date());
        checklist.setTemplateId(template == null ? null : template.getId());

        final Checklist saved = checklistRepository.saveAndFlush(checklist);

        if (template != null) {
            addTemplateItems(saved, template, locale);
        }
        return get(saved.getId(), locale);
    }

    /**
     * A changed date recomputes the deadlines that came from the template and were not set by hand; no date clears them
     * (FR-023, research R11).
     */
    @Transactional
    public ChecklistDto updateEventChecklist(final EventChecklistChange change, final Locale locale) {
        final Checklist checklist = changeableChecklist(change.id(), change.version());

        if (checklist.getKind() != ChecklistKind.EVENT) {
            throw new BadRequestException("error.checklist.phase.edit");
        }
        AuditContext.operation("eventChecklistUpdate");
        if (change.name() != null) {
            checklist.setName(validName(change.name()));
        }

        final @Nullable LocalDate date = change.clearDate() ? null : change.date() != null ? change.date() : checklist.getDate();

        if (!Objects.equals(date, checklist.getDate())) {
            checklist.setDate(date);
            recomputeDeadlines(checklist);
        }
        checklistRepository.saveAndFlush(checklist);
        return get(checklist.getId(), locale);
    }

    @Transactional
    public ChecklistDto cancelEventChecklist(final Long id, final Long version, final Locale locale) {
        final Checklist checklist = changeableChecklist(id, version);

        if (checklist.getKind() != ChecklistKind.EVENT) {
            throw new BadRequestException("error.checklist.phase.cancel");
        }
        AuditContext.operation("eventChecklistCancel");
        checklist.setCancelledAt(clock.instant());
        checklistRepository.saveAndFlush(checklist);
        return get(id, locale);
    }

    /**
     * Adds the template's items the checklist does not have yet, whatever their state; existing items are left alone
     * (FR-032a). A phase checklist uses its phase's template unless one is named.
     */
    @Transactional
    public ChecklistDto addFromTemplate(final Long checklistId, final Long version, final @Nullable Long templateId, final Locale locale) {
        final Checklist checklist = changeableChecklist(checklistId, version);
        final ChecklistTemplate template;

        if (checklist.getKind() == ChecklistKind.PHASE) {
            template = templateId == null
                    ? templateRepository.findByPhaseAndRetiredAtIsNull(Objects.requireNonNull(checklist.getPhase()))
                    .orElseThrow(() -> new BadRequestException("error.checklist.template.kind"))
                    : activeTemplate(templateId);
            if (template.getKind() != ChecklistKind.PHASE || template.getPhase() != checklist.getPhase()) {
                throw new BadRequestException("error.checklist.template.kind");
            }
        } else {
            if (templateId == null) {
                throw new BadRequestException("error.checklist.template.kind");
            }
            template = eventTemplate(templateId);
        }
        AuditContext.operation("checklistAddFromTemplate");
        addTemplateItems(checklist, template, locale);
        return get(checklistId, locale);
    }

    @Transactional
    public ChecklistItemDto createItem(final NewItem newItem, final Locale locale) {
        final Caller caller = callerService.current();
        final Checklist checklist = readable(caller, newItem.checklistId());

        writable(caller, checklist.getRevivalId());
        if (checklist.isCancelled()) {
            throw new BadRequestException("error.checklist.cancelled");
        }
        AuditContext.operation("checklistItemCreate");

        final ChecklistItem item = new ChecklistItem();

        item.setChecklistId(checklist.getId());
        item.setRevivalId(checklist.getRevivalId());
        item.setPosition(nextPosition(checklist.getId()));
        item.setText(validText(newItem.text()));
        item.setOwner(ownerResolver.validate(checklist.getRevivalId(), newItem.owner()));
        item.setDeadline(newItem.deadline());
        item.setDeadlineManual(newItem.deadline() != null);
        item.setComment(validComment(newItem.comment()));
        item.setGuideItemKey(newItem.guideItemKey());
        return toDtos(List.of(itemRepository.saveAndFlush(item)), locale).getFirst();
    }

    @Transactional
    public ChecklistItemDto updateItem(final ItemChange change, final Locale locale) {
        final Caller caller = callerService.current();
        final ChecklistItem item = changeable(caller, change.id(), change.version());

        if (change.text() != null && item.isRequired()) {
            throw new BadRequestException("error.checklist.required.text");
        }
        AuditContext.operation("checklistItemUpdate");
        if (change.text() != null) {
            item.setText(validText(change.text()));
        }
        if (change.clearOwner()) {
            item.setOwner(Owner.none());
        } else if (change.owner() != null) {
            item.setOwner(ownerResolver.change(item.getRevivalId(), item.getOwner(), change.owner()));
        }
        if (change.clearDeadline() && item.getDeadline() != null) {
            item.setDeadline(null);
            item.setDeadlineManual(true);
        } else if (change.deadline() != null && !change.deadline().equals(item.getDeadline())) {
            item.setDeadline(change.deadline());
            item.setDeadlineManual(true);
        }
        if (change.clearComment()) {
            item.setComment(null);
        } else if (change.comment() != null) {
            item.setComment(validComment(change.comment()));
        }
        return toDtos(List.of(itemRepository.saveAndFlush(item)), locale).getFirst();
    }

    @Transactional
    public ChecklistItemDto setState(final Long id, final Long version, final ChecklistItemState state, final @Nullable String reason,
                                     final Locale locale) {
        final Caller caller = callerService.current();
        final ChecklistItem item = changeable(caller, id, version);
        final String trimmed = reason == null || reason.isBlank() ? null : reason.strip();

        if (trimmed != null && trimmed.length() > ChecklistItem.REASON_MAX_LENGTH) {
            throw new BadRequestException("error.checklist.reason.tooLong", ChecklistItem.REASON_MAX_LENGTH);
        }
        if (state == ChecklistItemState.NOT_APPLICABLE && item.isRequired()) {
            accessPolicy.requireWaiveRequirement(caller, revivalOf(item.getRevivalId()));
            if (trimmed == null) {
                throw new BadRequestException("error.checklist.reason.required");
            }
        }
        AuditContext.operation("checklistItemSetState");
        item.setState(state);
        item.setStateChangedBy(caller.person().getId());
        item.setStateChangedAt(clock.instant());
        item.setReason(state == ChecklistItemState.OPEN ? null : trimmed);
        item.setReasonKey(null);
        item.setReasonArg(null);
        return toDtos(List.of(itemRepository.saveAndFlush(item)), locale).getFirst();
    }

    /**
     * Marks an item done, with an optional new comment, for MCP's complete_checklist_item. An item already done is
     * returned as it is.
     */
    @Transactional
    public ChecklistItemDto complete(final Long id, final @Nullable String comment, final Locale locale) {
        final ChecklistItemDto item = getItem(id, locale);
        final ChecklistItem stored = itemRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(ChecklistItem.class, id));

        writable(callerService.current(), stored.getRevivalId());
        if (item.state() == ChecklistItemState.DONE) {
            return item;
        }

        final Long version = comment == null ? item.version()
                : updateItem(new ItemChange(id, item.version(), null, null, false, null, false, comment, comment.isBlank()), locale).version();

        return setState(id, version, ChecklistItemState.DONE, null, locale);
    }

    // --- Internal, for the guide sync and templates (research R10); callers have checked access -----------------------

    /**
     * Adds a phase template's items to phase checklists the sync has just created (User Story 5, scenario 8).
     */
    @Transactional
    public void addPhaseTemplateItems(final List<Checklist> created) {
        for (final Checklist checklist : created) {
            templateRepository.findByPhaseAndRetiredAtIsNull(Objects.requireNonNull(checklist.getPhase()))
                    .ifPresent(template -> addTemplateItems(checklist, template, SWEDISH));
        }
    }

    /**
     * Copies the template items the checklist lacks, by {@code template_item_id}: text in the given language with Swedish
     * as fallback, deadlines from the checklist's date, and the one steering group member holding the item's capability
     * as owner (research R11). Returns the number added.
     */
    int addTemplateItems(final Checklist checklist, final ChecklistTemplate template, final Locale locale) {
        final List<ChecklistItem> existing = itemRepository.findByChecklistIdOrderByPositionAscIdAsc(checklist.getId());
        final Set<Long> present = existing.stream().map(ChecklistItem::getTemplateItemId).filter(Objects::nonNull).collect(Collectors.toSet());
        final List<RevivalMember> steeringGroup = memberRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(checklist.getRevivalId()).stream()
                .filter(member -> member.getRole() == RevivalRole.STEERING_GROUP && member.getPersonId() != null)
                .toList();
        final boolean english = "en".equals(locale.getLanguage());
        int position = existing.stream().mapToInt(ChecklistItem::getPosition).max().orElse(-1) + 1;
        int added = 0;

        for (final ChecklistTemplateItem templateItem : templateItemRepository.findByTemplateIdAndRetiredAtIsNullOrderByPositionAscIdAsc(template.getId())) {
            if (present.contains(templateItem.getId())) {
                continue;
            }

            final ChecklistItem item = new ChecklistItem();

            item.setChecklistId(checklist.getId());
            item.setRevivalId(checklist.getRevivalId());
            item.setPosition(position++);
            item.setText(english && templateItem.getTextEn() != null ? templateItem.getTextEn() : templateItem.getTextSv());
            item.setGuideItemKey(templateItem.getGuideItemKey());
            item.setTemplateItemId(templateItem.getId());
            item.setDefaultCapability(templateItem.getDefaultCapability());
            item.setRelativeDeadline(templateItem.getDeadline() == null ? null : templateItem.getDeadline().copy());
            item.setDeadline(deadline(item.getRelativeDeadline(), checklist.getDate()));
            item.setDeadlineManual(false);
            item.setOwner(defaultOwner(templateItem.getDefaultCapability(), steeringGroup));
            itemRepository.save(item);
            added++;
        }
        return added;
    }

    /**
     * Creates the ten phase checklists a revival lacks. Returns the ones created.
     */
    @Transactional
    public List<Checklist> ensurePhaseChecklists(final Long revivalId) {
        final Set<Phase> existing = checklistRepository.findByRevivalIdAndKind(revivalId, ChecklistKind.PHASE).stream()
                .map(Checklist::getPhase)
                .collect(Collectors.toSet());
        final List<Checklist> created = new ArrayList<>();

        for (final Phase phase : Phase.values()) {
            if (!existing.contains(phase)) {
                final Checklist checklist = new Checklist();

                checklist.setRevivalId(revivalId);
                checklist.setKind(ChecklistKind.PHASE);
                checklist.setPhase(phase);
                created.add(checklistRepository.save(checklist));
            }
        }
        return created;
    }

    /**
     * Makes the revival's required items match the requirements of a guide version: one item per requirement in its
     * phase's checklist, required items first in guide order, and open items for requirements no longer in the version
     * marked not applicable (FR-034).
     */
    @Transactional
    public void syncRequiredItems(final Long revivalId, final GuideVersionView version) {
        final Map<Phase, Checklist> phases = checklistRepository.findByRevivalIdAndKind(revivalId, ChecklistKind.PHASE).stream()
                .collect(Collectors.toMap(Checklist::getPhase, Function.identity()));
        final List<GuideVersionView.Item> requirements = version.items().filter(item -> item.type() == GuideItemType.REQUIREMENT).toList();
        final Map<String, Integer> guideOrder = new HashMap<>();
        final Map<String, ChecklistItem> required = itemRepository.findByRevivalIdAndRequiredTrue(revivalId).stream()
                .collect(Collectors.toMap(item -> Objects.requireNonNull(item.getGuideItemKey()), Function.identity()));
        for (int index = 0; index < requirements.size(); index++) {
            final GuideVersionView.Item requirement = requirements.get(index);
            final Checklist checklist = phases.get(requirement.phase());
            final ChecklistItem item = required.get(requirement.key());

            guideOrder.put(requirement.key(), index);
            if (item == null) {
                final ChecklistItem created = new ChecklistItem();

                created.setChecklistId(checklist.getId());
                created.setRevivalId(revivalId);
                created.setRequired(true);
                created.setGuideItemKey(requirement.key());
                created.setPosition(Integer.MAX_VALUE / 2 + index);
                required.put(requirement.key(), itemRepository.save(created));
            } else if (!item.getChecklistId().equals(checklist.getId())) {
                item.setChecklistId(checklist.getId());
            }
        }
        for (final ChecklistItem item : required.values()) {
            if (!guideOrder.containsKey(item.getGuideItemKey()) && item.getState() == ChecklistItemState.OPEN) {
                item.setState(ChecklistItemState.NOT_APPLICABLE);
                item.setStateChangedBy(null);
                item.setStateChangedAt(clock.instant());
                item.setReason(null);
                item.setReasonKey("checklist.removedFromGuide");
                item.setReasonArg(version.label());
            }
        }
        phases.values().forEach(checklist -> renumber(checklist.getId(), guideOrder));
    }

    /**
     * Required items first in guide order, then the others in the order they were added (FR-022).
     */
    private void renumber(final Long checklistId, final Map<String, Integer> guideOrder) {
        final List<ChecklistItem> items = itemRepository.findByChecklistIdOrderByPositionAscIdAsc(checklistId).stream()
                .sorted(Comparator.comparing((ChecklistItem item) -> item.isRequired() ? 0 : 1)
                        .thenComparing(item -> item.isRequired() ? guideOrder.getOrDefault(item.getGuideItemKey(), Integer.MAX_VALUE) : 0)
                        .thenComparingInt(ChecklistItem::getPosition)
                        .thenComparing(ChecklistItem::getId))
                .toList();

        for (int position = 0; position < items.size(); position++) {
            if (items.get(position).getPosition() != position) {
                items.get(position).setPosition(position);
            }
        }
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    private void recomputeDeadlines(final Checklist checklist) {
        itemRepository.findByChecklistIdOrderByPositionAscIdAsc(checklist.getId()).stream()
                .filter(item -> item.getTemplateItemId() != null && !item.isDeadlineManual())
                .forEach(item -> item.setDeadline(deadline(item.getRelativeDeadline(), checklist.getDate())));
    }

    private static @Nullable LocalDate deadline(final @Nullable RelativeDeadline rule, final @Nullable LocalDate date) {
        return date == null || rule == null ? null : rule.apply(date);
    }

    /**
     * Directeurs hold every capability implicitly, so only steering group members holding it explicitly count, and only
     * when there is exactly one.
     */
    private static Owner defaultOwner(final @Nullable Capability capability, final List<RevivalMember> steeringGroup) {
        if (capability == null) {
            return Owner.none();
        }

        final List<RevivalMember> holders = steeringGroup.stream().filter(member -> member.getCapabilities().contains(capability)).toList();

        return holders.size() == 1 ? Owner.member(Objects.requireNonNull(holders.getFirst().getPersonId())) : Owner.none();
    }

    private ChecklistTemplate activeTemplate(final Long templateId) {
        final ChecklistTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException(ChecklistTemplate.class, templateId));

        if (template.isRetired()) {
            throw new BadRequestException("error.checklist.template.retired");
        }
        return template;
    }

    private ChecklistTemplate eventTemplate(final Long templateId) {
        final ChecklistTemplate template = activeTemplate(templateId);

        if (template.getKind() != ChecklistKind.EVENT) {
            throw new BadRequestException("error.checklist.template.kind");
        }
        return template;
    }

    private Checklist changeableChecklist(final Long id, final Long version) {
        final Caller caller = callerService.current();
        final Checklist checklist = readable(caller, id);

        writable(caller, checklist.getRevivalId());
        if (checklist.isCancelled()) {
            throw new BadRequestException("error.checklist.cancelled");
        }
        if (!Objects.equals(version, checklist.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return checklist;
    }

    static String validName(final String name) {
        final String trimmed = name.strip();

        if (trimmed.isEmpty()) {
            throw new BadRequestException("error.checklist.name.blank");
        }
        if (trimmed.length() > Checklist.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.checklist.name.tooLong", Checklist.NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    private int nextPosition(final Long checklistId) {
        return itemRepository.findByChecklistIdOrderByPositionAscIdAsc(checklistId).stream()
                .mapToInt(ChecklistItem::getPosition).max().orElse(-1) + 1;
    }

    private Revival revivalOf(final Long revivalId) {
        return revivalRepository.findById(revivalId).orElseThrow(() -> new ResourceNotFoundException(Revival.class, revivalId));
    }

    private Revival visible(final Caller caller, final Long revivalId) {
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireReadRevivalWork(caller, revival);
        return revival;
    }

    private Revival writable(final Caller caller, final Long revivalId) {
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireWriteRevivalWork(caller, revival);
        return revival;
    }

    private Checklist readable(final Caller caller, final Long id) {
        final Checklist checklist = checklistRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(Checklist.class, id));
        final Revival revival = revivalRepository.findById(checklist.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canReadRevivalWork(caller, revival)) {
            throw new ResourceNotFoundException(Checklist.class, id);
        }
        return checklist;
    }

    private ChecklistItem changeable(final Caller caller, final Long id, final Long version) {
        final ChecklistItem item = itemRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(ChecklistItem.class, id));
        final Revival revival = revivalRepository.findById(item.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canReadRevivalWork(caller, revival)) {
            throw new ResourceNotFoundException(ChecklistItem.class, id);
        }
        accessPolicy.requireWriteRevivalWork(caller, revival);
        if (checklistRepository.findById(item.getChecklistId()).map(Checklist::isCancelled).orElse(false)) {
            throw new BadRequestException("error.checklist.cancelled");
        }
        if (!Objects.equals(version, item.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return item;
    }

    private static boolean hasWords(final @Nullable String name, final List<String> words) {
        return name != null && words.stream().allMatch(name.toLowerCase(Locale.ROOT)::contains);
    }

    static String validText(final String text) {
        final String trimmed = text.strip();

        if (trimmed.isEmpty()) {
            throw new BadRequestException("error.checklist.text.blank");
        }
        if (trimmed.length() > ChecklistItem.TEXT_MAX_LENGTH) {
            throw new BadRequestException("error.checklist.text.tooLong", ChecklistItem.TEXT_MAX_LENGTH);
        }
        return trimmed;
    }

    static @Nullable String validComment(final @Nullable String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }

        final String trimmed = comment.strip();

        if (trimmed.length() > ChecklistItem.COMMENT_MAX_LENGTH) {
            throw new BadRequestException("error.checklist.comment.tooLong", ChecklistItem.COMMENT_MAX_LENGTH);
        }
        return trimmed;
    }

    Map<Long, ChecklistSummaryDto> summaries(final List<Checklist> checklists) {
        final LocalDate today = today();
        final Map<Long, List<ChecklistItem>> items = itemRepository.findByChecklistIdInOrderByPositionAscIdAsc(
                        checklists.stream().map(Checklist::getId).toList()).stream()
                .collect(Collectors.groupingBy(ChecklistItem::getChecklistId));
        final Map<Long, ChecklistSummaryDto> summaries = new LinkedHashMap<>();

        for (final Checklist checklist : checklists) {
            final List<ChecklistItem> own = items.getOrDefault(checklist.getId(), List.of());

            summaries.put(checklist.getId(), new ChecklistSummaryDto(checklist.getId(), checklist.getKind(), checklist.getPhase(),
                    checklist.getName(), checklist.getDate(), checklist.isCancelled(),
                    (int) own.stream().filter(item -> item.getState() == ChecklistItemState.OPEN).count(),
                    (int) own.stream().filter(item -> item.isOverdue(today)).count(), own.size()));
        }
        return summaries;
    }

    List<ChecklistItemDto> toDtos(final List<ChecklistItem> items, final Locale locale) {
        if (items.isEmpty()) {
            return List.of();
        }

        final Map<Long, GuideVersionView.Text> itemTexts = texts.texts(items);
        final Map<Long, Checklist> checklists = checklistRepository.findAllById(items.stream().map(ChecklistItem::getChecklistId).distinct().toList())
                .stream().collect(Collectors.toMap(Checklist::getId, Function.identity()));
        final Map<Long, ChecklistSummaryDto> summaries = summaries(new ArrayList<>(checklists.values()));
        final Map<OwnerViews.Ref, @Nullable OwnerDto> owners = ownerViews.toDtos(items.stream()
                .map(item -> new OwnerViews.Ref(item.getRevivalId(), item.getOwner())).collect(Collectors.toSet()), locale);
        final Set<Long> changers = items.stream().map(ChecklistItem::getStateChangedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        final Map<Long, String> names = changers.isEmpty() ? Map.of() : personNames.byIds(changers, locale);
        final Map<Long, Optional<GuideVersionView>> versions = new HashMap<>();
        final LocalDate today = today();

        return items.stream().map(item -> {
            final GuideVersionView.@Nullable Item guideItem = item.getGuideItemKey() == null ? null
                    : versions.computeIfAbsent(item.getRevivalId(), texts::pinnedVersion).flatMap(view -> view.item(item.getGuideItemKey())).orElse(null);

            return new ChecklistItemDto(item.getId(), summaries.get(item.getChecklistId()), item.isRequired(), itemTexts.get(item.getId()),
                    item.getState(), item.getStateChangedBy() == null ? null : new PersonRefDto(item.getStateChangedBy(), names.get(item.getStateChangedBy())),
                    item.getStateChangedAt(), reason(item, locale), owners.get(new OwnerViews.Ref(item.getRevivalId(), item.getOwner())),
                    item.getDeadline(), item.isDeadlineManual(), item.isOverdue(today), item.getComment(), guideItem, item.getGuideItemKey(),
                    item.getDefaultCapability(), item.getVersion());
        }).toList();
    }

    private @Nullable String reason(final ChecklistItem item, final Locale locale) {
        if (item.getReasonKey() != null) {
            return messageSource.getMessage(item.getReasonKey(), new Object[]{Objects.requireNonNullElse(item.getReasonArg(), "")},
                    item.getReasonKey(), locale);
        }
        return item.getReason();
    }
}
