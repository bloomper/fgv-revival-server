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
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Specification queries for revival data, without the {@code delete(Specification)} that
 * {@code JpaSpecificationExecutor} would give its repositories.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SpecificationQueries {

    private final EntityManager entityManager;

    public SpecificationQueries(final EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public <T> List<T> findAll(final Class<T> type, final Specification<T> specification, final Sort sort) {
        final CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        final CriteriaQuery<T> query = cb.createQuery(type);
        final Root<T> root = query.from(type);
        final Predicate predicate = specification.toPredicate(root, query, cb);

        if (predicate != null) {
            query.where(predicate);
        }
        query.select(root).orderBy(QueryUtils.toOrders(sort, root, cb));
        return entityManager.createQuery(query).getResultList();
    }

    public <T> long count(final Class<T> type, final Specification<T> specification) {
        final CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        final CriteriaQuery<Long> query = cb.createQuery(Long.class);
        final Root<T> root = query.from(type);
        final Predicate predicate = specification.toPredicate(root, query, cb);

        if (predicate != null) {
            query.where(predicate);
        }
        query.select(cb.count(root));
        return entityManager.createQuery(query).getSingleResult();
    }
}
