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
package nu.fgv.revival.server.material;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.material.MaterialDtos.InventoryItemDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A revival's inventory (M8-FR-4, M8-FR-5): read by super admins and its directeurs and steering group, changed by
 * them while it is not archived. An item's owner is a member of the steering group or a free-text name; its material
 * is one of the same revival. Never deleted.
 *
 * @author Anders Jacobsson
 * @since 0.9
 */
@Service
public class InventoryService {

    private static final Comparator<InventoryItem> ORDER = Comparator.comparing((InventoryItem item) -> item.getName().toLowerCase(Locale.ROOT))
            .thenComparing(InventoryItem::getId);

    private final InventoryItemRepository repository;
    private final MaterialRepository materialRepository;
    private final RevivalRepository revivalRepository;
    private final OwnerResolver ownerResolver;
    private final OwnerViews ownerViews;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public InventoryService(final InventoryItemRepository repository,
                            final MaterialRepository materialRepository,
                            final RevivalRepository revivalRepository,
                            final OwnerResolver ownerResolver,
                            final OwnerViews ownerViews,
                            final CallerService callerService,
                            final AccessPolicy accessPolicy,
                            final Clock clock) {
        this.repository = repository;
        this.materialRepository = materialRepository;
        this.revivalRepository = revivalRepository;
        this.ownerResolver = ownerResolver;
        this.ownerViews = ownerViews;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    private static String validName(final @Nullable String name) {
        final String trimmed = name == null ? "" : name.strip();

        if (trimmed.isEmpty() || trimmed.length() > InventoryItem.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.inventory.name", InventoryItem.NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    private static @Nullable String optional(final @Nullable String text, final int maxLength, final String messageCode) {
        if (text == null || text.isBlank()) {
            return null;
        }

        final String trimmed = text.strip();

        if (trimmed.length() > maxLength) {
            throw new BadRequestException(messageCode, maxLength);
        }
        return trimmed;
    }

    /**
     * By name. Removed ones only with {@code includeRemoved}, and only for writers.
     */
    @Transactional(readOnly = true)
    public List<InventoryItemDto> list(final Long revivalId, final boolean includeRemoved, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireReadInventory(caller, revival);

        final boolean withRemoved = includeRemoved && accessPolicy.canWriteInventory(caller, revival);
        final List<InventoryItem> items = repository.findByRevivalId(revivalId).stream()
                .filter(item -> withRemoved || !item.isRemoved())
                .sorted(ORDER)
                .toList();
        final Map<OwnerViews.Ref, @Nullable OwnerDto> owners = ownerViews.toDtos(items.stream()
                .map(item -> new OwnerViews.Ref(item.getRevivalId(), item.getOwner())).toList(), locale);

        return items.stream().map(item -> InventoryItemDto.of(item, owners.get(new OwnerViews.Ref(item.getRevivalId(), item.getOwner())))).toList();
    }

    @Transactional
    public InventoryItemDto create(final Input input, final Locale locale) {
        final Caller caller = callerService.current();

        if (input.revivalId() == null) {
            throw new BadRequestException("error.material.revival");
        }

        final Revival revival = writable(caller, input.revivalId());
        final InventoryItem item = new InventoryItem();

        AuditContext.operation("inventoryItemCreate");
        item.setRevivalId(revival.getId());
        apply(item, input, ownerResolver.validate(revival.getId(), input.owner()));
        return dto(repository.saveAndFlush(item), locale);
    }

    @Transactional
    public InventoryItemDto update(final Long id, final Long version, final Input input, final Locale locale) {
        final InventoryItem item = changeable(callerService.current(), id, version);

        if (item.isRemoved()) {
            throw new BadRequestException("error.inventory.removed");
        }
        AuditContext.operation("inventoryItemUpdate");
        apply(item, input, ownerResolver.change(item.getRevivalId(), item.getOwner(),
                input.owner() == null ? new OwnerInputDto(null, null) : input.owner()));
        return dto(repository.saveAndFlush(item), locale);
    }

    @Transactional
    public InventoryItemDto remove(final Long id, final Long version, final Locale locale) {
        final Caller caller = callerService.current();
        final InventoryItem item = changeable(caller, id, version);

        if (item.isRemoved()) {
            throw new BadRequestException("error.inventory.removed");
        }
        AuditContext.operation("inventoryItemRemove");
        item.setRemovedAt(clock.instant());
        item.setRemovedBy(caller.person().getId());
        return dto(repository.saveAndFlush(item), locale);
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    @Transactional
    public InventoryItemDto restore(final Long id, final Long version, final Locale locale) {
        final InventoryItem item = changeable(callerService.current(), id, version);

        if (!item.isRemoved()) {
            throw new BadRequestException("error.inventory.notRemoved");
        }
        AuditContext.operation("inventoryItemRestore");
        item.setRemovedAt(null);
        item.setRemovedBy(null);
        return dto(repository.saveAndFlush(item), locale);
    }

    private InventoryItemDto dto(final InventoryItem item, final Locale locale) {
        return InventoryItemDto.of(item, ownerViews.toDto(item.getRevivalId(), item.getOwner(), locale));
    }

    private void apply(final InventoryItem item, final Input input, final Owner owner) {
        final int quantity = input.quantity() == null ? 1 : input.quantity();

        if (quantity < 1 || quantity > InventoryItem.MAX_QUANTITY) {
            throw new BadRequestException("error.inventory.quantity", InventoryItem.MAX_QUANTITY);
        }
        item.setName(validName(input.name()));
        item.setKind(Objects.requireNonNull(input.kind()));
        item.setQuantity(quantity);
        item.setStatus(Objects.requireNonNull(input.status()));
        item.setLocation(input.location());
        item.setLocationNote(optional(input.locationNote(), InventoryItem.LOCATION_NOTE_MAX_LENGTH, "error.inventory.locationNote"));
        item.setOwner(owner);
        item.setComment(optional(input.comment(), InventoryItem.COMMENT_MAX_LENGTH, "error.inventory.comment"));
        item.setMaterialId(validMaterial(item, input.materialId()));
    }

    /**
     * A material of the item's revival. A removed one only if the item already points at it.
     */
    private @Nullable Long validMaterial(final InventoryItem item, final @Nullable Long materialId) {
        if (materialId == null) {
            return null;
        }

        final boolean valid = materialRepository.findById(materialId)
                .filter(material -> material.getRevivalId().equals(item.getRevivalId()))
                .filter(material -> !material.isRemoved() || materialId.equals(item.getMaterialId()))
                .isPresent();

        if (!valid) {
            throw new BadRequestException("error.inventory.material");
        }
        return materialId;
    }

    private Revival visible(final Caller caller, final Long revivalId) {
        return accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);
    }

    private Revival writable(final Caller caller, final Long revivalId) {
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireWriteInventory(caller, revival);
        return revival;
    }

    private InventoryItem changeable(final Caller caller, final Long id, final Long version) {
        final InventoryItem item = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(InventoryItem.class, id));
        final Revival revival = revivalRepository.findById(item.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canSee(caller, revival) || !accessPolicy.canReadInventory(caller, revival)) {
            throw new ResourceNotFoundException(InventoryItem.class, id);
        }
        accessPolicy.requireWriteInventory(caller, revival);
        if (!Objects.equals(version, item.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return item;
    }

    /**
     * An item as entered. {@code revivalId} only on create; a null quantity is 1.
     */
    public record Input(@Nullable Long revivalId, @Nullable String name, InventoryKind kind, @Nullable Integer quantity,
                        InventoryStatus status,
                        @Nullable InventoryLocation location, @Nullable String locationNote,
                        @Nullable OwnerInputDto owner,
                        @Nullable String comment, @Nullable Long materialId) {
    }
}
