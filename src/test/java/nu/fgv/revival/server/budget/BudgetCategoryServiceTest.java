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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.catalog.CatalogService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The budget categories the super admin keeps: the kind is fixed, and at most one active category holds a
 * role.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class BudgetCategoryServiceTest {

    private final List<BudgetCategory> categories = new ArrayList<>();
    @Mock
    private BudgetCategoryRepository repository;
    @Mock
    private CallerService callerService;
    private BudgetCategoryService service;

    private static CatalogService.Names names(final String sv) {
        return new CatalogService.Names(sv, null);
    }

    @BeforeEach
    void setUp() {
        service = new BudgetCategoryService(repository, callerService, new AccessPolicy(), TestClocks.at(LocalDate.of(2026, 10, 23)));
        categories.addAll(TestBudgetCategories.seeded());
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(repository.findAllByOrderByPositionAscIdAsc()).thenAnswer(invocation -> List.copyOf(categories));
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                categories.stream().filter(category -> category.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.findAllById(any())).thenAnswer(invocation -> {
            final Iterable<String> ids = invocation.getArgument(0);
            final List<BudgetCategory> found = new ArrayList<>();

            ids.forEach(id -> categories.stream().filter(category -> category.getId().equals(id)).forEach(found::add));
            return found;
        });
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private BudgetCategory find(final String id) {
        return categories.stream().filter(category -> category.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aNewCategoryHasItsKindAndAFreeRole() {
        find("HELPERS").setRole(null);

        final BudgetCategory created = service.create(BudgetCategoryKind.COST, BudgetCategoryRole.HELPERS, names(" Funktionärer "));

        assertThat(created.getKind()).isEqualTo(BudgetCategoryKind.COST);
        assertThat(created.getRole()).isEqualTo(BudgetCategoryRole.HELPERS);
        assertThat(created.getNameSv()).isEqualTo("Funktionärer");
        assertThat(created.getPosition()).isEqualTo(categories.size());
        assertThat(created.getId()).hasSize(36);
    }

    @Test
    void aRoleAnotherActiveCategoryHoldsIsRefused() {
        assertThatThrownBy(() -> service.create(BudgetCategoryKind.COST, BudgetCategoryRole.VENUE, names("Fler lokaler")))
                .isInstanceOf(BadRequestException.class).hasFieldOrPropertyWithValue("detailMessageCode", "error.budgetCategory.role.taken");
        assertThatThrownBy(() -> service.update("FOOD", 0L, names("Mat och dryck"), BudgetCategoryRole.PARTY))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budgetCategory.role.taken");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aRetiredCategoryDoesNotHoldItsRole() {
        find("VENUE").setRetiredAt(Instant.parse("2026-10-01T10:00:00Z"));

        final BudgetCategory created = service.create(BudgetCategoryKind.COST, BudgetCategoryRole.VENUE, names("Lokaler 2"));

        assertThat(created.getRole()).isEqualTo(BudgetCategoryRole.VENUE);
    }

    @Test
    void aRoleMustFitTheKind() {
        assertThatThrownBy(() -> service.create(BudgetCategoryKind.INCOME, BudgetCategoryRole.HELPERS, names("Springare")))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budgetCategory.role.kind");
        assertThatThrownBy(() -> service.update("SPONSORSHIP", 0L, names("Sponsring"), BudgetCategoryRole.VENUE))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budgetCategory.role.kind");
    }

    @Test
    void anUpdateRenamesAndSetsOrClearsTheRoleButNotTheKind() {
        final BudgetCategory updated = service.update("VENUE", 0L, new CatalogService.Names("Lokalhyra", "Venue hire"), null);

        assertThat(updated.getNameSv()).isEqualTo("Lokalhyra");
        assertThat(updated.getNameEn()).isEqualTo("Venue hire");
        assertThat(updated.getRole()).isNull();
        assertThat(updated.getKind()).isEqualTo(BudgetCategoryKind.COST);

        assertThat(service.update("FOOD", 0L, names("Mat och dryck"), BudgetCategoryRole.VENUE).getRole()).isEqualTo(BudgetCategoryRole.VENUE);
    }

    @Test
    void aStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.update("FOOD", 5L, names("Mat"), null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void restoringIsRefusedWhileAnotherActiveCategoryHasTheRole() {
        final BudgetCategory old = TestBudgetCategories.category("OLD_VENUE", BudgetCategoryKind.COST, BudgetCategoryRole.VENUE, "Gamla lokaler", null, 8);

        old.setRetiredAt(Instant.parse("2026-01-01T10:00:00Z"));
        categories.add(old);

        assertThatThrownBy(() -> service.restore("OLD_VENUE", 0L)).hasFieldOrPropertyWithValue("detailMessageCode", "error.budgetCategory.role.taken");

        find("VENUE").setRetiredAt(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(service.restore("OLD_VENUE", 0L).isRetired()).isFalse();
    }

    @Test
    void onlyTheSuperAdminChangesCategories() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        assertThatThrownBy(() -> service.create(BudgetCategoryKind.COST, null, names("Övrigt"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update("FOOD", 0L, names("Mat"), null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.restore("FOOD", 0L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aCategoryInUseCannotBeDeleted() {
        when(repository.findUsedIds()).thenReturn(List.of("FOOD"));

        assertThatThrownBy(() -> service.delete("FOOD", 0L)).hasFieldOrPropertyWithValue("detailMessageCode", "error.catalog.inUse");
        assertThat(service.inUse(List.of("FOOD", "PARTY"))).containsEntry("FOOD", true).containsEntry("PARTY", false);
    }

    @Test
    void mcpNamesACategoryByIdOrName() {
        assertThat(service.resolveActive("FOOD").getId()).isEqualTo("FOOD");
        assertThat(service.resolveActive(" mat och dryck ").getId()).isEqualTo("FOOD");
        assertThat(service.resolveActive("venues").getId()).isEqualTo("VENUE");
        assertThatThrownBy(() -> service.resolveActive("Okänd")).hasFieldOrPropertyWithValue("detailMessageCode", "error.budgetCategory.unknown");

        categories.add(TestBudgetCategories.category("FOOD_2", BudgetCategoryKind.COST, null, "Food and drink", null, 8));
        assertThatThrownBy(() -> service.resolveActive("food and drink"))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budgetCategory.ambiguous");

        find("SPONSORSHIP").setRetiredAt(Instant.parse("2026-10-01T10:00:00Z"));
        assertThatThrownBy(() -> service.resolveActive("SPONSORSHIP")).hasFieldOrPropertyWithValue("detailMessageCode", "error.budgetCategory.unknown");
    }
}
