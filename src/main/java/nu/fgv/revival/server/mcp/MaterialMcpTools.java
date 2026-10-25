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
package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.material.InventoryItem;
import nu.fgv.revival.server.material.InventoryKind;
import nu.fgv.revival.server.material.InventoryLocation;
import nu.fgv.revival.server.material.InventoryService;
import nu.fgv.revival.server.material.InventoryStatus;
import nu.fgv.revival.server.material.LinkServiceCatalog;
import nu.fgv.revival.server.material.LinkServiceDto;
import nu.fgv.revival.server.material.Material;
import nu.fgv.revival.server.material.MaterialCategory;
import nu.fgv.revival.server.material.MaterialDtos.InventoryItemDto;
import nu.fgv.revival.server.material.MaterialDtos.MaterialDto;
import nu.fgv.revival.server.material.MaterialService;
import nu.fgv.revival.server.material.MaterialVisibility;
import nu.fgv.revival.server.participant.ParticipantService;
import nu.fgv.revival.server.spex.SpexareDirectory;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * A revival's materials and inventory over MCP. Materials are links only: nothing is uploaded or fetched. Items are
 * removed and restored, never deleted.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class MaterialMcpTools {

    private final MaterialService materialService;
    private final InventoryService inventoryService;
    private final LinkServiceCatalog linkServices;
    private final ParticipantService participantService;
    private final McpToolSupport support;

    public MaterialMcpTools(final MaterialService materialService, final InventoryService inventoryService, final LinkServiceCatalog linkServices,
                            final ParticipantService participantService, final McpToolSupport support) {
        this.materialService = materialService;
        this.inventoryService = inventoryService;
        this.linkServices = linkServices;
        this.participantService = participantService;
        this.support = support;
    }

    private static Inventory toMcp(final InventoryItemDto item) {
        return new Inventory(item.id(), item.name(), item.kind(), item.quantity(), item.status(), item.location(), item.locationNote(),
                McpOwner.of(item.owner()), item.comment(), item.materialId(), item.removed());
    }

    private MaterialDto material(final Long revivalId, final Long materialId) {
        return McpToolSupport.byId(materialService.list(revivalId, true), MaterialDto::id, materialId, Material.class);
    }

    private InventoryItemDto item(final Long revivalId, final Long itemId) {
        return McpToolSupport.byId(inventoryService.list(revivalId, true, Locale.ENGLISH), InventoryItemDto::id, itemId, InventoryItem.class);
    }

    @McpTool(name = "list_materials",
            description = "List a revival's materials: links to the script, sheet music, recordings and the like, with category, who sees them (the steering group, all participants or some positions), the service the link is on, and comment.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<MaterialDto> listMaterials(
            @McpToolParam(description = "A revival whose materials you can read") final Long revivalId,
            @McpToolParam(required = false, description = "Also removed ones") final @Nullable Boolean includeRemoved) {
        return support.call("list_materials", () -> new McpToolSupport.ListResult<>(materialService.list(revivalId, Boolean.TRUE.equals(includeRemoved))));
    }

    @McpTool(name = "create_material",
            description = "Add a link to a revival's materials. The service it is on is recognised from the link unless given.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public MaterialDto createMaterial(
            @McpToolParam(description = "A revival whose materials you can change") final Long revivalId,
            @McpToolParam(description = "Its title") final String title,
            @McpToolParam(description = "The link, http or https") final String url,
            @McpToolParam(description = "SCRIPT, SHEET_MUSIC, RECORDING, VIDEO, PROGRAMME, POSTER, PHOTO or OTHER") final MaterialCategory category,
            @McpToolParam(description = "STEERING_GROUP, PARTICIPANTS or POSITIONS") final MaterialVisibility visibility,
            @McpToolParam(required = false, description = "POSITIONS: the positions' ids from list_positions") final @Nullable List<String> positionIds,
            @McpToolParam(required = false, description = "A link service, by id from list_link_services") final @Nullable String serviceId,
            @McpToolParam(required = false, description = "A comment") final @Nullable String comment) {
        return support.call("create_material", () -> materialService.create(new MaterialService.Input(revivalId, title, url, serviceId, category, visibility,
                positionIds, comment)));
    }

    @McpTool(name = "update_material",
            description = "Change a material. Give every field; list_materials shows the current ones.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public MaterialDto updateMaterial(
            @McpToolParam(description = "A revival whose materials you can change") final Long revivalId,
            @McpToolParam(description = "The material, by id") final Long materialId,
            @McpToolParam(description = "Its title") final String title,
            @McpToolParam(description = "The link") final String url,
            @McpToolParam(description = "As in create_material") final MaterialCategory category,
            @McpToolParam(description = "As in create_material") final MaterialVisibility visibility,
            @McpToolParam(required = false, description = "POSITIONS: the positions' ids") final @Nullable List<String> positionIds,
            @McpToolParam(required = false, description = "A link service, by id") final @Nullable String serviceId,
            @McpToolParam(required = false, description = "A comment") final @Nullable String comment) {
        return support.call("update_material", () -> materialService.update(materialId, material(revivalId, materialId).version(),
                new MaterialService.Input(revivalId, title, url, serviceId, category, visibility, positionIds, comment)));
    }

    @McpTool(name = "set_material_removed",
            description = "Remove a material from the list, or restore a removed one. Nothing is deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public MaterialDto setMaterialRemoved(
            @McpToolParam(description = "A revival whose materials you can change") final Long revivalId,
            @McpToolParam(description = "The material, by id") final Long materialId,
            @McpToolParam(description = "true to remove, false to restore") final Boolean removed) {
        return support.call("set_material_removed", () -> {
            final Long version = material(revivalId, materialId).version();

            return removed ? materialService.remove(materialId, version) : materialService.restore(materialId, version);
        });
    }

    @McpTool(name = "list_link_services",
            description = "List the services materials link to, such as Spexflix or Google Drive, with the hosts each is recognised by.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<LinkServiceDto> listLinkServices() {
        return support.call("list_link_services", () -> new McpToolSupport.ListResult<>(linkServices.list(false).stream().map(LinkServiceDto::of).toList()));
    }

    @McpTool(name = "list_positions",
            description = "List the positions in a revival, Spexregister's tasks such as Orkester or Scen, by id and name, e.g. to show a material to some positions.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = true))
    public McpToolSupport.ListResult<SpexareDirectory.Position> listPositions() {
        return support.call("list_positions", () -> new McpToolSupport.ListResult<>(participantService.positions()));
    }

    @McpTool(name = "list_inventory",
            description = "List a revival's inventory: scripts, props, costumes and the like, how many, whether they are had, missing or to borrow, buy or build, where they are and who holds them.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<Inventory> listInventory(
            @McpToolParam(description = "A revival whose inventory you can read") final Long revivalId,
            @McpToolParam(required = false, description = "Also removed ones") final @Nullable Boolean includeRemoved) {
        return support.call("list_inventory", () -> new McpToolSupport.ListResult<>(inventoryService.list(revivalId, Boolean.TRUE.equals(includeRemoved),
                Locale.ENGLISH).stream().map(MaterialMcpTools::toMcp).toList()));
    }

    @McpTool(name = "create_inventory_item",
            description = "Add something to a revival's inventory.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Inventory createInventoryItem(
            @McpToolParam(description = "A revival whose inventory you can change") final Long revivalId,
            @McpToolParam(description = "What it is") final String name,
            @McpToolParam(description = "SCRIPT, SHEET_MUSIC, PROPS, COSTUME, WIG, BACKDROP, TECHNICAL or OTHER") final InventoryKind kind,
            @McpToolParam(description = "HAVE, MISSING, BORROW, BUY or BUILD") final InventoryStatus status,
            @McpToolParam(required = false, description = "How many, default 1") final @Nullable Integer quantity,
            @McpToolParam(required = false, description = "KEMIFORRADET, V_FORRADET, RUNAN, PRIVATE or OTHER") final @Nullable InventoryLocation location,
            @McpToolParam(required = false, description = "Where exactly") final @Nullable String locationNote,
            @McpToolParam(required = false, description = "Who holds it: a display name or me; anyone outside the steering group as free text") final @Nullable String owner,
            @McpToolParam(required = false, description = "A comment") final @Nullable String comment,
            @McpToolParam(required = false, description = "The material it belongs to, by id") final @Nullable Long materialId) {
        return support.call("create_inventory_item", () -> toMcp(inventoryService.create(new InventoryService.Input(revivalId, name, kind, quantity, status,
                location, locationNote, support.owner(revivalId, owner), comment, materialId), Locale.ENGLISH)));
    }

    @McpTool(name = "update_inventory_item",
            description = "Change an inventory item. Give every field; list_inventory shows the current ones.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Inventory updateInventoryItem(
            @McpToolParam(description = "A revival whose inventory you can change") final Long revivalId,
            @McpToolParam(description = "The item, by id") final Long itemId,
            @McpToolParam(description = "What it is") final String name,
            @McpToolParam(description = "As in create_inventory_item") final InventoryKind kind,
            @McpToolParam(description = "As in create_inventory_item") final InventoryStatus status,
            @McpToolParam(required = false, description = "How many") final @Nullable Integer quantity,
            @McpToolParam(required = false, description = "As in create_inventory_item") final @Nullable InventoryLocation location,
            @McpToolParam(required = false, description = "Where exactly") final @Nullable String locationNote,
            @McpToolParam(required = false, description = "As in create_inventory_item") final @Nullable String owner,
            @McpToolParam(required = false, description = "A comment") final @Nullable String comment,
            @McpToolParam(required = false, description = "The material it belongs to, by id") final @Nullable Long materialId) {
        return support.call("update_inventory_item", () -> toMcp(inventoryService.update(itemId, item(revivalId, itemId).version(),
                new InventoryService.Input(revivalId, name, kind, quantity, status, location, locationNote, support.owner(revivalId, owner), comment,
                        materialId), Locale.ENGLISH)));
    }

    @McpTool(name = "set_inventory_item_removed",
            description = "Remove an inventory item from the list, or restore a removed one. Nothing is deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Inventory setInventoryItemRemoved(
            @McpToolParam(description = "A revival whose inventory you can change") final Long revivalId,
            @McpToolParam(description = "The item, by id") final Long itemId,
            @McpToolParam(description = "true to remove, false to restore") final Boolean removed) {
        return support.call("set_inventory_item_removed", () -> {
            final Long version = item(revivalId, itemId).version();

            return toMcp(removed ? inventoryService.remove(itemId, version, Locale.ENGLISH) : inventoryService.restore(itemId, version, Locale.ENGLISH));
        });
    }

    /**
     * An inventory item with who holds it by name.
     */
    public record Inventory(Long id, String name, InventoryKind kind, int quantity, InventoryStatus status, @Nullable InventoryLocation location,
                            @Nullable String locationNote, @Nullable McpOwner owner, @Nullable String comment, @Nullable Long materialId,
                            boolean removed) {
    }
}
