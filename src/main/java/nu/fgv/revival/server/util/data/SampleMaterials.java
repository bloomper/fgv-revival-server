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
package nu.fgv.revival.server.util.data;

import nu.fgv.revival.server.material.InventoryItem;
import nu.fgv.revival.server.material.InventoryItemRepository;
import nu.fgv.revival.server.material.InventoryKind;
import nu.fgv.revival.server.material.InventoryLocation;
import nu.fgv.revival.server.material.InventoryStatus;
import nu.fgv.revival.server.material.Material;
import nu.fgv.revival.server.material.MaterialCategory;
import nu.fgv.revival.server.material.MaterialRepository;
import nu.fgv.revival.server.material.MaterialVisibility;
import nu.fgv.revival.server.member.Owner;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Sample materials and inventory: a material in each kind of place, FGV's applications and services such as
 * Google Drive, one for a single position when Spexregister has positions, and a few inventory items with owners and
 * statuses; one of each removed. The links are fictitious. Goes through the
 * repositories, so the rows are audited as usual.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
class SampleMaterials {

    private final MaterialRepository materialRepository;
    private final InventoryItemRepository inventoryRepository;

    SampleMaterials(final MaterialRepository materialRepository, final InventoryItemRepository inventoryRepository) {
        this.materialRepository = materialRepository;
        this.inventoryRepository = inventoryRepository;
    }

    void insert(final long revivalId, final long directeur, final SamplePositions positions) {
        final Material script = material(revivalId, "Manus", "https://docs.google.com/document/d/exempel-manus", "GOOGLE_DRIVE",
                MaterialCategory.SCRIPT, MaterialVisibility.PARTICIPANTS, "Senaste versionen, med strykningarna från första läsningen");

        material(revivalId, "Noter till sångerna", "https://latarkiv.example.org/spex/exempel", "LATARKIV", MaterialCategory.SHEET_MUSIC,
                MaterialVisibility.PARTICIPANTS, null);
        material(revivalId, "Originaluppsättningen på film", "https://spexflix.example.org/spex/exempel", "SPEXFLIX", MaterialCategory.VIDEO,
                MaterialVisibility.PARTICIPANTS, null);
        material(revivalId, "Manus från originaluppsättningen", "https://manusarkiv.example.org/spex/exempel", "MANUSARKIV", MaterialCategory.SCRIPT,
                MaterialVisibility.STEERING_GROUP, null);
        material(revivalId, "Repetitionsfilmer", "https://youtu.be/exempel", "YOUTUBE", MaterialCategory.RECORDING, MaterialVisibility.PARTICIPANTS,
                "Dansnumren, filmade på rephelg 1");
        material(revivalId, "Affisch, original", "https://www.dropbox.com/s/exempel/affisch.pdf", "DROPBOX", MaterialCategory.POSTER,
                MaterialVisibility.STEERING_GROUP, null);
        material(revivalId, "Programblad, utkast", "https://1drv.ms/w/exempel", "ONEDRIVE", MaterialCategory.PROGRAMME, MaterialVisibility.STEERING_GROUP,
                null);
        if (positions.actorTaskId() != null) {
            final Material choreography = material(revivalId, "Koreografi, ensemblen", "https://youtu.be/exempel-koreografi", "YOUTUBE",
                    MaterialCategory.RECORDING, MaterialVisibility.POSITIONS, "Bara för dem som står på scen");

            choreography.getPositions().add(positions.actorTaskId());
            materialRepository.saveAndFlush(choreography);
        }

        final Material old = material(revivalId, "Manus, gammal version", "https://docs.google.com/document/d/exempel-manus-v1", "GOOGLE_DRIVE",
                MaterialCategory.SCRIPT, MaterialVisibility.PARTICIPANTS, null);

        old.setRemovedAt(Instant.now().minus(Duration.ofDays(10)));
        old.setRemovedBy(directeur);
        materialRepository.saveAndFlush(old);

        item(revivalId, "Manus, utskrivna", InventoryKind.SCRIPT, 30, InventoryStatus.HAVE, InventoryLocation.KEMIFORRADET, "Hylla 3", null,
                script.getId());
        item(revivalId, "Kungakrona", InventoryKind.PROPS, 1, InventoryStatus.BUILD, null, null, Owner.member(directeur), null);
        item(revivalId, "Peruker", InventoryKind.WIG, 6, InventoryStatus.BORROW, InventoryLocation.PRIVATE, null, Owner.named("Kim Kostymsdotter"),
                null);
        item(revivalId, "Fond: slottet", InventoryKind.BACKDROP, 1, InventoryStatus.MISSING, InventoryLocation.V_FORRADET, null, null, null);
        final InventoryItem microphones = item(revivalId, "Trådlösa mikrofoner", InventoryKind.TECHNICAL, 8, InventoryStatus.BUY, null, null, null, null);

        microphones.setComment("Offert från två firmor, beslut på nästa möte");
        inventoryRepository.saveAndFlush(microphones);

        final InventoryItem cape = item(revivalId, "Mantel", InventoryKind.PROPS, 1, InventoryStatus.HAVE, InventoryLocation.RUNAN, null, null, null);

        cape.setRemovedAt(Instant.now().minus(Duration.ofDays(4)));
        cape.setRemovedBy(directeur);
        inventoryRepository.saveAndFlush(cape);
    }

    private Material material(final long revivalId, final String title, final String url, final String serviceId, final MaterialCategory category,
                              final MaterialVisibility visibility, final @Nullable String comment) {
        final Material material = new Material();

        material.setRevivalId(revivalId);
        material.setTitle(title);
        material.setUrl(url);
        material.setServiceId(serviceId);
        material.setCategory(category);
        material.setVisibility(visibility);
        material.setComment(comment);
        return materialRepository.saveAndFlush(material);
    }

    private InventoryItem item(final long revivalId, final String name, final InventoryKind kind, final int quantity, final InventoryStatus status,
                               final @Nullable InventoryLocation location, final @Nullable String locationNote, final @Nullable Owner owner,
                               final @Nullable Long materialId) {
        final InventoryItem item = new InventoryItem();

        item.setRevivalId(revivalId);
        item.setName(name);
        item.setKind(kind);
        item.setQuantity(quantity);
        item.setStatus(status);
        item.setLocation(location);
        item.setLocationNote(locationNote);
        item.setOwner(owner);
        item.setMaterialId(materialId);
        return inventoryRepository.saveAndFlush(item);
    }
}
