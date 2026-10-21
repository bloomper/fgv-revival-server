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

package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.spex.SpexDto;
import nu.fgv.revival.server.spex.SpexService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import static nu.fgv.revival.server.revival.RevivalMapper.REVIVAL_MAPPER;
import static org.springframework.util.StringUtils.hasText;

/**
 * Revivals and their lifecycle. Every method resolves the caller and asks {@link AccessPolicy} before acting
 * (constitution III). GraphQL and MCP both call {@link #findVisible}, so they cannot disagree (FR-025).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Service
public class RevivalService {

    static final int MIN_YEAR = 1948;
    static final int MAX_YEAR = 2100;
    static final int MAX_VENUES = 20;

    private static final Sort NEWEST_FIRST = Sort.by(
            Sort.Order.desc("premiereDate").nullsLast(),
            Sort.Order.desc("year"),
            Sort.Order.desc("id"));

    private final RevivalRepository repository;
    private final RevivalStatusChangeRepository statusChangeRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final SpexService spexService;

    public RevivalService(final RevivalRepository repository,
                          final RevivalStatusChangeRepository statusChangeRepository,
                          final CallerService callerService,
                          final AccessPolicy accessPolicy,
                          final SpexService spexService) {
        this.repository = repository;
        this.statusChangeRepository = statusChangeRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.spexService = spexService;
    }

    private RevivalDto toDto(final Caller caller, final Revival revival, final SpexDto spex) {
        return REVIVAL_MAPPER.toDto(revival, spex, accessPolicy.permissions(caller, revival));
    }

    private RevivalDto toDto(final Caller caller, final Revival revival) {
        return toDto(caller, revival, spexService.findTitles(List.of(revival.getSpexId())).get(revival.getSpexId()));
    }

    private Revival visible(final Caller caller, final Long id) {
        return accessPolicy.requireVisible(caller, repository.findById(id).orElse(null), id);
    }

    /**
     * The revivals the caller can see (FR-015), newest premiere first, then year descending. Spex titles come from one
     * cached list.
     */
    @Transactional(readOnly = true)
    public List<RevivalDto> findVisible(final @Nullable Collection<RevivalStatus> statuses) {
        final Caller caller = callerService.current();
        Specification<Revival> specification = accessPolicy.visibilityPredicate(caller);

        if (statuses != null && !statuses.isEmpty()) {
            specification = specification.and((root, query, cb) -> root.get("status").in(statuses));
        }

        final List<Revival> revivals = repository.findAll(specification, NEWEST_FIRST);
        final Map<Long, SpexDto> spexById = spexService.findTitles(revivals.stream().map(Revival::getSpexId).toList());

        return revivals.stream().map(revival -> toDto(caller, revival, spexById.get(revival.getSpexId()))).toList();
    }

    /**
     * A revival the caller can see. Not visible and missing give the same error (FR-017).
     */
    @Transactional(readOnly = true)
    public RevivalDto findById(final Long id) {
        final Caller caller = callerService.current();

        return toDto(caller, visible(caller, id));
    }

    @Transactional(readOnly = true)
    public List<RevivalStatusChangeHistoryDto> statusHistory(final Long id) {
        final Caller caller = callerService.current();

        visible(caller, id);
        return statusChangeRepository.findByRevivalIdOrderByChangedAtDescIdDesc(id).stream()
                .map(REVIVAL_MAPPER::toHistoryDto)
                .toList();
    }

    @Transactional
    public RevivalDto create(final RevivalCreateDto dto) {
        final Caller caller = callerService.current();

        accessPolicy.requireCreateRevival(caller);
        AuditContext.operation("revivalCreate");

        final Revival revival = new Revival();

        revival.setYear(validYear(dto.year()));
        revival.setWorkingTitle(validWorkingTitle(dto.workingTitle()));
        spexService.requireOriginal(dto.spexId());
        revival.setSpexId(dto.spexId());
        revival.setStatus(RevivalStatus.PROPOSED);

        return toDto(caller, repository.saveAndFlush(revival));
    }

    @Transactional
    public RevivalDto update(final RevivalUpdateDto dto) {
        final Caller caller = callerService.current();
        final Revival revival = visible(caller, dto.id());

        if (dto.changesCore()) {
            accessPolicy.requireEditCore(caller, revival);
        }
        if (dto.changesDetails()) {
            accessPolicy.requireEditDetails(caller, revival);
        }
        if (!dto.changesCore() && !dto.changesDetails()
                && !accessPolicy.canEditCore(caller, revival) && !accessPolicy.canEditDetails(caller, revival)) {
            throw new AccessDeniedException("Not permitted");
        }
        if (!Objects.equals(dto.version(), revival.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        AuditContext.operation("revivalUpdate");

        if (dto.year() != null) {
            revival.setYear(validYear(dto.year()));
        }
        if (dto.workingTitle() != null) {
            revival.setWorkingTitle(validWorkingTitle(dto.workingTitle()));
        }
        if (dto.spexId() != null && !dto.spexId().equals(revival.getSpexId())) {
            spexService.requireOriginal(dto.spexId());
            revival.setSpexId(dto.spexId());
        }
        if (Boolean.TRUE.equals(dto.clearPremiereDate())) {
            revival.setPremiereDate(null);
        } else if (dto.premiereDate() != null) {
            revival.setPremiereDate(dto.premiereDate());
        }
        if (dto.showDates() != null) {
            revival.getShowDates().clear();
            revival.getShowDates().addAll(new TreeSet<>(dto.showDates()));
        }
        if (dto.venues() != null) {
            revival.getVenues().clear();
            revival.getVenues().addAll(validVenues(dto.venues()));
        }
        validateDates(revival);

        return toDto(caller, repository.saveAndFlush(revival));
    }

    /**
     * Changes the status. The {@code from} the caller saw must still be the stored status (research R12); then a super
     * admin may set any status and a directeur only the next step (FR-013, FR-014). The change is recorded (FR-018).
     */
    @Transactional
    public RevivalDto changeStatus(final RevivalStatusChangeDto dto) {
        final Caller caller = callerService.current();
        final Revival revival = visible(caller, dto.id());

        if (revival.getStatus() != dto.from()) {
            throw new ConflictException("error.revival.status.conflict");
        }
        accessPolicy.requireStatusChange(caller, revival, dto.to());
        AuditContext.operation("revivalStatusChange");

        final RevivalStatusChange change = new RevivalStatusChange();

        change.setRevivalId(revival.getId());
        change.setFromStatus(revival.getStatus());
        change.setToStatus(dto.to());
        change.setChangedBy(caller.person().getId());
        change.setChangedAt(Instant.now());
        revival.setStatus(dto.to());

        final Revival saved = repository.saveAndFlush(revival);

        statusChangeRepository.saveAndFlush(change);
        return toDto(caller, saved);
    }

    private static Integer validYear(final @Nullable Integer year) {
        if (year == null || year < MIN_YEAR || year > MAX_YEAR) {
            throw new BadRequestException("error.revival.year", MIN_YEAR, MAX_YEAR);
        }
        return year;
    }

    private static String validWorkingTitle(final @Nullable String workingTitle) {
        if (!hasText(workingTitle)) {
            throw new BadRequestException("error.revival.workingTitle.blank");
        }
        if (workingTitle.trim().length() > 255) {
            throw new BadRequestException("error.revival.workingTitle.tooLong", 255);
        }
        return workingTitle.trim();
    }

    private static List<String> validVenues(final List<String> venues) {
        if (venues.size() > MAX_VENUES) {
            throw new BadRequestException("error.revival.venues.tooMany", MAX_VENUES);
        }
        if (venues.stream().anyMatch(venue -> !hasText(venue) || venue.trim().length() > 255)) {
            throw new BadRequestException("error.revival.venues.blank");
        }
        return venues.stream().map(String::trim).toList();
    }

    private static void validateDates(final Revival revival) {
        final LocalDate premiere = revival.getPremiereDate();

        if (premiere != null && premiere.getYear() != revival.getYear()) {
            throw new BadRequestException("error.revival.premiereDate.year", revival.getYear());
        }
        if (!revival.getShowDates().isEmpty() && premiere == null) {
            throw new BadRequestException("error.revival.showDates.withoutPremiere");
        }
        if (premiere != null && revival.getShowDates().stream().anyMatch(date -> date.isBefore(premiere))) {
            throw new BadRequestException("error.revival.showDates.beforePremiere");
        }
    }
}
