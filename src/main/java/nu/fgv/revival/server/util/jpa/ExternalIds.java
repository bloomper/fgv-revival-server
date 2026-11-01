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

package nu.fgv.revival.server.util.jpa;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Translates between the external ids that the APIs speak and the internal ids that the rows refer to each other by.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExternalIds {

    private final EntityManager entityManager;

    /**
     * @param type an entity type with a {@code uuid} attribute
     * @param uuid its external id
     * @return its internal id
     * @throws ResourceNotFoundException when there is none with that external id
     */
    public Long idOf(final Class<?> type, final UUID uuid) {
        return findIdOf(type, uuid).orElseThrow(() -> new ResourceNotFoundException(type, uuid));
    }

    /**
     * @param type an entity type with a {@code uuid} attribute
     * @param uuid its external id
     * @return its internal id, or empty when there is none with that external id
     */
    public Optional<Long> findIdOf(final Class<?> type, final UUID uuid) {
        return entityManager
                .createQuery("SELECT e.id FROM %s e WHERE e.uuid = :uuid".formatted(type.getSimpleName()), Long.class)
                .setParameter("uuid", uuid)
                .getResultStream()
                .findFirst();
    }

    /**
     * @param type an entity type with a {@code uuid} attribute
     * @param uuid its external id, or null
     * @return its internal id, or null when no external id is given
     * @throws ResourceNotFoundException when there is none with that external id
     */
    public @Nullable Long idOfNullable(final Class<?> type, final @Nullable UUID uuid) {
        return uuid == null ? null : idOf(type, uuid);
    }

    /**
     * @param type  an entity type with a {@code uuid} attribute
     * @param uuids external ids
     * @return their internal ids, in the same order
     * @throws ResourceNotFoundException when one of them does not exist
     */
    public List<Long> idsOf(final Class<?> type, final Collection<UUID> uuids) {
        if (uuids.isEmpty()) {
            return List.of();
        }

        final Map<UUID, Long> ids = entityManager
                .createQuery("SELECT e.uuid, e.id FROM %s e WHERE e.uuid IN :uuids".formatted(type.getSimpleName()), Object[].class)
                .setParameter("uuids", uuids)
                .getResultStream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));

        return uuids.stream()
                .map(uuid -> {
                    final Long id = ids.get(uuid);

                    if (id == null) {
                        throw new ResourceNotFoundException(type, uuid);
                    }
                    return id;
                })
                .toList();
    }

    /**
     * @param type  an entity type with a {@code uuid} attribute
     * @param uuids external ids, or null
     * @return their internal ids, in the same order, or null when no list is given
     * @throws ResourceNotFoundException when one of them does not exist
     */
    public @Nullable List<Long> idsOfNullable(final Class<?> type, final @Nullable Collection<UUID> uuids) {
        return uuids == null ? null : idsOf(type, uuids);
    }

    /**
     * @param type an entity type with a {@code uuid} attribute
     * @param id   its internal id
     * @return its external id
     * @throws ResourceNotFoundException when there is none with that internal id
     */
    public UUID uuidOf(final Class<?> type, final Long id) {
        return entityManager
                .createQuery("SELECT e.uuid FROM %s e WHERE e.id = :id".formatted(type.getSimpleName()), UUID.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(type, id));
    }

    /**
     * @param type an entity type with a {@code uuid} attribute
     * @param ids  internal ids
     * @return the external ids of those that exist, by internal id
     */
    public Map<Long, UUID> uuidsOf(final Class<?> type, final Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }

        return entityManager
                .createQuery("SELECT e.id, e.uuid FROM %s e WHERE e.id IN :ids".formatted(type.getSimpleName()), Object[].class)
                .setParameter("ids", ids)
                .getResultStream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (UUID) row[1]));
    }

    /**
     * @param type an entity type with a {@code uuid} attribute
     * @param byId values by internal id
     * @param <V>  the value type
     * @return the same values by external id
     */
    public <V> Map<UUID, V> byUuid(final Class<?> type, final Map<Long, V> byId) {
        final Map<Long, UUID> uuids = uuidsOf(type, byId.keySet());
        final Map<UUID, V> result = new HashMap<>();

        byId.forEach((id, value) -> {
            final UUID uuid = uuids.get(id);

            if (uuid != null) {
                result.put(uuid, value);
            }
        });
        return result;
    }

    /**
     * @param type an entity type with a {@code uuid} attribute
     * @param id   its internal id, or null
     * @return its external id, or null when no internal id is given
     */
    public @Nullable UUID uuidOfNullable(final Class<?> type, final @Nullable Long id) {
        return id == null ? null : uuidOf(type, id);
    }
}
