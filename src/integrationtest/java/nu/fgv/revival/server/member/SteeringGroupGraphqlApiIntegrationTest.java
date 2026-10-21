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

package nu.fgv.revival.server.member;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.util.List;
import java.util.Map;

import static nu.fgv.revival.server.Documents.MEMBER_ADD;
import static nu.fgv.revival.server.Documents.MEMBER_CAPABILITIES;
import static nu.fgv.revival.server.Documents.MEMBER_REMOVE;
import static nu.fgv.revival.server.Documents.REVIVAL;
import static nu.fgv.revival.server.Documents.STATUS;
import static nu.fgv.revival.server.Documents.UPDATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * User story 4: the directeur builds the steering group (FR-003, FR-019a, FR-019c, FR-020).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
class SteeringGroupGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private long id;

    @BeforeEach
    void setUp() {
        id = createRevival(ALI_BABA, 2028, "Ali Baba 2028");
        setStatus(id, "APPROVED");
        addMember(id, DIRECTEUR, "DIRECTEUR", List.of());
    }

    private String addSteeringGroupMember(final String user, final String email, final List<String> capabilities) {
        final GraphQlTester.Response response = execute(user, MEMBER_ADD,
                Map.of("input", Map.of("revivalId", id, "email", email, "role", "STEERING_GROUP", "capabilities", capabilities)));

        response.path("revivalMemberAdd.capabilities").entityList(String.class)
                .satisfies(actual -> assertThat(actual).containsExactlyInAnyOrderElementsOf(capabilities));
        return response.path("revivalMemberAdd.id").entity(String.class).get();
    }

    private long memberVersion(final String memberId) {
        return jdbcClient.sql("SELECT version FROM revival_member WHERE id = ?").param(Long.parseLong(memberId)).query(Long.class).single();
    }

    @Test
    void theDirecteurAddsSetsCapabilitiesAndRemoves() {
        final String memberId = addSteeringGroupMember(DIRECTEUR, "Ny.Styrgrupp" + DOMAIN, List.of("FINANCE"));

        execute(DIRECTEUR, MEMBER_CAPABILITIES, Map.of("input", Map.of("id", memberId, "version", memberVersion(memberId), "capabilities", List.of("CATERING", "COMMS"))))
                .path("revivalMemberCapabilitiesSet.capabilities").entityList(String.class)
                .satisfies(actual -> assertThat(actual).containsExactlyInAnyOrder("CATERING", "COMMS"));
        execute(DIRECTEUR, MEMBER_REMOVE, Map.of("id", memberId)).errors().verify();
        expectError(execute(DIRECTEUR, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", "x" + DOMAIN, "role", "DIRECTEUR"))), "FORBIDDEN");
    }

    @Test
    void aSteeringGroupMemberCannotChangeAnything() {
        setStatus(id, "ORGANISING");

        final String self = addSteeringGroupMember(DIRECTEUR, STEERING_GROUP, List.of("FINANCE"));

        expectError(execute(STEERING_GROUP, MEMBER_ADD, Map.of("input", Map.of("revivalId", id, "email", "x" + DOMAIN, "role", "STEERING_GROUP"))), "FORBIDDEN");
        expectError(execute(STEERING_GROUP, MEMBER_REMOVE, Map.of("id", self)), "FORBIDDEN");
        expectError(execute(STEERING_GROUP, MEMBER_CAPABILITIES, Map.of("input", Map.of("id", self, "version", memberVersion(self), "capabilities", List.of("COMMS")))), "FORBIDDEN");
        expectError(execute(STEERING_GROUP, UPDATE, Map.of("input", Map.of("id", id, "version", version(id), "venues", List.of("X")))), "FORBIDDEN");
        expectError(execute(STEERING_GROUP, STATUS, Map.of("input", Map.of("id", id, "from", "ORGANISING", "to", "OPEN"))), "FORBIDDEN");
    }

    @Test
    void emailsAreShownOnlyToDirecteursAndSuperAdmins() {
        setStatus(id, "ORGANISING");
        addSteeringGroupMember(DIRECTEUR, STEERING_GROUP, List.of());
        addSteeringGroupMember(DIRECTEUR, "vantar" + DOMAIN, List.of("COMMS"));

        for (final String user : List.of(STEERING_GROUP, OBSERVER)) {
            execute(user, REVIVAL, Map.of("id", id))
                    .path("revival.members[*].email").entityList(Object.class)
                    .satisfies(emails -> assertThat(emails).hasSize(3).containsOnlyNulls())
                    .path("revival.members[2].state").entity(String.class).isEqualTo("WAITING")
                    .path("revival.members[2].capabilities").entityList(String.class).containsExactly("COMMS");
        }
        for (final String user : List.of(DIRECTEUR, SUPER_ADMIN)) {
            execute(user, REVIVAL, Map.of("id", id))
                    .path("revival.members[*].email").entityList(String.class).contains(DIRECTEUR, "vantar" + DOMAIN);
        }
    }

    @Test
    void theMemberSeesTheRevivalOnlyWhileOrganisingOpenOrClosing() {
        addSteeringGroupMember(DIRECTEUR, STEERING_GROUP, List.of());

        for (final String status : List.of("APPROVED", "ORGANISING", "OPEN", "CLOSING", "SETTLING", "ARCHIVED", "REJECTED", "PROPOSED")) {
            setStatus(id, status);

            final boolean visible = List.of("ORGANISING", "OPEN", "CLOSING").contains(status);

            if (visible) {
                execute(STEERING_GROUP, REVIVAL, Map.of("id", id)).path("revival.id").hasValue();
            } else {
                expectError(execute(STEERING_GROUP, REVIVAL, Map.of("id", id)), "NOT_FOUND");
            }
        }
    }

    @Test
    void aRemovedMemberLosesSightOnTheNextRequest() {
        setStatus(id, "OPEN");

        final String memberId = addSteeringGroupMember(DIRECTEUR, STEERING_GROUP, List.of());

        execute(STEERING_GROUP, REVIVAL, Map.of("id", id)).path("revival.id").hasValue();
        execute(DIRECTEUR, MEMBER_REMOVE, Map.of("id", memberId)).errors().verify();
        expectError(execute(STEERING_GROUP, REVIVAL, Map.of("id", id)), "NOT_FOUND");
    }

    @Test
    void aSuperAdminManagesTheSteeringGroupAsADirecteurCan() {
        final String memberId = addSteeringGroupMember(SUPER_ADMIN, "admin-lagd" + DOMAIN, List.of("STAFFING"));

        execute(SUPER_ADMIN, MEMBER_CAPABILITIES, Map.of("input", Map.of("id", memberId, "version", memberVersion(memberId), "capabilities", List.of())))
                .path("revivalMemberCapabilitiesSet.capabilities").entityList(String.class).hasSize(0);
        execute(SUPER_ADMIN, MEMBER_REMOVE, Map.of("id", memberId)).errors().verify();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM revival_member WHERE removed_at IS NOT NULL").query(Integer.class).single()).isEqualTo(1);
    }
}
