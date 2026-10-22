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

package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static nu.fgv.revival.server.Documents.REVIVAL;
import static nu.fgv.revival.server.Documents.REVIVALS;
import static nu.fgv.revival.server.Documents.STATUS;
import static nu.fgv.revival.server.Documents.UPDATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * User story 2: the directeur runs their revival through its lifecycle (FR-009, FR-011, FR-014–FR-017).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
class RevivalDirecteurGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private long own;
    private long other;
    private long proposed;

    @BeforeEach
    void setUp() {
        own = createRevival(ALI_BABA, 2028);
        other = createRevival(NOBEL, 2029);
        proposed = createRevival(SNORRE, 2030);
        setStatus(own, "APPROVED");
        setStatus(other, "APPROVED");
        addMember(own, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(proposed, DIRECTEUR, "DIRECTEUR", List.of());
    }

    private Map<String, Object> details(final long id, final String premiere, final List<String> showDates) {
        return Map.of("input", Map.of("id", id, "version", version(id), "premiereDate", premiere, "showDates", showDates, "venues", List.of("Chalmers kårhus")));
    }

    @Test
    void theDirecteurSeesOnlyTheirVisibleRevival() {
        execute(DIRECTEUR, REVIVALS, Map.of()).path("revivals[*].id").entityList(String.class).containsExactly(String.valueOf(own));
        expectError(execute(DIRECTEUR, REVIVAL, Map.of("id", other)), "NOT_FOUND");
        expectError(execute(DIRECTEUR, REVIVAL, Map.of("id", proposed)), "NOT_FOUND");
        expectError(execute(DIRECTEUR, REVIVAL, Map.of("id", 999_999)), "NOT_FOUND");
    }

    @Test
    void detailsCanBeEditedButNotCoreFields() {
        execute(DIRECTEUR, UPDATE, details(own, "2028-02-10", List.of("2028-02-10", "2028-02-12")))
                .path("revivalUpdate.premiereDate").entity(String.class).isEqualTo("2028-02-10")
                .path("revivalUpdate.showDates").entityList(String.class).containsExactly("2028-02-10", "2028-02-12")
                .path("revivalUpdate.venues").entityList(String.class).containsExactly("Chalmers kårhus");
        expectError(execute(DIRECTEUR, UPDATE, Map.of("input", Map.of("id", own, "version", version(own), "year", 2029))), "FORBIDDEN");
    }

    @Test
    void aShowDateBeforeThePremiereIsRefused() {
        expectError(execute(DIRECTEUR, UPDATE, details(own, "2028-02-10", List.of("2028-02-09"))), "BAD_REQUEST");
        expectError(execute(DIRECTEUR, UPDATE, details(own, "2029-02-10", List.of())), "BAD_REQUEST");
    }

    @Test
    void eachForwardStepSucceedsAndEverythingElseIsRefused() {
        final List<String> steps = List.of("APPROVED", "ORGANISING", "OPEN", "CLOSING", "SETTLING");

        for (int i = 0; i < steps.size() - 1; i++) {
            final String from = steps.get(i);

            for (final String refused : List.of("ARCHIVED", "REJECTED", "PROPOSED")) {
                expectError(execute(DIRECTEUR, STATUS, Map.of("input", Map.of("id", own, "from", from, "to", refused))), "FORBIDDEN");
            }
            if (i + 2 < steps.size()) {
                expectError(execute(DIRECTEUR, STATUS, Map.of("input", Map.of("id", own, "from", from, "to", steps.get(i + 2)))), "FORBIDDEN");
            }
            if (i > 0) {
                expectError(execute(DIRECTEUR, STATUS, Map.of("input", Map.of("id", own, "from", from, "to", steps.get(i - 1)))), "FORBIDDEN");
            }
            execute(DIRECTEUR, STATUS, Map.of("input", Map.of("id", own, "from", from, "to", steps.get(i + 1))))
                    .path("revivalStatusChange.status").entity(String.class).isEqualTo(steps.get(i + 1));
        }
        expectError(execute(DIRECTEUR, STATUS, Map.of("input", Map.of("id", own, "from", "SETTLING", "to", "ARCHIVED"))), "FORBIDDEN");
        assertThat(status(own)).isEqualTo("SETTLING");
    }

    @Test
    void aStaleFromIsAConflict() {
        setStatus(own, "ORGANISING");

        expectError(execute(DIRECTEUR, STATUS, Map.of("input", Map.of("id", own, "from", "APPROVED", "to", "ORGANISING"))), "CONFLICT");
    }

    @Test
    void aStaleVersionIsAConflict() {
        final long stale = version(own);

        execute(DIRECTEUR, UPDATE, details(own, "2028-02-10", List.of()));
        expectError(execute(DIRECTEUR, UPDATE, Map.of("input", Map.of("id", own, "version", stale, "venues", List.of("Annan scen")))), "CONFLICT");
    }

    @Test
    void twoDirecteursCanBothEdit() {
        addMember(own, DIRECTEUR_2, "DIRECTEUR", List.of());

        execute(DIRECTEUR, UPDATE, details(own, "2028-02-10", List.of()));
        execute(DIRECTEUR_2, UPDATE, Map.of("input", Map.of("id", own, "version", version(own), "venues", List.of("Lindholmen"))))
                .path("revivalUpdate.venues").entityList(String.class).containsExactly("Lindholmen");
    }

    @Test
    void anArchivedRevivalDisappears() {
        setStatus(own, "SETTLING");
        execute(DIRECTEUR, REVIVAL, Map.of("id", own)).path("revival.id").hasValue();

        setStatus(own, "ARCHIVED");

        expectError(execute(DIRECTEUR, REVIVAL, Map.of("id", own)), "NOT_FOUND");
        execute(DIRECTEUR, REVIVALS, Map.of()).path("revivals").entityList(Object.class).hasSize(0);
    }

    @Test
    void permissionsTellTheWebWhatTheDirecteurMayDo() {
        execute(DIRECTEUR, REVIVAL, Map.of("id", own))
                .path("revival.permissions.canEditCore").entity(Boolean.class).isEqualTo(false)
                .path("revival.permissions.canEditDetails").entity(Boolean.class).isEqualTo(true)
                .path("revival.permissions.allowedStatuses").entityList(String.class).containsExactly("ORGANISING")
                .path("revival.permissions.canManageDirecteurs").entity(Boolean.class).isEqualTo(false)
                .path("revival.permissions.canManageSteeringGroup").entity(Boolean.class).isEqualTo(true);
    }
}
