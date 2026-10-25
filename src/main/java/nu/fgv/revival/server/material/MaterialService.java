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
import nu.fgv.revival.server.material.MaterialDtos.MaterialDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantAccess;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A revival's materials: its directeurs and steering group add, change, remove and
 * restore them while it is not archived; super admins and they read them all; a participant reads those for every
 * participant and those for their position. A link's service is the one chosen, or else recognised from its host when
 * it is saved. Never deleted.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class MaterialService {

    private static final Comparator<Material> ORDER = Comparator.comparing((Material material) -> material.getTitle().toLowerCase(Locale.ROOT))
            .thenComparing(Material::getId);

    private final MaterialRepository repository;
    private final RevivalRepository revivalRepository;
    private final LinkServiceCatalog services;
    private final SpexareDirectory directory;
    private final ParticipantAccess participantAccess;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public MaterialService(final MaterialRepository repository,
                           final RevivalRepository revivalRepository,
                           final LinkServiceCatalog services,
                           final SpexareDirectory directory,
                           final ParticipantAccess participantAccess,
                           final CallerService callerService,
                           final AccessPolicy accessPolicy,
                           final Clock clock) {
        this.repository = repository;
        this.revivalRepository = revivalRepository;
        this.services = services;
        this.directory = directory;
        this.participantAccess = participantAccess;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    private static String validTitle(final @Nullable String title) {
        final String trimmed = title == null ? "" : title.strip();

        if (trimmed.isEmpty() || trimmed.length() > Material.TITLE_MAX_LENGTH) {
            throw new BadRequestException("error.material.title", Material.TITLE_MAX_LENGTH);
        }
        return trimmed;
    }

    private static @Nullable String validComment(final @Nullable String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }

        final String trimmed = comment.strip();

        if (trimmed.length() > Material.COMMENT_MAX_LENGTH) {
            throw new BadRequestException("error.material.comment", Material.COMMENT_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * By title. Removed ones only with {@code includeRemoved}, and only for writers; a participant gets what is meant
     * for them, never removed ones.
     */
    @Transactional(readOnly = true)
    public List<MaterialDto> list(final Long revivalId, final boolean includeRemoved) {
        final Caller caller = callerService.current();
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireReadMaterials(caller, revival);
        if (accessPolicy.canReadAllMaterials(caller, revival)) {
            final boolean withRemoved = includeRemoved && accessPolicy.canWriteMaterials(caller, revival);

            return repository.findByRevivalId(revivalId).stream()
                    .filter(material -> withRemoved || !material.isRemoved())
                    .sorted(ORDER)
                    .map(MaterialDto::of)
                    .toList();
        }
        return visibleTo(caller, revival, ownParticipant(caller, revivalId));
    }

    /**
     * The materials the caller may see as a participant of the revival; none for a helper.
     */
    @Transactional(readOnly = true)
    public List<MaterialDto> forParticipation(final Long revivalId) {
        final Caller caller = callerService.current();
        final ParticipantAccess.Own own = participantAccess.own(caller, revivalId);

        return visibleTo(caller, own.revival(), own.participant());
    }

    /**
     * Materials by id. No caller check: callers have read the rows that point at them.
     */
    @Transactional(readOnly = true)
    public Map<Long, MaterialDto> byIds(final Collection<Long> ids) {
        return ids.isEmpty() ? Map.of() : repository.findAllById(Set.copyOf(ids)).stream()
                .collect(Collectors.toMap(Material::getId, MaterialDto::of));
    }

    /**
     * Each material's service: the one it holds, or else the one its link's host is recognised as now. No caller check:
     * callers have read the materials.
     */
    @Transactional(readOnly = true)
    public Map<MaterialDto, LinkServiceDto> services(final Collection<MaterialDto> materials) {
        final List<LinkService> all = services.all();
        final Map<String, LinkService> byId = all.stream().collect(Collectors.toMap(LinkService::getId, service -> service));
        final Map<MaterialDto, LinkServiceDto> result = new HashMap<>();

        for (final MaterialDto material : materials) {
            final LinkService service = material.serviceId() != null
                    ? byId.get(material.serviceId())
                    : Links.recognise(Links.host(material.url()), all).orElse(null);

            if (service != null) {
                result.put(material, LinkServiceDto.of(service));
            }
        }
        return result;
    }

    /**
     * Each material's positions as Spexregister names them; by id alone while it is unavailable. No caller check:
     * callers have read the materials.
     */
    public Map<MaterialDto, List<SpexareDirectory.Position>> positions(final Collection<MaterialDto> materials) {
        final Map<String, SpexareDirectory.Position> known = materials.stream().anyMatch(material -> !material.positionIds().isEmpty())
                ? directory.positionsOrEmpty() : Map.of();
        final Map<MaterialDto, List<SpexareDirectory.Position>> result = new HashMap<>();

        materials.forEach(material -> result.put(material, material.positionIds().stream()
                .map(id -> known.getOrDefault(id, new SpexareDirectory.Position(id, id, null, false)))
                .toList()));
        return result;
    }

    @Transactional
    public MaterialDto create(final Input input) {
        final Caller caller = callerService.current();

        if (input.revivalId() == null) {
            throw new BadRequestException("error.material.revival");
        }

        final Revival revival = writable(caller, input.revivalId());
        final Material material = new Material();

        AuditContext.operation("materialCreate");
        material.setRevivalId(revival.getId());
        apply(material, input);
        return MaterialDto.of(repository.saveAndFlush(material));
    }

    @Transactional
    public MaterialDto update(final Long id, final Long version, final Input input) {
        final Material material = changeable(callerService.current(), id, version);

        if (material.isRemoved()) {
            throw new BadRequestException("error.material.removed");
        }
        AuditContext.operation("materialUpdate");
        apply(material, input);
        return MaterialDto.of(repository.saveAndFlush(material));
    }

    @Transactional
    public MaterialDto remove(final Long id, final Long version) {
        final Caller caller = callerService.current();
        final Material material = changeable(caller, id, version);

        if (material.isRemoved()) {
            throw new BadRequestException("error.material.removed");
        }
        AuditContext.operation("materialRemove");
        material.setRemovedAt(clock.instant());
        material.setRemovedBy(caller.person().getId());
        return MaterialDto.of(repository.saveAndFlush(material));
    }

    @Transactional
    public MaterialDto restore(final Long id, final Long version) {
        final Material material = changeable(callerService.current(), id, version);

        if (!material.isRemoved()) {
            throw new BadRequestException("error.material.notRemoved");
        }
        AuditContext.operation("materialRestore");
        material.setRemovedAt(null);
        material.setRemovedBy(null);
        return MaterialDto.of(repository.saveAndFlush(material));
    }

    private void apply(final Material material, final Input input) {
        final String url = Links.requireHttps(input.url(), Material.URL_MAX_LENGTH);

        material.setTitle(validTitle(input.title()));
        material.setUrl(url);
        if (input.serviceId() != null && !input.serviceId().isBlank()) {
            final String serviceId = input.serviceId().strip();

            services.requireAssignable(List.of(serviceId), material.getServiceId() == null ? List.of() : List.of(material.getServiceId()));
            material.setServiceId(serviceId);
            material.setServiceChosen(true);
        } else {
            material.setServiceId(services.recognise(Links.host(url)).map(LinkService::getId).orElse(null));
            material.setServiceChosen(false);
        }
        material.setCategory(Objects.requireNonNull(input.category()));
        material.setVisibility(Objects.requireNonNull(input.visibility()));

        final Set<String> positions = input.visibility() == MaterialVisibility.POSITIONS
                ? validPositions(input.positionIds(), material.getPositions())
                : Set.of();

        material.getPositions().retainAll(positions);
        material.getPositions().addAll(positions);
        material.setComment(validComment(input.comment()));
    }

    /**
     * At least one position, each a Spexregister task id. Ids the material already holds are kept without asking
     * Spexregister; new ones need it, so they are refused with SPEXREGISTER_UNAVAILABLE while it cannot be reached.
     */
    private Set<String> validPositions(final @Nullable List<String> ids, final Set<String> held) {
        final Set<String> wanted = new LinkedHashSet<>();

        if (ids != null) {
            ids.stream().filter(Objects::nonNull).map(String::strip).filter(id -> !id.isEmpty()).forEach(wanted::add);
        }
        if (wanted.isEmpty()) {
            throw new BadRequestException("error.material.positions");
        }
        if (wanted.size() > Material.MAX_POSITIONS) {
            throw new BadRequestException("error.material.positions.tooMany", Material.MAX_POSITIONS);
        }

        final Set<String> added = new HashSet<>(wanted);

        added.removeAll(held);
        if (!added.isEmpty()) {
            final Set<String> known = directory.positions().stream().map(SpexareDirectory.Position::id).collect(Collectors.toSet());

            if (!known.containsAll(added)) {
                throw new BadRequestException("error.material.position.unknown");
            }
        }
        return wanted;
    }

    private List<MaterialDto> visibleTo(final Caller caller, final Revival revival, final @Nullable Participant participant) {
        if (participant != null && participant.getKind() == ParticipantKind.HELPER) {
            return List.of();
        }

        final String position = participant == null ? null : participant.getPositionTaskId();

        return repository.findByRevivalId(revival.getId()).stream()
                .filter(material -> !material.isRemoved())
                .filter(material -> accessPolicy.canSeeMaterial(caller, revival, material.getVisibility(), material.getPositions(), position))
                .sorted(ORDER)
                .map(MaterialDto::of)
                .toList();
    }

    /**
     * The caller's participant row in the revival, if they have one.
     */
    private @Nullable Participant ownParticipant(final Caller caller, final Long revivalId) {
        try {
            return participantAccess.own(caller, revivalId).participant();
        } catch (final ResourceNotFoundException _) {
            return null;
        }
    }

    private Revival visible(final Caller caller, final Long revivalId) {
        return accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);
    }

    private Revival writable(final Caller caller, final Long revivalId) {
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireWriteMaterials(caller, revival);
        return revival;
    }

    /**
     * A material of a revival whose materials the caller reads in full, writable by them, at the version they saw.
     * Others are told it is missing.
     */
    private Material changeable(final Caller caller, final Long id, final Long version) {
        final Material material = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(Material.class, id));
        final Revival revival = revivalRepository.findById(material.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canSee(caller, revival) || !accessPolicy.canReadAllMaterials(caller, revival)) {
            throw new ResourceNotFoundException(Material.class, id);
        }
        accessPolicy.requireWriteMaterials(caller, revival);
        if (!Objects.equals(version, material.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return material;
    }

    /**
     * A material as entered. {@code revivalId} only on create; {@code serviceId} null to recognise the service from the
     * link; {@code positionIds} only for {@link MaterialVisibility#POSITIONS}.
     */
    public record Input(@Nullable Long revivalId, @Nullable String title, @Nullable String url,
                        @Nullable String serviceId,
                        MaterialCategory category, MaterialVisibility visibility, @Nullable List<String> positionIds,
                        @Nullable String comment) {
    }
}
