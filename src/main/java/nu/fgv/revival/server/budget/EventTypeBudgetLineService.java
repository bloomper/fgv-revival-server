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
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.event.EventType;
import nu.fgv.revival.server.event.EventTypeDto;
import nu.fgv.revival.server.event.EventTypeRepository;
import nu.fgv.revival.server.event.EventTypeService;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The lines event types propose for budgets (product spec 5.10, M5-FR-3): the super admin keeps them, everyone signed
 * in reads them with the types. Removing one deletes it; budget lines made from it keep its id.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Service
public class EventTypeBudgetLineService {

    private final EventTypeBudgetLineRepository repository;
    private final EventTypeRepository typeRepository;
    private final EventTypeService typeService;
    private final BudgetCategoryService categoryService;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;

    public EventTypeBudgetLineService(final EventTypeBudgetLineRepository repository,
                                      final EventTypeRepository typeRepository,
                                      final EventTypeService typeService,
                                      final BudgetCategoryService categoryService,
                                      final CallerService callerService,
                                      final AccessPolicy accessPolicy) {
        this.repository = repository;
        this.typeRepository = typeRepository;
        this.typeService = typeService;
        this.categoryService = categoryService;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
    }

    /**
     * The types' lines in their order, by type id. No caller check: everyone signed in reads the types.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<EventTypeBudgetLineDto>> byTypes(final Collection<Long> typeIds) {
        return typeIds.isEmpty() ? Map.of()
                : repository.findByEventTypeIdInOrderByPositionAscIdAsc(Set.copyOf(typeIds)).stream().map(EventTypeBudgetLineDto::of)
                .collect(Collectors.groupingBy(EventTypeBudgetLineDto::eventTypeId));
    }

    /**
     * Appends a line to the type and returns the type.
     */
    @Transactional
    public EventTypeDto create(final Definition definition) {
        manageable();
        if (definition.eventTypeId() == null) {
            throw new BadRequestException("error.eventTypeBudgetLine.type");
        }

        final EventType type = typeRepository.findById(definition.eventTypeId())
                .orElseThrow(() -> new ResourceNotFoundException(EventType.class, definition.eventTypeId()));
        final EventTypeBudgetLine line = new EventTypeBudgetLine();

        line.setEventTypeId(type.getId());
        line.setPosition(repository.findByEventTypeIdOrderByPositionAscIdAsc(type.getId()).size());
        define(line, definition, null);
        AuditContext.operation("eventTypeBudgetLineCreate");
        repository.saveAndFlush(line);
        return typeService.get(type.getId());
    }

    @Transactional
    public EventTypeDto update(final Long id, final Long version, final Definition definition) {
        manageable();

        final EventTypeBudgetLine line = changeable(id, version);

        define(line, definition, line.getCategoryId());
        AuditContext.operation("eventTypeBudgetLineUpdate");
        repository.saveAndFlush(line);
        return typeService.get(line.getEventTypeId());
    }

    /**
     * Deletes the line; budget lines made from it stay as they are.
     */
    @Transactional
    public EventTypeDto remove(final Long id, final Long version) {
        manageable();

        final EventTypeBudgetLine line = changeable(id, version);
        final Long typeId = line.getEventTypeId();

        AuditContext.operation("eventTypeBudgetLineRemove");
        repository.delete(line);
        repository.flush();

        final List<EventTypeBudgetLine> remaining = repository.findByEventTypeIdOrderByPositionAscIdAsc(typeId);

        for (int index = 0; index < remaining.size(); index++) {
            if (remaining.get(index).getPosition() != index) {
                remaining.get(index).setPosition(index);
                repository.saveAndFlush(remaining.get(index));
            }
        }
        return typeService.get(typeId);
    }

    private void manageable() {
        accessPolicy.requireManageEventTypes(callerService.current());
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    private EventTypeBudgetLine changeable(final Long id, final Long version) {
        final EventTypeBudgetLine line = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(EventTypeBudgetLine.class, id));

        if (!Objects.equals(version, line.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return line;
    }

    private void define(final EventTypeBudgetLine line, final Definition definition, final @Nullable String heldCategory) {
        categoryService.requireAssignable(List.of(definition.categoryId()), heldCategory == null ? List.of() : List.of(heldCategory));
        line.setCategoryId(definition.categoryId());
        line.setDescriptionSv(BudgetService.validDescription(definition.descriptionSv()));
        line.setDescriptionEn(definition.descriptionEn() == null || definition.descriptionEn().isBlank() ? null
                : BudgetService.validDescription(definition.descriptionEn()));
        line.setQuantityRule(Objects.requireNonNull(definition.quantityRule()));
        if (definition.quantityRule() == BudgetLineQuantity.FIXED) {
            if (definition.quantity() == null) {
                throw new BadRequestException("error.eventTypeBudgetLine.quantity");
            }
            line.setQuantity(BudgetService.validQuantity(definition.quantity()));
        } else {
            line.setQuantity(null);
        }
        line.setUnitAmount(Objects.requireNonNull(BudgetService.validAmount(definition.unitAmount(), BigDecimal.ZERO)));
    }

    /**
     * What the super admin sets on a line. The type is given only on create.
     */
    public record Definition(@Nullable Long eventTypeId, String categoryId, @Nullable String descriptionSv,
                             @Nullable String descriptionEn,
                             BudgetLineQuantity quantityRule, @Nullable BigDecimal quantity, BigDecimal unitAmount) {
    }
}
