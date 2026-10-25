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
import nu.fgv.revival.server.guide.GuideVersionSummaryDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.spex.SpexDto;
import nu.fgv.revival.server.spex.SpexService;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static nu.fgv.revival.server.revival.RevivalMapper.REVIVAL_MAPPER;
import static org.springframework.util.StringUtils.hasText;

/**
 * Revivals and their lifecycle. Every method resolves the caller and asks {@link AccessPolicy} before acting.
 * GraphQL and MCP both call {@link #findVisible}, so they cannot disagree.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class RevivalService {

    static final int MIN_YEAR = 1948;
    static final int MAX_YEAR = 2100;
    static final int MAX_SHOWS = 50;
    private static final Pattern SHOW_TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private static final Sort NEWEST_FIRST = Sort.by(
            Sort.Order.desc("premiereDate").nullsLast(),
            Sort.Order.desc("year"),
            Sort.Order.desc("id"));

    private final RevivalRepository repository;
    private final RevivalStatusChangeRepository statusChangeRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final SpexService spexService;
    private final RevivalGuideVersions guideVersions;
    private final ApplicationEventPublisher events;
    private final RevivalDeletion deletion;
    private final RevivalAnonymization anonymization;
    private final Clock clock;

    public RevivalService(final RevivalRepository repository,
                          final RevivalStatusChangeRepository statusChangeRepository,
                          final CallerService callerService,
                          final AccessPolicy accessPolicy,
                          final SpexService spexService,
                          final RevivalGuideVersions guideVersions,
                          final ApplicationEventPublisher events,
                          final RevivalDeletion deletion,
                          final RevivalAnonymization anonymization,
                          final Clock clock) {
        this.repository = repository;
        this.statusChangeRepository = statusChangeRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.spexService = spexService;
        this.guideVersions = guideVersions;
        this.events = events;
        this.deletion = deletion;
        this.anonymization = anonymization;
        this.clock = clock;
    }

    /**
     * Within 1948–2100 and not before the original spex.
     */
    private static Integer validYear(final @Nullable Integer year, final SpexDto spex) {
        if (year == null || year < MIN_YEAR || year > MAX_YEAR) {
            throw new BadRequestException("error.revival.year", MIN_YEAR, MAX_YEAR);
        }
        if (spex.year() != null && year < spex.year()) {
            throw new BadRequestException("error.revival.year.beforeOriginal", spex.year());
        }
        return year;
    }

    /**
     * At most {@value #MAX_SHOWS}, times as {@code HH:mm} and venues trimmed with blank as not decided, and no date,
     * time and venue twice.
     */
    private static List<RevivalShowDto> validShows(final List<RevivalShowDto> shows) {
        if (shows.size() > MAX_SHOWS) {
            throw new BadRequestException("error.revival.shows.tooMany", MAX_SHOWS);
        }

        final List<RevivalShowDto> valid = shows.stream()
                .map(show -> new RevivalShowDto(show.date(), validTime(show.time()), hasText(show.venue()) ? show.venue().trim() : null))
                .toList();

        if (valid.stream().anyMatch(show -> show.venue() != null && show.venue().length() > RevivalShow.VENUE_MAX_LENGTH)) {
            throw new BadRequestException("error.revival.shows.venueTooLong", RevivalShow.VENUE_MAX_LENGTH);
        }
        if (new HashSet<>(valid).size() != valid.size()) {
            throw new BadRequestException("error.revival.shows.duplicate");
        }
        return valid;
    }

    /**
     * {@code HH:mm} from 00:00 to 23:59, trimmed, or null when blank.
     */
    private static @Nullable String validTime(final @Nullable String time) {
        if (!hasText(time)) {
            return null;
        }

        final String trimmed = time.trim();

        if (!SHOW_TIME.matcher(trimmed).matches()) {
            throw new BadRequestException("error.revival.shows.timeInvalid");
        }
        return trimmed;
    }

    private static void validateDates(final Revival revival) {
        final LocalDate premiere = revival.getPremiereDate();

        if (premiere != null && premiere.getYear() != revival.getYear()) {
            throw new BadRequestException("error.revival.premiereDate.year", revival.getYear());
        }
        if (!revival.getShows().isEmpty() && premiere == null) {
            throw new BadRequestException("error.revival.shows.withoutPremiere");
        }
        if (premiere != null && revival.getShows().stream().anyMatch(show -> show.getDate().isBefore(premiere))) {
            throw new BadRequestException("error.revival.shows.beforePremiere");
        }
    }

    private RevivalDto toDto(final Caller caller, final Revival revival, final SpexDto spex, final List<GuideVersionSummaryDto> published) {
        return REVIVAL_MAPPER.toDto(revival, spex, accessPolicy.permissions(caller, revival, published));
    }

    private RevivalDto toDto(final Caller caller, final Revival revival) {
        return toDto(caller, revival, spexService.findTitles(List.of(revival.getSpexId())).get(revival.getSpexId()), guideVersions.published());
    }

    private Revival visible(final Caller caller, final Long id) {
        return accessPolicy.requireVisible(caller, repository.findById(id).orElse(null), id);
    }

    /**
     * The revivals the caller can see, newest premiere first, then year descending. Spex titles come from one
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
        final List<GuideVersionSummaryDto> published = guideVersions.published();

        return revivals.stream().map(revival -> toDto(caller, revival, spexById.get(revival.getSpexId()), published)).toList();
    }

    /**
     * Moves a revival to another published version. The version the caller saw must still be the pinned one,
     * as with status changes. A directeur moves only forward; a super admin to any version.
     */
    @Transactional
    public RevivalDto changeGuideVersion(final Long id, final @Nullable Long fromVersionId, final Long toVersionId) {
        final Caller caller = callerService.current();
        final Revival revival = visible(caller, id);

        if (!Objects.equals(revival.getGuideVersionId(), fromVersionId)) {
            throw new ConflictException("error.guide.version.conflict");
        }

        final Map<Long, GuideVersionSummaryDto> published = guideVersions.published().stream()
                .collect(java.util.stream.Collectors.toMap(GuideVersionSummaryDto::id, version -> version));
        final GuideVersionSummaryDto target = published.get(toVersionId);

        if (target == null || target.publishedAt() == null) {
            throw new BadRequestException("error.guide.version.notPublished");
        }

        final GuideVersionSummaryDto current = fromVersionId == null ? null : published.get(fromVersionId);

        accessPolicy.requireGuideVersionChange(caller, revival, current == null ? null : current.publishedAt(), target.publishedAt());
        AuditContext.operation("revivalGuideVersionChange");
        guideVersions.change(revival, toVersionId, caller);
        return toDto(caller, repository.saveAndFlush(revival));
    }

    /**
     * Every change of a visible revival's pinned version, newest first.
     */
    @Transactional(readOnly = true)
    public List<RevivalGuideVersionChange> guideVersionHistory(final Long id) {
        final Caller caller = callerService.current();

        // Participants see the revival for their own page only
        return accessPolicy.canReadRevivalWork(caller, visible(caller, id)) ? guideVersions.history(id) : List.of();
    }

    /**
     * A revival the caller can see. Not visible and missing give the same error.
     */
    @Transactional(readOnly = true)
    public RevivalDto findById(final Long id) {
        final Caller caller = callerService.current();

        return toDto(caller, visible(caller, id));
    }

    /**
     * The poster of a revival's spex, or empty when it has none. Not visible and missing give the same error.
     */
    @Transactional(readOnly = true)
    public Optional<SpexregisterClient.Poster> poster(final Long id) {
        return spexService.poster(visible(callerService.current(), id).getSpexId());
    }

    /**
     * Newest first; empty for participants and helpers, who see the revival for their own page only.
     */
    @Transactional(readOnly = true)
    public List<RevivalStatusChangeHistoryDto> statusHistory(final Long id) {
        final Caller caller = callerService.current();

        if (!accessPolicy.canReadRevivalWork(caller, visible(caller, id))) {
            return List.of();
        }
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

        final SpexDto spex = spexService.requireOriginal(dto.spexId());
        final Integer year = validYear(dto.year(), spex);

        if (year < LocalDate.now(clock).getYear()) {
            throw new BadRequestException("error.revival.year.past", LocalDate.now(clock).getYear());
        }
        requireUnique(dto.spexId(), year, null);
        revival.setYear(year);
        revival.setSpexId(dto.spexId());
        revival.setStatus(RevivalStatus.PROPOSED);

        final Revival saved = repository.saveAndFlush(revival);

        events.publishEvent(new RevivalCreated(saved.getId()));
        guideVersions.pinToLatest(saved, caller);
        return toDto(caller, saved);
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

        if (dto.changesCore()) {
            final Long spexId = dto.spexId() != null ? dto.spexId() : revival.getSpexId();
            final SpexDto spex = spexService.requireOriginal(spexId);
            final Integer year = validYear(dto.year() != null ? dto.year() : revival.getYear(), spex);

            requireUnique(spexId, year, revival.getId());
            revival.setSpexId(spexId);
            revival.setYear(year);
        }
        if (Boolean.TRUE.equals(dto.clearPremiereDate())) {
            revival.setPremiereDate(null);
        } else if (dto.premiereDate() != null) {
            revival.setPremiereDate(dto.premiereDate());
        }
        if (dto.shows() != null) {
            replaceShows(revival, validShows(dto.shows()));
        }
        validateDates(revival);

        return toDto(caller, repository.saveAndFlush(revival));
    }

    /**
     * Removes the revival with everything that belongs to it, which only a super admin may do.
     */
    @Transactional
    public void delete(final Long id, final Long version) {
        final Caller caller = callerService.current();
        final Revival revival = visible(caller, id);

        accessPolicy.requireDeleteRevival(caller);
        if (!Objects.equals(version, revival.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        AuditContext.operation("revivalDelete");
        deletion.delete(revival);
    }

    /**
     * Removes the people from the revival for good: a directeur while it is settling, or a super
     * admin while it is settling, archived or rejected; once. The confirmation is the revival's title as the web shows
     * it, e.g. "Ali Baba 2028" (trimmed, any case), or its id while Spexregister is unavailable.
     */
    @Transactional
    public RevivalDto anonymize(final Long id, final Long version, final String confirmation) {
        final Caller caller = callerService.current();
        final Revival revival = visible(caller, id);

        if (revival.isAnonymized()) {
            throw new BadRequestException("error.revival.anonymized");
        }
        accessPolicy.requireAnonymize(caller, revival);
        if (!Objects.equals(version, revival.getVersion())) {
            throw new ConflictException("error.conflict");
        }

        final SpexDto spex = spexService.findTitles(List.of(revival.getSpexId())).get(revival.getSpexId());
        final String expected = spex == null || spex.title() == null ? String.valueOf(revival.getId())
                : "%s %d".formatted(spex.title(), revival.getYear());

        if (confirmation == null || !confirmation.strip().equalsIgnoreCase(expected)) {
            throw new BadRequestException("error.revival.anonymize.confirmation");
        }
        AuditContext.operation("revivalAnonymize");
        anonymization.anonymize(revival, caller.person().getId());
        return toDto(caller, repository.findById(id).orElseThrow());
    }

    /**
     * Changes the status. The {@code from} the caller saw must still be the stored status; then a super
     * admin may set any status and a directeur only the next step. The change is recorded.
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
        events.publishEvent(new RevivalStatusChanged(saved.getId(), change.getFromStatus(), change.getToStatus()));
        return toDto(caller, saved);
    }

    private void requireUnique(final Long spexId, final Integer year, final @Nullable Long id) {
        if (repository.existsBySpexIdAndYearAndIdNot(spexId, year, id == null ? -1L : id)) {
            throw new BadRequestException("error.revival.duplicate", year);
        }
    }

    /**
     * Keeps the shows that stay, so that only real changes reach the history. The shows are the inverse side of their
     * relation, which Hibernate does not count as a change of the revival, so a change touches the revival itself: its
     * version then moves on and a stale one is a conflict, as for its other fields.
     */
    private void replaceShows(final Revival revival, final List<RevivalShowDto> shows) {
        final Set<RevivalShowDto> wanted = new HashSet<>(shows);
        final boolean removed = revival.getShows().removeIf(show -> !wanted.remove(show.toDto()));

        shows.stream()
                .filter(wanted::contains)
                .forEach(show -> revival.getShows().add(new RevivalShow(revival, show.date(),
                        show.time() == null ? null : LocalTime.parse(show.time(), RevivalShow.TIME_FORMAT), show.venue())));
        if (removed || !wanted.isEmpty()) {
            revival.setLastModifiedAt(Instant.now(clock));
        }
    }
}
