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
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Spexare from Spexregister, for participants. Names and contact details are kept in memory only, for at most five
 * minutes by spexare id, and never written to the database, logs or files; the task list,
 * vocal parts and spex families for an hour. Every failure is a {@link SpexregisterUnavailableException}. No caller
 * check here: the participant services check first.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SpexareDirectory {

    private static final String POSITIONS = "positions";
    private static final String VOCAL_PARTS = "vocalParts";
    private static final String FAMILY = "family:";

    private final SpexregisterClient client;
    private final CacheManager cacheManager;

    public SpexareDirectory(final SpexregisterClient client, final CacheManager cacheManager) {
        this.client = client;
        this.cacheManager = cacheManager;
    }

    private static <T> T unavailableOnFailure(final Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (final Cache.ValueRetrievalException e) {
            throw new SpexregisterUnavailableException(e.getCause() != null ? e.getCause() : e);
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            throw new SpexregisterUnavailableException(e);
        }
    }

    private Cache people() {
        return Objects.requireNonNull(cacheManager.getCache(CacheConfig.SPEXARE));
    }

    private Cache reference() {
        return Objects.requireNonNull(cacheManager.getCache(CacheConfig.SPEXREGISTER_REFERENCE));
    }

    /**
     * The spexare with these ids that Spexregister lets the application read, by id, from the cache where it can.
     */
    public Map<Long, SpexregisterClient.Spexare> byIds(final Collection<Long> ids, final String actingPerson) {
        final Set<Long> wanted = new LinkedHashSet<>(ids);
        final Map<Long, SpexregisterClient.Spexare> found = new LinkedHashMap<>();
        final List<Long> missing = new ArrayList<>();

        for (final Long id : wanted) {
            final SpexregisterClient.Spexare cached = people().get(id, SpexregisterClient.Spexare.class);

            if (cached != null) {
                found.put(id, cached);
            } else {
                missing.add(id);
            }
        }
        for (int from = 0; from < missing.size(); from += SpexregisterClient.MAX_SPEXARE) {
            final List<Long> chunk = missing.subList(from, Math.min(missing.size(), from + SpexregisterClient.MAX_SPEXARE));

            unavailableOnFailure(() -> client.spexareByIds(chunk, actingPerson)).forEach(spexare -> {
                final Long id = Long.valueOf(spexare.id());

                people().put(id, spexare);
                found.put(id, spexare);
            });
        }
        return found;
    }

    public SpexregisterClient.@Nullable Spexare byId(final Long id, final String actingPerson) {
        return byIds(List.of(id), actingPerson).get(id);
    }

    /**
     * The participants of a spex and its revivals as Spexregister lists them.
     */
    public List<SpexregisterClient.Spexare> bySpex(final Long spexId, final String actingPerson) {
        return unavailableOnFailure(() -> client.spexareBySpex(spexId, actingPerson));
    }

    public List<SpexregisterClient.Spexare> search(final String query, final String actingPerson) {
        return unavailableOnFailure(() -> client.searchSpexare(query, actingPerson));
    }

    /**
     * The ids of a spex and its revivals in Spexregister.
     */
    @SuppressWarnings("unchecked")
    public Set<Long> family(final Long spexId) {
        return Set.copyOf(unavailableOnFailure(() -> Objects.requireNonNull(reference().get(FAMILY + spexId, () -> client.spexFamily(spexId)))));
    }

    @SuppressWarnings("unchecked")
    public List<Position> positions() {
        return unavailableOnFailure(() -> Objects.requireNonNull(reference().get(POSITIONS, () -> client.tasks().stream()
                .map(task -> new Position(task.id(), task.name(), task.category() == null ? null : task.category().name(),
                        task.category() != null && Boolean.TRUE.equals(task.category().actorPresent())))
                .toList())));
    }

    @SuppressWarnings("unchecked")
    public List<VocalPart> vocalParts() {
        return unavailableOnFailure(() -> Objects.requireNonNull(reference().get(VOCAL_PARTS, () -> client.vocalParts().stream()
                .map(type -> new VocalPart(type.id(), type.label() == null ? type.id() : type.label()))
                .toList())));
    }

    /**
     * Positions by id, without failing: empty while Spexregister is unavailable.
     */
    public Map<String, Position> positionsOrEmpty() {
        try {
            final Map<String, Position> byId = new LinkedHashMap<>();

            positions().forEach(position -> byId.put(position.id(), position));
            return byId;
        } catch (final SpexregisterUnavailableException _) {
            return Map.of();
        }
    }

    /**
     * Vocal parts by id, without failing: empty while Spexregister is unavailable.
     */
    public Map<String, VocalPart> vocalPartsOrEmpty() {
        try {
            final Map<String, VocalPart> byId = new LinkedHashMap<>();

            vocalParts().forEach(part -> byId.put(part.id(), part));
            return byId;
        } catch (final SpexregisterUnavailableException _) {
            return Map.of();
        }
    }

    /**
     * Forgets what is cached about a spexare, after the sync has written to them.
     */
    public void evict(final Long spexareId) {
        people().evict(spexareId);
    }

    public Long create(final String firstName, final String lastName, final String actingPerson) {
        return unavailableOnFailure(() -> client.createSpexare(firstName, lastName, actingPerson));
    }

    public void saveHomeAddress(final Long spexareId, final @Nullable String addressId, final SpexregisterClient.AddressInput address,
                                final String actingPerson) {
        try {
            unavailableOnFailure(() -> {
                client.saveHomeAddress(spexareId, addressId, address, actingPerson);
                return null;
            });
        } finally {
            people().evict(spexareId);
        }
    }

    public void saveRegisterConsent(final Long spexareId, final @Nullable String consentId, final boolean value, final String actingPerson) {
        try {
            unavailableOnFailure(() -> {
                client.saveRegisterConsent(spexareId, consentId, value, actingPerson);
                return null;
            });
        } finally {
            people().evict(spexareId);
        }
    }

    /**
     * A task from Spexregister as a planned position; {@code hasRole} when its category has actors, i.e. the ensemble
     * (Spexregister's {@code TaskCategory.actorPresent}).
     */
    public record Position(String id, String name, @Nullable String category, boolean hasRole) {
    }

    public record VocalPart(String id, String name) {
    }
}
