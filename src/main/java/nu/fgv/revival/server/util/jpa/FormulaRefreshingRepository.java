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
import org.hibernate.annotations.Formula;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The repositories' base: saving an entity with {@link Formula} attributes, such as the external id of a row it refers
 * to, writes it and reads it back, since Hibernate computes formulas only when it loads a row. Without this, the entity
 * a create or an update returns would lack them, or still have the old ones.
 *
 * @param <T>  the entity type
 * @param <ID> the id type
 * @author Anders Jacobsson
 * @since 1.0
 */
public class FormulaRefreshingRepository<T, ID> extends SimpleJpaRepository<T, ID> {

    private static final Map<Class<?>, Boolean> HAS_FORMULAS = new ConcurrentHashMap<>();

    private final EntityManager entityManager;

    public FormulaRefreshingRepository(final JpaEntityInformation<T, ?> entityInformation, final EntityManager entityManager) {
        super(entityInformation, entityManager);
        this.entityManager = entityManager;
    }

    private static boolean hasFormulas(final Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (final Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Formula.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    @Transactional
    public <S extends T> S save(final S entity) {
        final S saved = super.save(entity);

        if (HAS_FORMULAS.computeIfAbsent(saved.getClass(), FormulaRefreshingRepository::hasFormulas)) {
            entityManager.flush();
            entityManager.refresh(saved);
        }
        return saved;
    }
}
