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

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Fixed external ids for tests.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class TestUuids {

    private TestUuids() {
    }

    /**
     * @param n a number to tell the ids apart
     * @return the same id for the same number
     */
    public static UUID uuid(final long n) {
        return new UUID(0x0000000000007000L, 0x8000000000000000L | n);
    }

    /**
     * @param n a number to tell the ids apart, or null
     * @return the same id for the same number, or null
     */
    public static @Nullable UUID uuidOrNull(final @Nullable Long n) {
        return n == null ? null : uuid(n);
    }

    /**
     * Does what the database does on save: gives the entity the external id of its id, and every {@code fooUuid}
     * the external id of its {@code fooId} or {@code foo}.
     *
     * @param entity an entity with ids set
     * @param <T>    the entity type
     * @return the same entity
     */
    public static <T extends @Nullable Object> T withUuids(final T entity) {
        if (entity == null) {
            return entity;
        }
        for (Class<?> type = entity.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (final Field field : type.getDeclaredFields()) {
                if (field.getType() != UUID.class || !field.getName().endsWith("Uuid") && !field.getName().equals("uuid")) {
                    continue;
                }
                final String base = field.getName().equals("uuid") ? "" : field.getName().substring(0, field.getName().length() - 4);
                final Long id = longField(entity, type, base.isEmpty() ? "id" : base + "Id");
                final Long value = id != null || base.isEmpty() ? id : longField(entity, type, base);

                if (value != null) {
                    set(entity, field, uuid(value));
                }
            }
        }
        return entity;
    }

    private static @Nullable Long longField(final Object entity, final Class<?> start, final String name) {
        for (Class<?> type = start; type != Object.class; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);

                if (field.getType() != Long.class) {
                    return null;
                }
                field.setAccessible(true);
                return (Long) field.get(entity);
            } catch (final NoSuchFieldException _) {
                continue;
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

    private static void set(final Object entity, final Field field, final UUID value) {
        try {
            field.setAccessible(true);
            field.set(entity, value);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
