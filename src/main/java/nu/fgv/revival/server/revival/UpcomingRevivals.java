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

import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.spex.SpexDto;
import nu.fgv.revival.server.spex.SpexService;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The revivals coming up, for everyone with access: those being organised or open whose premiere is set and whose
 * last show has not passed, soonest first. Only what a revival announces anyway is told: its title, premiere and shows.
 * Proposed, approved and rejected revivals are not announced, so they are left out.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class UpcomingRevivals {

    static final Set<RevivalStatus> ANNOUNCED = EnumSet.of(RevivalStatus.ORGANISING, RevivalStatus.OPEN);

    private final RevivalRepository repository;
    private final SpexService spexService;
    private final RevivalTitles titles;
    private final CallerService callerService;
    private final Clock clock;

    public UpcomingRevivals(final RevivalRepository repository, final SpexService spexService,
                            final RevivalTitles titles, final CallerService callerService, final Clock clock) {
        this.repository = repository;
        this.spexService = spexService;
        this.titles = titles;
        this.callerService = callerService;
        this.clock = clock;
    }

    private static LocalDate lastDay(final Revival revival, final List<RevivalShowDto> shows) {
        return Stream.concat(Stream.of(revival.getPremiereDate()), shows.stream().map(RevivalShowDto::date))
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<UpcomingRevivalDto> list(final Locale locale) {
        callerService.current();

        final LocalDate today = LocalDate.now(clock);
        final Specification<Revival> announced = (root, query, cb) -> cb.and(root.get("status").in(ANNOUNCED), cb.isNotNull(root.get("premiereDate")));
        final List<Revival> revivals = repository.findAll(announced, Sort.by("premiereDate", "id"));
        final Map<Long, SpexDto> spexById = spexService.findTitles(revivals.stream().map(Revival::getSpexId).toList());

        return revivals.stream()
                .map(revival -> {
                    final List<RevivalShowDto> shows = RevivalMapper.REVIVAL_MAPPER.toShowDtos(revival.getShows());

                    return lastDay(revival, shows).isBefore(today) ? null : new UpcomingRevivalDto(revival.getId(),
                            titles.of(spexById.get(revival.getSpexId()), revival.getYear(), locale), revival.getYear(), revival.getPremiereDate(), shows);
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
