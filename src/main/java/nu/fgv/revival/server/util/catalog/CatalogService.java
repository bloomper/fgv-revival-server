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
package nu.fgv.revival.server.util.catalog;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeping one list: everyone signed in reads it, the super admin changes it. An entry in use is retired, not deleted:
 * retired entries are not offered for new use, but rows that hold one keep it and still show its name.
 *
 * @param <E> the entry
 * @author Anders Jacobsson
 * @since 0.4
 */
public abstract class CatalogService<E extends CatalogEntry> {

    private final CatalogRepository<E> repository;
    private final Class<E> type;
    private final String operationPrefix;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    protected CatalogService(final CatalogRepository<E> repository, final Class<E> type, final String operationPrefix,
                             final CallerService callerService, final AccessPolicy accessPolicy, final Clock clock) {
        this.repository = repository;
        this.type = type;
        this.operationPrefix = operationPrefix;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    private static <E extends CatalogEntry> void setNames(final E entry, final Names names, final List<E> entries) {
        final String sv = validName(names.sv());
        final String folded = sv.toLowerCase(Locale.ROOT);

        if (entries.stream().anyMatch(other -> !other.getId().equals(entry.getId()) && other.getNameSv().toLowerCase(Locale.ROOT).equals(folded))) {
            throw new BadRequestException("error.catalog.name.taken", sv);
        }
        entry.setNameSv(sv);
        entry.setNameEn(names.en() == null || names.en().isBlank() ? null : validName(names.en()));
    }

    private static String validName(final @Nullable String name) {
        final String trimmed = name == null ? "" : name.strip();

        if (trimmed.isEmpty()) {
            throw new BadRequestException("error.catalog.name.blank");
        }
        if (trimmed.length() > CatalogEntry.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.catalog.name.tooLong", CatalogEntry.NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    // --- Reading ----------------------------------------------------------------------------------------------------

    /**
     * The ids that rows refer to. Only these entries cannot be deleted.
     */
    protected abstract Set<String> used();

    @Transactional(readOnly = true)
    public List<E> list(final boolean includeRetired) {
        callerService.current();
        return repository.findAllByOrderByPositionAscIdAsc().stream().filter(entry -> includeRetired || !entry.isRetired()).toList();
    }

    /**
     * Entries by id, retired ones too, for showing what rows hold. No caller check: callers have checked the rows.
     */
    @Transactional(readOnly = true)
    public Map<String, E> byId(final Collection<String> ids) {
        return ids.isEmpty() ? Map.of() : repository.findAllById(Set.copyOf(ids)).stream().collect(Collectors.toMap(CatalogEntry::getId, Function.identity()));
    }

    /**
     * Whether each entry is in use, for the super admin's list.
     */
    @Transactional(readOnly = true)
    public Map<String, Boolean> inUse(final Collection<String> ids) {
        final Set<String> used = used();

        return ids.stream().distinct().collect(Collectors.toMap(Function.identity(), used::contains));
    }

    // --- Changing ---------------------------------------------------------------------------------------------------

    /**
     * Refuses ids that do not exist, and retired ones unless the row already {@code held} them.
     */
    @Transactional(readOnly = true)
    public void requireAssignable(final Collection<String> ids, final Collection<String> held) {
        final Map<String, E> found = byId(ids);

        for (final String id : ids) {
            final E entry = found.get(id);

            if (entry == null) {
                throw new BadRequestException("error.catalog.unknown", id);
            }
            if (entry.isRetired() && !held.contains(id)) {
                throw new BadRequestException("error.catalog.retired", entry.getNameSv());
            }
        }
    }

    /**
     * Appends a new entry, set up by the subclass, with a new id.
     */
    protected E create(final E entry, final Names names) {
        manageable();

        final List<E> entries = repository.findAllByOrderByPositionAscIdAsc();

        AuditContext.operation(operationPrefix + "Create");
        entry.setId(UUID.randomUUID().toString());
        setNames(entry, names, entries);
        entry.setPosition(entries.size());
        return repository.saveAndFlush(entry);
    }

    @Transactional
    public E rename(final String id, final Long version, final Names names) {
        manageable();

        final E entry = changeable(id, version);

        AuditContext.operation(operationPrefix + "Update");
        setNames(entry, names, repository.findAllByOrderByPositionAscIdAsc());
        return repository.saveAndFlush(entry);
    }

    /**
     * Returns the whole list, retired entries too, in its new order.
     */
    @Transactional
    public List<E> move(final String id, final Long version, final int position) {
        manageable();

        final E entry = changeable(id, version);
        final List<E> entries = new ArrayList<>(repository.findAllByOrderByPositionAscIdAsc());

        AuditContext.operation(operationPrefix + "Move");
        entries.removeIf(candidate -> candidate.getId().equals(id));
        entries.add(Math.clamp(position, 0, entries.size()), entry);
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).getPosition() != index) {
                entries.get(index).setPosition(index);
                repository.save(entries.get(index));
            }
        }
        repository.flush();
        return entries;
    }

    @Transactional
    public E retire(final String id, final Long version) {
        manageable();

        final E entry = changeable(id, version);

        AuditContext.operation(operationPrefix + "Retire");
        entry.setRetiredAt(entry.isRetired() ? entry.getRetiredAt() : clock.instant());
        return repository.saveAndFlush(entry);
    }

    @Transactional
    public E restore(final String id, final Long version) {
        manageable();

        final E entry = changeable(id, version);

        AuditContext.operation(operationPrefix + "Restore");
        entry.setRetiredAt(null);
        return repository.saveAndFlush(entry);
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    /**
     * Deletes an entry nothing uses, such as one created by mistake. One in use is refused: retire it instead.
     */
    @Transactional
    public void delete(final String id, final Long version) {
        manageable();

        final E entry = changeable(id, version);

        if (used().contains(id)) {
            throw new BadRequestException("error.catalog.inUse");
        }
        AuditContext.operation(operationPrefix + "Delete");
        repository.delete(entry);
        repository.flush();

        final List<E> remaining = repository.findAllByOrderByPositionAscIdAsc();

        for (int index = 0; index < remaining.size(); index++) {
            if (remaining.get(index).getPosition() != index) {
                remaining.get(index).setPosition(index);
                repository.save(remaining.get(index));
            }
        }
        repository.flush();
    }

    protected void manageable() {
        accessPolicy.requireManageCatalogs(callerService.current());
    }

    private E changeable(final String id, final Long version) {
        final E entry = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(type, id));

        if (!Objects.equals(version, entry.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return entry;
    }

    public record Names(String sv, @Nullable String en) {
    }
}
