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


package nu.fgv.revival.server.event;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.template.ChecklistTemplate;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateRepository;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateSummaryDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The event types the super admin keeps (M4-FR-1): everyone signed in reads them, the super admin creates, changes,
 * moves, retires and restores them. Never deleted: events keep a retired type.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@Service
public class EventTypeService {

    private final EventTypeRepository repository;
    private final ChecklistTemplateRepository templateRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public EventTypeService(final EventTypeRepository repository,
                            final ChecklistTemplateRepository templateRepository,
                            final CallerService callerService,
                            final AccessPolicy accessPolicy,
                            final Clock clock) {
        this.repository = repository;
        this.templateRepository = templateRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    private static String validName(final @Nullable String name) {
        final String trimmed = name == null ? "" : name.strip();

        if (trimmed.isEmpty()) {
            throw new BadRequestException("error.eventType.name.blank");
        }
        if (trimmed.length() > EventType.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.eventType.name.tooLong", EventType.NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * Swedish and English, trimmed; both null when there is no Swedish text.
     */
    private static Text validText(final @Nullable Text text) {
        if (text == null || text.sv() == null || text.sv().isBlank()) {
            return new Text(null, null);
        }

        final String sv = text.sv().strip();
        final String en = text.en() == null || text.en().isBlank() ? null : text.en().strip();

        if (sv.length() > EventType.TEXT_MAX_LENGTH || en != null && en.length() > EventType.TEXT_MAX_LENGTH) {
            throw new BadRequestException("error.eventType.text.tooLong", EventType.TEXT_MAX_LENGTH);
        }
        return new Text(sv, en);
    }

    // --- Reading ----------------------------------------------------------------------------------------------------

    private static void validOffsets(final @Nullable Integer min, final @Nullable Integer max) {
        if (min == null && max == null) {
            return;
        }
        if (min == null || max == null) {
            throw new BadRequestException("error.eventType.offsets.both");
        }
        if (min < -EventType.OFFSET_LIMIT || max > EventType.OFFSET_LIMIT) {
            throw new BadRequestException("error.eventType.offsets.range", EventType.OFFSET_LIMIT);
        }
        if (min > max) {
            throw new BadRequestException("error.eventType.offsets.order");
        }
    }

    @Transactional(readOnly = true)
    public List<EventTypeDto> list(final boolean includeRetired) {
        callerService.current();
        return repository.findAllByOrderByPositionAscIdAsc().stream()
                .filter(type -> includeRetired || !type.isRetired())
                .map(EventTypeDto::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventTypeDto get(final Long id) {
        callerService.current();
        return EventTypeDto.of(repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(EventType.class, id)));
    }

    /**
     * Types by id, retired ones too, for showing what events hold. No caller check: callers have checked the events.
     */
    @Transactional(readOnly = true)
    public Map<Long, EventTypeDto> byIds(final Collection<Long> ids) {
        return ids.isEmpty() ? Map.of()
                : repository.findAllById(Set.copyOf(ids)).stream().map(EventTypeDto::of).collect(Collectors.toMap(EventTypeDto::id, Function.identity()));
    }

    // --- Changing ---------------------------------------------------------------------------------------------------

    /**
     * The type's checklist template, retired or not. Template names are readable by everyone who reads the types.
     */
    @Transactional(readOnly = true)
    public @Nullable ChecklistTemplateSummaryDto template(final EventTypeDto type) {
        return type.checklistTemplateId() == null ? null
                : templateRepository.findById(type.checklistTemplateId()).map(ChecklistTemplateSummaryDto::of).orElse(null);
    }

    /**
     * Appends a new type.
     */
    @Transactional
    public EventTypeDto create(final Definition definition) {
        manageable();

        final List<EventType> types = repository.findAllByOrderByPositionAscIdAsc();
        final EventType type = new EventType();

        AuditContext.operation("eventTypeCreate");
        define(type, definition, types);
        type.setPosition(types.size());
        return EventTypeDto.of(save(type, type.getNameSv()));
    }

    @Transactional
    public EventTypeDto update(final Long id, final Long version, final Definition definition) {
        manageable();

        final EventType type = changeable(id, version);

        AuditContext.operation("eventTypeUpdate");
        define(type, definition, repository.findAllByOrderByPositionAscIdAsc());
        return EventTypeDto.of(save(type, type.getNameSv()));
    }

    /**
     * Returns every type, retired ones too, in the new order. Works as moving an entry in a catalog.
     */
    @Transactional
    public List<EventTypeDto> move(final Long id, final Long version, final int position) {
        manageable();

        final EventType type = changeable(id, version);
        final List<EventType> types = new ArrayList<>(repository.findAllByOrderByPositionAscIdAsc());

        AuditContext.operation("eventTypeMove");
        types.removeIf(candidate -> candidate.getId().equals(id));
        types.add(Math.clamp(position, 0, types.size()), type);
        for (int index = 0; index < types.size(); index++) {
            if (types.get(index).getPosition() != index) {
                types.get(index).setPosition(index);
                repository.save(types.get(index));
            }
        }
        repository.flush();
        return types.stream().map(EventTypeDto::of).toList();
    }

    @Transactional
    public EventTypeDto retire(final Long id, final Long version) {
        manageable();

        final EventType type = changeable(id, version);

        AuditContext.operation("eventTypeRetire");
        type.setRetiredAt(type.isRetired() ? type.getRetiredAt() : clock.instant());
        return EventTypeDto.of(repository.saveAndFlush(type));
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    @Transactional
    public EventTypeDto restore(final Long id, final Long version) {
        manageable();

        final EventType type = changeable(id, version);

        AuditContext.operation("eventTypeRestore");
        type.setRetiredAt(null);
        return EventTypeDto.of(repository.saveAndFlush(type));
    }

    private void manageable() {
        accessPolicy.requireManageEventTypes(callerService.current());
    }

    private EventType changeable(final Long id, final Long version) {
        final EventType type = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(EventType.class, id));

        if (!Objects.equals(version, type.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return type;
    }

    /**
     * The database compares names without regard to case or accents, so a name the check above let through can still
     * collide.
     */
    private EventType save(final EventType type, final String name) {
        try {
            return repository.saveAndFlush(type);
        } catch (final DataIntegrityViolationException e) {
            throw new BadRequestException("error.eventType.name.taken", name);
        }
    }

    private void define(final EventType type, final Definition definition, final List<EventType> types) {
        final String sv = validName(definition.name().sv());
        final String folded = sv.toLowerCase(Locale.ROOT);

        if (types.stream().anyMatch(other -> !other.getId().equals(type.getId()) && other.getNameSv().toLowerCase(Locale.ROOT).equals(folded))) {
            throw new BadRequestException("error.eventType.name.taken", sv);
        }
        type.setNameSv(sv);
        type.setNameEn(definition.name().en() == null || definition.name().en().isBlank() ? null : validName(definition.name().en()));

        final Text description = validText(definition.description());
        final Text rooms = validText(definition.rooms());
        final Text catering = validText(definition.catering());

        type.setDescriptionSv(description.sv());
        type.setDescriptionEn(description.en());
        type.setRoomsSv(rooms.sv());
        type.setRoomsEn(rooms.en());
        type.setCateringSv(catering.sv());
        type.setCateringEn(catering.en());

        validOffsets(definition.offsetMinDays(), definition.offsetMaxDays());
        type.setOffsetMinDays(definition.offsetMinDays());
        type.setOffsetMaxDays(definition.offsetMaxDays());
        if (definition.days() < 1 || definition.days() > EventType.MAX_DAYS) {
            throw new BadRequestException("error.eventType.days", EventType.MAX_DAYS);
        }
        type.setDays(definition.days());
        type.setRevivalPays(definition.revivalPays());
        type.setChecklistTemplateId(validTemplate(definition.checklistTemplateId(), type.getChecklistTemplateId()));
    }

    /**
     * An EVENT template that is not retired, unless the type already has it.
     */
    private @Nullable Long validTemplate(final @Nullable Long templateId, final @Nullable Long held) {
        if (templateId == null) {
            return null;
        }

        final ChecklistTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException(ChecklistTemplate.class, templateId));

        if (template.getKind() != ChecklistKind.EVENT) {
            throw new BadRequestException("error.eventType.template.kind");
        }
        if (template.isRetired() && !templateId.equals(held)) {
            throw new BadRequestException("error.checklist.template.retired");
        }
        return templateId;
    }

    /**
     * Swedish text and, optionally, English.
     */
    public record Text(@Nullable String sv, @Nullable String en) {
    }

    /**
     * Everything the super admin sets on a type. A null or blank Swedish text clears that text.
     */
    public record Definition(Text name, @Nullable Text description, @Nullable Text rooms, @Nullable Text catering,
                             @Nullable Integer offsetMinDays, @Nullable Integer offsetMaxDays, int days,
                             boolean revivalPays,
                             @Nullable Long checklistTemplateId) {
    }
}
