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

package nu.fgv.revival.server;

import nu.fgv.revival.server.util.jpa.ExternalIds;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static nu.fgv.revival.server.TestUuids.uuid;

/**
 * External ids without a database: the row with internal id n is known as {@link TestUuids#uuid(long) uuid(n)}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class TestExternalIds extends ExternalIds {

    public static final TestExternalIds IDS = new TestExternalIds();

    private TestExternalIds() {
        super(null);
    }

    /**
     * @param uuid an external id made by {@link TestUuids#uuid(long)}
     * @return the internal id it stands for
     */
    public static Long internal(final UUID uuid) {
        return uuid.getLeastSignificantBits() & Long.MAX_VALUE;
    }

    @Override
    public Long idOf(final Class<?> type, final UUID uuid) {
        return internal(uuid);
    }

    @Override
    public Optional<Long> findIdOf(final Class<?> type, final UUID uuid) {
        return Optional.of(internal(uuid));
    }

    @Override
    public List<Long> idsOf(final Class<?> type, final Collection<UUID> uuids) {
        return uuids.stream().map(TestExternalIds::internal).toList();
    }

    @Override
    public UUID uuidOf(final Class<?> type, final Long id) {
        return uuid(id);
    }

    @Override
    public Map<Long, UUID> uuidsOf(final Class<?> type, final Collection<Long> ids) {
        return ids.stream().distinct().collect(Collectors.toMap(id -> id, TestUuids::uuid));
    }
}
