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

package nu.fgv.revival.server.spex;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static nu.fgv.revival.server.Documents.CREATE;
import static nu.fgv.revival.server.Documents.REVIVAL;
import static nu.fgv.revival.server.Documents.REVIVALS;
import static nu.fgv.revival.server.Documents.SPEX_SEARCH;
import static nu.fgv.revival.server.Documents.UPDATE;

/**
 * Revivals keep working while Spexregister is down; only picking a spex is blocked (FR-008a, SC-001b).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
class SpexregisterUnavailableIntegrationTest extends AbstractIntegrationTest {

    private void assertDegraded(final long id) {
        execute(SUPER_ADMIN, REVIVALS, Map.of())
                .path("revivals[0].spex.available").entity(Boolean.class).isEqualTo(false)
                .path("revivals[0].spex.title").valueIsNull();
        execute(SUPER_ADMIN, REVIVAL, Map.of("id", id)).path("revival.spex.available").entity(Boolean.class).isEqualTo(false);
        expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", Map.of("spexId", ALI_BABA, "year", 2029))), "SPEXREGISTER_UNAVAILABLE");
        expectError(execute(SUPER_ADMIN, UPDATE, Map.of("input", Map.of("id", id, "version", version(id), "spexId", NOBEL))), "SPEXREGISTER_UNAVAILABLE");
        expectError(execute(SUPER_ADMIN, SPEX_SEARCH, Map.of("query", "ali")), "SPEXREGISTER_UNAVAILABLE");
        execute(SUPER_ADMIN, UPDATE, Map.of("input", Map.of("id", id, "version", version(id), "premiereDate", "2028-02-10", "shows", java.util.List.of(Map.of("date", "2028-02-10", "venue", "Kårhuset")))))
                .path("revivalUpdate.title").entity(String.class).isEqualTo("Revival 2028");
    }

    @Test
    void anErrorResponse() {
        final long id = createRevival(ALI_BABA, 2028);

        spexregisterDown(false);
        assertDegraded(id);
    }

    @Test
    void aTimeout() {
        final long id = createRevival(ALI_BABA, 2028);

        spexregisterDown(true);
        assertDegraded(id);
    }
}
