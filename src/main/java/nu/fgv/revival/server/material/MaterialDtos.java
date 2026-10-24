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

import nu.fgv.revival.server.member.OwnerDto;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The GraphQL types of materials and the inventory (material.graphqls). A material's service and positions, and an
 * item's material, are resolved by {@link MaterialGraphqlApi}.
 *
 * @author Anders Jacobsson
 * @since 0.9
 */
public final class MaterialDtos {

    private MaterialDtos() {
    }

    public record MaterialDto(Long id, Long revivalId, String title, String url, @Nullable String serviceId,
                              boolean serviceChosen,
                              MaterialCategory category,
                              MaterialVisibility visibility, List<String> positionIds, @Nullable String comment,
                              boolean removed, Long version) {

        static MaterialDto of(final Material material) {
            return new MaterialDto(material.getId(), material.getRevivalId(), material.getTitle(), material.getUrl(), material.getServiceId(),
                    material.isServiceChosen(), material.getCategory(), material.getVisibility(), material.getPositions().stream().sorted().toList(), material.getComment(),
                    material.isRemoved(), material.getVersion() == null ? 0L : material.getVersion());
        }
    }

    public record InventoryItemDto(Long id, Long revivalId, String name, InventoryKind kind, int quantity,
                                   InventoryStatus status,
                                   @Nullable InventoryLocation location, @Nullable String locationNote,
                                   @Nullable OwnerDto owner,
                                   @Nullable String comment, @Nullable Long materialId, boolean removed, Long version) {

        static InventoryItemDto of(final InventoryItem item, final @Nullable OwnerDto owner) {
            return new InventoryItemDto(item.getId(), item.getRevivalId(), item.getName(), item.getKind(), item.getQuantity(), item.getStatus(),
                    item.getLocation(), item.getLocationNote(), owner, item.getComment(), item.getMaterialId(), item.isRemoved(),
                    item.getVersion() == null ? 0L : item.getVersion());
        }
    }
}
