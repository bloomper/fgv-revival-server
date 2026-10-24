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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.budget.BudgetDtos.Scenario;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * What-ifs over a revival's budget (product spec 5.10, M5-FR-10): who reads the budget calculates, who writes it saves
 * named scenarios. A saved scenario's result is calculated from the budget as it is when it is read.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Service
public class BudgetScenarioService {

    static final int MAX_TICKETS = 100_000;

    private final BudgetScenarioRepository repository;
    private final BudgetService budgetService;
    private final CallerService callerService;
    private final BudgetAccess access;
    private final Clock clock;

    public BudgetScenarioService(final BudgetScenarioRepository repository,
                                 final BudgetService budgetService,
                                 final CallerService callerService,
                                 final BudgetAccess access,
                                 final Clock clock) {
        this.repository = repository;
        this.budgetService = budgetService;
        this.callerService = callerService;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ScenarioResultDto calculate(final Long revivalId, final ScenarioParameters input) {
        access.readable(callerService.current(), revivalId);
        return calculate(budgetService.baseline(revivalId), valid(input));
    }

    /**
     * The scenarios that are not removed, oldest first, each with its result.
     */
    @Transactional(readOnly = true)
    public List<Scenario> list(final Long revivalId) {
        access.readable(callerService.current(), revivalId);

        final BudgetService.Baseline baseline = budgetService.baseline(revivalId);

        return repository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revivalId).stream().map(scenario -> of(scenario, baseline)).toList();
    }

    /**
     * The revival is required when saving, though the input type shares it with updates.
     */
    @Transactional
    public Scenario save(final @Nullable Long revivalId, final String name, final ScenarioParameters parameters) {
        if (revivalId == null) {
            throw new BadRequestException("error.scenario.revival");
        }
        access.writable(callerService.current(), revivalId);

        final BudgetScenario scenario = new BudgetScenario();

        scenario.setRevivalId(revivalId);
        scenario.setName(validName(name));
        scenario.setParameters(valid(parameters));
        AuditContext.operation("budgetScenarioSave");
        return of(repository.saveAndFlush(scenario), budgetService.baseline(revivalId));
    }

    @Transactional
    public Scenario update(final Long id, final Long version, final String name, final ScenarioParameters parameters) {
        final BudgetScenario scenario = changeable(callerService.current(), id, version);

        scenario.setName(validName(name));
        scenario.setParameters(valid(parameters));
        AuditContext.operation("budgetScenarioUpdate");
        return of(repository.saveAndFlush(scenario), budgetService.baseline(scenario.getRevivalId()));
    }

    /**
     * Marks the scenario removed; it stays in the history (constitution IV).
     */
    @Transactional
    public void remove(final Long id, final Long version) {
        final BudgetScenario scenario = changeable(callerService.current(), id, version);

        AuditContext.operation("budgetScenarioRemove");
        scenario.setRemovedAt(clock.instant());
        repository.saveAndFlush(scenario);
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    private static ScenarioResultDto calculate(final BudgetService.Baseline baseline, final ScenarioParameters parameters) {
        return ScenarioCalculator.calculate(baseline.lines(), baseline.participantsPlanned(), baseline.participationFee(), parameters);
    }

    private static Scenario of(final BudgetScenario scenario, final BudgetService.Baseline baseline) {
        return new Scenario(scenario.getId(), scenario.getRevivalId(), scenario.getName(), scenario.getParameters(),
                calculate(baseline, scenario.getParameters()), scenario.getVersion() == null ? 0L : scenario.getVersion());
    }

    private BudgetScenario changeable(final Caller caller, final Long id, final Long version) {
        final BudgetScenario scenario = repository.findById(id).filter(candidate -> !candidate.isRemoved())
                .orElseThrow(() -> new ResourceNotFoundException(BudgetScenario.class, id));

        if (!access.canRead(caller, scenario.getRevivalId())) {
            throw new ResourceNotFoundException(BudgetScenario.class, id);
        }
        access.writable(caller, scenario.getRevivalId());
        if (!Objects.equals(version, scenario.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return scenario;
    }

    private static String validName(final @Nullable String name) {
        final String trimmed = name == null ? "" : name.strip();

        if (trimmed.isEmpty() || trimmed.length() > BudgetScenario.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.scenario.name", BudgetScenario.NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * Participants 0–2000, tickets 0–100000 and amounts 0–10000000 kronor.
     */
    static ScenarioParameters valid(final ScenarioParameters parameters) {
        count(parameters.participants(), BudgetSettings.MAX_PARTICIPANTS);
        count(parameters.showTickets(), MAX_TICKETS);
        count(parameters.partyTickets(), MAX_TICKETS);
        return new ScenarioParameters(parameters.participants(), amount(parameters.participationFee()), amount(parameters.showTicketPrice()),
                parameters.showTickets(), amount(parameters.partyTicketPrice()), parameters.partyTickets(), amount(parameters.venueCost()),
                amount(parameters.partyCost()));
    }

    private static void count(final @Nullable Integer count, final int max) {
        if (count != null && (count < 0 || count > max)) {
            throw new BadRequestException("error.scenario.count", max);
        }
    }

    private static @Nullable BigDecimal amount(final @Nullable BigDecimal amount) {
        return BudgetService.validAmount(amount, BigDecimal.ZERO);
    }
}
