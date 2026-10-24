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
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.catalog.CatalogService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The budget categories the super admin keeps (M5-FR-1). A category's kind is fixed when it is created; its role may
 * change, but at most one active category holds each role, and only one of the role's kind.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Service
public class BudgetCategoryService extends CatalogService<BudgetCategory> {

    private final BudgetCategoryRepository repository;

    public BudgetCategoryService(final BudgetCategoryRepository repository, final CallerService callerService, final AccessPolicy accessPolicy,
                                 final Clock clock) {
        super(repository, BudgetCategory.class, "budgetCategory", callerService, accessPolicy, clock);
        this.repository = repository;
    }

    private static void requireKind(final BudgetCategoryKind kind, final @Nullable BudgetCategoryRole role) {
        if (role != null && role.kind() != kind) {
            throw new BadRequestException("error.budgetCategory.role.kind", role.name());
        }
    }

    @Override
    protected Set<String> used() {
        return Set.copyOf(repository.findUsedIds());
    }

    @Transactional
    public BudgetCategory create(final BudgetCategoryKind kind, final @Nullable BudgetCategoryRole role, final Names names) {
        manageable();
        requireRole(null, kind, role);

        final BudgetCategory category = new BudgetCategory();

        category.setKind(kind);
        category.setRole(role);
        return create(category, names);
    }

    /**
     * Renames the category and sets its role, or clears it with null.
     */
    @Transactional
    public BudgetCategory update(final String id, final Long version, final Names names, final @Nullable BudgetCategoryRole role) {
        manageable();

        final BudgetCategory category = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(BudgetCategory.class, id));

        if (!category.isRetired()) {
            requireRole(id, category.getKind(), role);
        } else {
            requireKind(category.getKind(), role);
        }
        // The same managed entity that rename checks and saves, so the version moves on once
        category.setRole(role);
        return rename(id, version, names);
    }

    /**
     * Refused while another active category holds the same role.
     */
    @Override
    @Transactional
    public BudgetCategory restore(final String id, final Long version) {
        manageable();

        final BudgetCategory category = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(BudgetCategory.class, id));

        requireRole(id, category.getKind(), category.getRole());
        return super.restore(id, version);
    }

    /**
     * The active category with the role, if any.
     */
    @Transactional(readOnly = true)
    public Optional<BudgetCategory> withRole(final BudgetCategoryRole role) {
        return repository.findAllByOrderByPositionAscIdAsc().stream()
                .filter(category -> !category.isRetired() && category.getRole() == role)
                .findFirst();
    }

    /**
     * Every category, retired ones too, in list order. No caller check: for showing what rows hold.
     */
    @Transactional(readOnly = true)
    public List<BudgetCategory> all() {
        return repository.findAllByOrderByPositionAscIdAsc();
    }

    /**
     * The categories as GraphQL shows them, by id. No caller check: callers have checked the rows.
     */
    @Transactional(readOnly = true)
    public Map<String, BudgetCategoryDto> dtos(final Collection<String> ids) {
        final Map<String, BudgetCategoryDto> result = new HashMap<>();

        byId(ids).forEach((id, category) -> result.put(id, BudgetCategoryDto.of(category)));
        return result;
    }

    /**
     * An active category by its id, or by its Swedish or English name without regard to case, as MCP names one.
     * Several matches, or none, are refused.
     */
    @Transactional(readOnly = true)
    public BudgetCategory resolveActive(final String text) {
        final String wanted = text.strip();
        final List<BudgetCategory> active = repository.findAllByOrderByPositionAscIdAsc().stream().filter(category -> !category.isRetired()).toList();
        final Optional<BudgetCategory> byId = active.stream().filter(category -> category.getId().equals(wanted)).findFirst();

        if (byId.isPresent()) {
            return byId.get();
        }

        final List<BudgetCategory> byName = active.stream()
                .filter(category -> category.getNameSv().equalsIgnoreCase(wanted) || category.getNameEn() != null && category.getNameEn().equalsIgnoreCase(wanted))
                .toList();

        if (byName.size() > 1) {
            throw new BadRequestException("error.budgetCategory.ambiguous", wanted,
                    String.join(", ", byName.stream().map(BudgetCategory::getNameSv).toList()));
        }
        if (byName.isEmpty()) {
            throw new BadRequestException("error.budgetCategory.unknown", wanted,
                    String.join(", ", active.stream().map(BudgetCategory::getNameSv).toList()));
        }
        return byName.getFirst();
    }

    private void requireRole(final @Nullable String id, final BudgetCategoryKind kind, final @Nullable BudgetCategoryRole role) {
        requireKind(kind, role);
        if (role != null && repository.findAllByOrderByPositionAscIdAsc().stream()
                .anyMatch(other -> !other.getId().equals(id) && !other.isRetired() && other.getRole() == role)) {
            throw new BadRequestException("error.budgetCategory.role.taken", role.name());
        }
    }
}
