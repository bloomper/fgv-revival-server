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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static nu.fgv.revival.server.Documents.REVIVAL;
import static nu.fgv.revival.server.Documents.REVIVALS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every role × status combination of the visibility table, through GraphQL and MCP: 5 roles × 8 statuses.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class RevivalVisibilityIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> STATUSES = List.of("PROPOSED", "APPROVED", "ORGANISING", "OPEN", "CLOSING", "SETTLING", "ARCHIVED", "REJECTED");
    private static final Map<String, List<String>> VISIBLE = Map.of(
            "SUPER_ADMIN", STATUSES,
            "OBSERVER", STATUSES,
            "DIRECTEUR", List.of("APPROVED", "ORGANISING", "OPEN", "CLOSING", "SETTLING"),
            "STEERING_GROUP", List.of("ORGANISING", "OPEN", "CLOSING"),
            "NONE", List.of());
    private static final Map<String, String> USERS = Map.of(
            "SUPER_ADMIN", SUPER_ADMIN,
            "OBSERVER", OBSERVER,
            "DIRECTEUR", DIRECTEUR,
            "STEERING_GROUP", STEERING_GROUP,
            "NONE", PLAIN_USER);

    static Stream<Arguments> combinations() {
        final List<Arguments> combinations = new ArrayList<>();

        for (final String role : List.of("SUPER_ADMIN", "OBSERVER", "DIRECTEUR", "STEERING_GROUP", "NONE")) {
            for (final String status : STATUSES) {
                combinations.add(Arguments.of(role, status, VISIBLE.get(role).contains(status)));
            }
        }
        return combinations.stream();
    }

    @ParameterizedTest(name = "{0} in {1} sees it: {2}")
    @MethodSource("combinations")
    void graphQlAndMcpMatchTheVisibilityTable(final String role, final String status, final boolean visible) {
        final long id = createRevival(ALI_BABA, 2028);
        final String user = USERS.get(role);

        addMember(id, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(id, STEERING_GROUP, "STEERING_GROUP", List.of("FINANCE"));
        setStatus(id, status);

        final List<String> listed = execute(user, REVIVALS, Map.of()).path("revivals[*].id").entityList(String.class).get();

        if (visible) {
            assertThat(listed).containsExactly(String.valueOf(id));
            execute(user, REVIVAL, Map.of("id", id)).path("revival.id").entity(String.class).isEqualTo(String.valueOf(id));
        } else {
            assertThat(listed).isEmpty();
            expectError(execute(user, REVIVAL, Map.of("id", id)), "NOT_FOUND");
        }

        final JsonNode mcp = listRevivals(mcpToken(user), Map.of());
        final List<String> mcpIds = new ArrayList<>();

        mcp.forEach(item -> mcpIds.add(item.get("id").asString()));
        assertThat(mcpIds).isEqualTo(listed);
    }
}
