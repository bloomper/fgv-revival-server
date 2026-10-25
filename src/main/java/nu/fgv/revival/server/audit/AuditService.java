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
package nu.fgv.revival.server.audit;

import jakarta.persistence.EntityManager;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import nu.fgv.revival.server.util.security.SecurityUtil;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A record's history from Envers, read only. Who changed it is shown by name, never by email address.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class AuditService {

    private final AuditRegistry registry;
    private final AuditDiffer differ;
    private final CallerService callerService;
    private final PersonNames personNames;
    private final EntityManager entityManager;

    public AuditService(final AuditRegistry registry, final AuditDiffer differ, final CallerService callerService, final PersonNames personNames,
                        final EntityManager entityManager) {
        this.registry = registry;
        this.differ = differ;
        this.callerService = callerService;
        this.personNames = personNames;
        this.entityManager = entityManager;
    }

    private static String subjectOf(final AuditRevisionEntity revision) {
        return Objects.requireNonNullElse(revision.getModifiedBy(), SecurityUtil.SYSTEM);
    }

    /**
     * Newest first. A change that touched only fields the history leaves out is left out too. A record the caller may
     * not read now is refused even though they may have read it once.
     */
    @Transactional(readOnly = true)
    public List<RevisionDto> revisions(final AuditedType type, final Long id, final Locale locale) {
        final AuditedEntityDescriptor descriptor = registry.require(type);
        final Caller caller = callerService.current();
        @SuppressWarnings("unchecked") final List<Object[]> triples = AuditReaderFactory.get(entityManager).createQuery()
                .forRevisionsOfEntity(descriptor.entityClass(), false, true)
                .add(AuditEntity.id().eq(id))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();

        if (triples.isEmpty()) {
            throw new ResourceNotFoundException(descriptor.entityClass(), id);
        }
        descriptor.access().require(caller, triples.getLast()[0]);

        final Map<String, String> names = personNames.bySubjects(triples.stream()
                .map(triple -> subjectOf((AuditRevisionEntity) triple[1]))
                .toList(), locale);
        final List<RevisionDto> revisions = new ArrayList<>(triples.size());
        Object previous = null;

        for (final Object[] triple : triples) {
            final Object snapshot = triple[0];
            final AuditRevisionEntity revision = (AuditRevisionEntity) triple[1];
            final RevisionType revisionType = (RevisionType) triple[2];
            final List<FieldChangeDto> changes = revisionType == RevisionType.DEL ? List.of() : differ.diff(descriptor, previous, snapshot, locale);

            if (revisionType != RevisionType.MOD || !changes.isEmpty()) {
                revisions.add(new RevisionDto(revision.getId(), type, id, revisionType, Instant.ofEpochMilli(revision.getModifiedAt()),
                        names.get(subjectOf(revision)),
                        revision.getSource(), revision.getComment(), changes));
            }
            if (revisionType != RevisionType.DEL) {
                previous = snapshot;
            }
        }
        return revisions.reversed();
    }
}
