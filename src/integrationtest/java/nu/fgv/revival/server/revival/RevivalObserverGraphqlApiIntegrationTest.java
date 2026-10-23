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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static nu.fgv.revival.server.Documents.CREATE;
import static nu.fgv.revival.server.Documents.MEMBER_ADD;
import static nu.fgv.revival.server.Documents.MEMBER_CAPABILITIES;
import static nu.fgv.revival.server.Documents.MEMBER_REMOVE;
import static nu.fgv.revival.server.Documents.REVIVAL;
import static nu.fgv.revival.server.Documents.REVIVALS;
import static nu.fgv.revival.server.Documents.SPEX_SEARCH;
import static nu.fgv.revival.server.Documents.STATUS;
import static nu.fgv.revival.server.Documents.UPDATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * User story 3: observers see everything and change nothing (FR-005, FR-015).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
class RevivalObserverGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> STATUSES = List.of("PROPOSED", "APPROVED", "ORGANISING", "OPEN", "CLOSING", "SETTLING", "ARCHIVED", "REJECTED");

    @Test
    void anObserverSeesEveryStatusAndMembersWithoutEmail() {
        final List<String> ids = new ArrayList<>();

        for (final String status : STATUSES) {
            final long id = createRevival(ALI_BABA, 2028 + ids.size());

            addMember(id, DIRECTEUR, "DIRECTEUR", List.of());
            setStatus(id, status);
            ids.add(String.valueOf(id));
        }

        execute(OBSERVER, REVIVALS, Map.of()).path("revivals[*].id").entityList(String.class)
                .satisfies(actual -> assertThat(actual).containsExactlyInAnyOrderElementsOf(ids));
        execute(OBSERVER, REVIVAL, Map.of("id", ids.getFirst()))
                .path("revival.members[0].role").entity(String.class).isEqualTo("DIRECTEUR")
                .path("revival.members[0].email").valueIsNull();
    }

    @Test
    void everyMutationIsRefused() {
        final long id = createRevival(ALI_BABA, 2028);

        setStatus(id, "ORGANISING");

        final long steeringGroupMember = addMember(id, STEERING_GROUP, "STEERING_GROUP", List.of());

        expectError(execute(OBSERVER, CREATE, Map.of("input", Map.of("spexId", ALI_BABA, "year", 2028))), "FORBIDDEN");
        expectError(execute(OBSERVER, UPDATE, Map.of("input", Map.of("id", id, "version", version(id), "shows", List.of(Map.of("date", "2028-03-01", "venue", "X"))))), "FORBIDDEN");
        expectError(execute(OBSERVER, STATUS, Map.of("input", Map.of("id", id, "from", "ORGANISING", "to", "OPEN"))), "FORBIDDEN");
        expectError(execute(OBSERVER, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", "x" + DOMAIN, "role", "STEERING_GROUP"))), "FORBIDDEN");
        expectError(execute(OBSERVER, MEMBER_CAPABILITIES, Map.of("input", Map.of("id", steeringGroupMember, "version", 0, "capabilities", List.of("FINANCE")))), "FORBIDDEN");
        expectError(execute(OBSERVER, MEMBER_REMOVE, Map.of("id", steeringGroupMember)), "FORBIDDEN");
        expectError(execute(OBSERVER, SPEX_SEARCH, Map.of("query", "ali")), "FORBIDDEN");
    }
}
