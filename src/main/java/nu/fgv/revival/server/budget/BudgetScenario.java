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

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.fgv.revival.server.util.AbstractAuditable;
import org.hibernate.envers.Audited;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A saved what-if over a revival's budget (product spec 5.10, M5-FR-10). Its result is computed when it is read, from
 * the budget as it is then. Removed rather than deleted.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Entity
@Table(name = "budget_scenario")
@Audited
@NoArgsConstructor
@Getter
@Setter
public class BudgetScenario extends AbstractAuditable {

    public static final int NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revival_id", nullable = false, updatable = false)
    private Long revivalId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Convert(converter = ScenarioParametersConverter.class)
    @Column(name = "parameters", nullable = false, columnDefinition = "JSON")
    private ScenarioParameters parameters = ScenarioParameters.none();

    @Column(name = "removed_at")
    private @Nullable Instant removedAt;

    public boolean isRemoved() {
        return removedAt != null;
    }
}
