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

import nu.fgv.revival.server.material.MaterialDtos.InventoryItemDto;
import nu.fgv.revival.server.material.MaterialDtos.MaterialDto;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.participant.ParticipantDtos.Participation;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Materials and the inventory over GraphQL (material.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class MaterialGraphqlApi {

    private final ExternalIds ids;

    private final MaterialService materials;
    private final InventoryService inventory;

    public MaterialGraphqlApi(final MaterialService materials, final InventoryService inventory, final ExternalIds ids) {
        this.ids = ids;
        this.materials = materials;
        this.inventory = inventory;
    }

    @QueryMapping(name = "materials")
    public List<MaterialDto> materialList(@Argument final UUID revivalId, @Argument final @Nullable Boolean includeRemoved) {
        return materials.list(ids.idOfNullable(Revival.class, revivalId), Boolean.TRUE.equals(includeRemoved));
    }

    @MutationMapping
    public MaterialDto materialCreate(@Argument final MaterialInput input) {
        return materials.create(input.toInput(ids));
    }

    @MutationMapping
    public MaterialDto materialUpdate(@Argument final UUID id, @Argument final Long version, @Argument final MaterialInput input) {
        return materials.update(ids.idOf(Material.class, id), version, input.toInput(ids));
    }

    @MutationMapping
    public MaterialDto materialRemove(@Argument final UUID id, @Argument final Long version) {
        return materials.remove(ids.idOf(Material.class, id), version);
    }

    @MutationMapping
    public MaterialDto materialRestore(@Argument final UUID id, @Argument final Long version) {
        return materials.restore(ids.idOf(Material.class, id), version);
    }

    @BatchMapping(typeName = "Material", field = "service")
    public Map<MaterialDto, LinkServiceDto> service(final List<MaterialDto> list) {
        return materials.services(list);
    }

    @BatchMapping(typeName = "Material", field = "positions")
    public Map<MaterialDto, List<SpexareDirectory.Position>> positions(final List<MaterialDto> list) {
        return materials.positions(list);
    }

    @SchemaMapping(typeName = "Participation", field = "materials")
    public List<MaterialDto> participationMaterials(final Participation participation) {
        return materials.forParticipation(ids.idOf(Revival.class, participation.revival().id()));
    }

    @QueryMapping(name = "inventory")
    public List<InventoryItemDto> inventoryList(@Argument final UUID revivalId, @Argument final @Nullable Boolean includeRemoved, final Locale locale) {
        return inventory.list(ids.idOfNullable(Revival.class, revivalId), Boolean.TRUE.equals(includeRemoved), locale);
    }

    @MutationMapping
    public InventoryItemDto inventoryItemCreate(@Argument final InventoryItemInput input, final Locale locale) {
        return inventory.create(input.toInput(ids), locale);
    }

    @MutationMapping
    public InventoryItemDto inventoryItemUpdate(@Argument final UUID id, @Argument final Long version, @Argument final InventoryItemInput input,
                                                final Locale locale) {
        return inventory.update(ids.idOf(InventoryItem.class, id), version, input.toInput(ids), locale);
    }

    @MutationMapping
    public InventoryItemDto inventoryItemRemove(@Argument final UUID id, @Argument final Long version, final Locale locale) {
        return inventory.remove(ids.idOf(InventoryItem.class, id), version, locale);
    }

    @MutationMapping
    public InventoryItemDto inventoryItemRestore(@Argument final UUID id, @Argument final Long version, final Locale locale) {
        return inventory.restore(ids.idOf(InventoryItem.class, id), version, locale);
    }

    @BatchMapping(typeName = "InventoryItem", field = "material")
    public Map<InventoryItemDto, MaterialDto> material(final List<InventoryItemDto> items) {
        final Map<UUID, MaterialDto> found = ids.byUuid(Material.class, materials.byIds(ids.idsOf(Material.class, items.stream().map(InventoryItemDto::materialId).filter(Objects::nonNull).distinct().toList())));
        final Map<InventoryItemDto, MaterialDto> result = new HashMap<>();

        items.stream().filter(item -> item.materialId() != null && found.containsKey(item.materialId()))
                .forEach(item -> result.put(item, found.get(item.materialId())));
        return result;
    }

    public record MaterialInput(@Nullable UUID revivalId, @Nullable String title, @Nullable String url,
                                @Nullable String serviceId,
                                MaterialCategory category, MaterialVisibility visibility,
                                @Nullable List<String> positionIds,
                                @Nullable String comment) {

        MaterialService.Input toInput(final ExternalIds ids) {
            return new MaterialService.Input(ids.idOfNullable(Revival.class, revivalId), title, url, serviceId, category, visibility, positionIds, comment);
        }
    }

    public record InventoryItemInput(@Nullable UUID revivalId, @Nullable String name, InventoryKind kind,
                                     @Nullable Integer quantity,
                                     InventoryStatus status, @Nullable InventoryLocation location,
                                     @Nullable String locationNote,
                                     @Nullable OwnerInputDto owner, @Nullable String comment,
                                     @Nullable UUID materialId) {

        InventoryService.Input toInput(final ExternalIds ids) {
            return new InventoryService.Input(ids.idOfNullable(Revival.class, revivalId), name, kind, quantity, status, location, locationNote, owner, comment, ids.idOfNullable(Material.class, materialId));
        }
    }
}
