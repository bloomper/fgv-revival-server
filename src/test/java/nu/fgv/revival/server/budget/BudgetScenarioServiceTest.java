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
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Saved scenarios: readers calculate, writers save, and a removed scenario is kept but not listed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class BudgetScenarioServiceTest {

    private final List<BudgetScenario> scenarios = new ArrayList<>();
    @Mock
    private BudgetScenarioRepository repository;
    @Mock
    private BudgetService budgetService;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private CallerService callerService;
    private BudgetScenarioService service;

    private static ScenarioParameters tickets(final int count) {
        return new ScenarioParameters(null, null, null, count, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        final Revival revival = TestCallers.revival(RevivalStatus.ORGANISING);

        service = new BudgetScenarioService(repository, budgetService, callerService, new BudgetAccess(revivalRepository, new AccessPolicy()),
                TestClocks.at(LocalDate.of(2027, 11, 1)));
        lenient().when(callerService.current()).thenReturn(TestCallers.directeur());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(budgetService.baseline(REVIVAL_ID)).thenReturn(new BudgetService.Baseline(List.of(
                new ScenarioCalculator.Line(BudgetCategoryKind.INCOME, BudgetCategoryRole.SHOW_TICKETS, new BigDecimal("100"), new BigDecimal("15000.00")),
                new ScenarioCalculator.Line(BudgetCategoryKind.COST, BudgetCategoryRole.VENUE, BigDecimal.ONE, new BigDecimal("12000.00"))), 40, null));
        lenient().when(repository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(REVIVAL_ID))
                .thenAnswer(invocation -> scenarios.stream().filter(scenario -> !scenario.isRemoved()).toList());
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                scenarios.stream().filter(scenario -> scenario.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            final BudgetScenario scenario = invocation.getArgument(0);

            if (scenario.getId() == null) {
                scenario.setId(1L + scenarios.size());
                scenario.setVersion(0L);
                scenarios.add(scenario);
            } else {
                scenario.setVersion(scenario.getVersion() + 1);
            }
            return scenario;
        });
    }

    @Test
    void aSavedScenarioHasItsResultFromTheBudgetAsItIs() {
        final BudgetDtos.Scenario saved = service.save(REVIVAL_ID, " Fler biljetter ", tickets(200));

        assertThat(saved.name()).isEqualTo("Fler biljetter");
        assertThat(saved.result().income()).isEqualTo(new BigDecimal("30000.00"));
        assertThat(saved.result().result()).isEqualTo(new BigDecimal("18000.00"));
        assertThat(service.list(REVIVAL_ID)).singleElement().extracting(BudgetDtos.Scenario::name).isEqualTo("Fler biljetter");
    }

    @Test
    void readersCalculateButDoNotSave() {
        when(callerService.current()).thenReturn(TestCallers.observer());

        assertThat(service.calculate(REVIVAL_ID, tickets(50)).breakEvenShowTickets()).isEqualTo(80);
        assertThatThrownBy(() -> service.save(REVIVAL_ID, "Test", tickets(50))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void scenariosAreValidated() {
        assertThatThrownBy(() -> service.save(null, "Test", tickets(1))).hasFieldOrPropertyWithValue("detailMessageCode", "error.scenario.revival");
        assertThatThrownBy(() -> service.save(REVIVAL_ID, " ", tickets(1))).hasFieldOrPropertyWithValue("detailMessageCode", "error.scenario.name");
        assertThatThrownBy(() -> service.save(REVIVAL_ID, "Test", tickets(-1))).hasFieldOrPropertyWithValue("detailMessageCode", "error.scenario.count");
        assertThatThrownBy(() -> service.calculate(REVIVAL_ID, new ScenarioParameters(2001, null, null, null, null, null, null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.scenario.count");
        assertThatThrownBy(() -> service.calculate(REVIVAL_ID, new ScenarioParameters(null, null, null, null, null, null, new BigDecimal("-5"), null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.budget.amount");
    }

    @Test
    void aRemovedScenarioIsKeptButNotListedOrChanged() {
        final BudgetDtos.Scenario saved = service.save(REVIVAL_ID, "Test", tickets(1));

        assertThatThrownBy(() -> service.update(saved.id(), 3L, "Test 2", tickets(2))).isInstanceOf(ConflictException.class);
        assertThat(service.update(saved.id(), 0L, "Test 2", tickets(2)).name()).isEqualTo("Test 2");
        service.remove(saved.id(), 1L);

        assertThat(scenarios.getFirst().getRemovedAt()).isNotNull();
        assertThat(service.list(REVIVAL_ID)).isEmpty();
        assertThatThrownBy(() -> service.update(saved.id(), 2L, "Test 3", tickets(3))).isInstanceOf(ResourceNotFoundException.class);
    }
}
