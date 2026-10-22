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

package nu.fgv.revival.server.spex;

import nu.fgv.revival.server.config.CacheConfig;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spex from Spexregister: one cached list of original spex, searched locally (research R5). Spexregister being
 * unavailable makes titles unavailable and blocks only picking a spex (FR-008a).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Service
public class SpexService {

    private static final String KEY = "all";

    private final SpexregisterClient client;
    private final CacheManager cacheManager;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;

    public SpexService(final SpexregisterClient client,
                       final CacheManager cacheManager,
                       final CallerService callerService,
                       final AccessPolicy accessPolicy) {
        this.client = client;
        this.cacheManager = cacheManager;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
    }

    static String normalize(final @Nullable String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private Cache cache() {
        return Objects.requireNonNull(cacheManager.getCache(CacheConfig.ORIGINAL_SPEX));
    }

    /**
     * The original spex, from the cache or from Spexregister.
     *
     * @throws SpexregisterUnavailableException when Spexregister cannot be reached
     */
    List<SpexDto> originalSpex() {
        try {
            return Objects.requireNonNull(cache().get(KEY, client::originalSpex));
        } catch (final Cache.ValueRetrievalException e) {
            throw new SpexregisterUnavailableException(e.getCause() != null ? e.getCause() : e);
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            throw new SpexregisterUnavailableException(e);
        }
    }

    /**
     * Original spex whose title contains the query, ignoring case and accents. Super admins only.
     */
    public List<SpexDto> search(final String query, final @Nullable Integer first) {
        accessPolicy.requireSpexSearch(callerService.current());

        final String needle = normalize(query);
        final int limit = first == null || first < 1 ? 20 : Math.min(first, 100);

        return originalSpex().stream()
                .filter(spex -> normalize(spex.title()).contains(needle))
                .sorted(Comparator.comparing((SpexDto spex) -> normalize(spex.title())).thenComparing(spex -> spex.year() == null ? 0 : spex.year()))
                .limit(limit)
                .toList();
    }

    /**
     * The spex from the cached list only, never calling Spexregister.
     */
    public Optional<SpexDto> findCached(final Long id) {
        final Cache.ValueWrapper cached = cache().get(KEY);

        return cached == null || !(cached.get() instanceof final List<?> spex)
                ? Optional.empty()
                : spex.stream().map(SpexDto.class::cast).filter(candidate -> candidate.id().equals(id)).findFirst();
    }

    /**
     * Titles for several spex from one cached list. Never fails: while Spexregister is unavailable every spex is
     * returned as unavailable.
     */
    public Map<Long, SpexDto> findTitles(final Collection<Long> ids) {
        final Map<Long, SpexDto> known;

        try {
            known = originalSpex().stream().collect(Collectors.toMap(SpexDto::id, Function.identity(), (a, _) -> a));
        } catch (final SpexregisterUnavailableException _) {
            return ids.stream().distinct().collect(Collectors.toMap(Function.identity(), SpexDto::unavailable, (a, _) -> a, LinkedHashMap::new));
        }

        return ids.stream().distinct().collect(Collectors.toMap(
                Function.identity(),
                id -> known.getOrDefault(id, new SpexDto(id, null, null, true)),
                (a, _) -> a,
                LinkedHashMap::new));
    }

    /**
     * Refuses a spex that does not exist or is a revival recorded in Spexregister (FR-008).
     *
     * @throws SpexregisterUnavailableException when it cannot be checked
     */
    public SpexDto requireOriginal(final Long id) {
        final Optional<SpexDto> cached = originalSpex().stream().filter(spex -> spex.id().equals(id)).findFirst();

        if (cached.isPresent()) {
            return cached.get();
        }

        // Not in the cached list: it may be new since the list was read, so ask Spexregister directly
        final SpexregisterClient.SpexNode spex;

        try {
            spex = client.spexById(id).orElseThrow(() -> new BadRequestException("error.spex.notOriginal", id));
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            throw new SpexregisterUnavailableException(e);
        }

        if (Boolean.TRUE.equals(spex.revival())) {
            throw new BadRequestException("error.spex.notOriginal", id);
        }
        cache().evict(KEY);
        return new SpexDto(id, spex.title(), SpexregisterClient.year(spex.year()), true);
    }
}
