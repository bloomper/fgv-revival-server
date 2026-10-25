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

import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static nu.fgv.revival.server.member.AccessPolicyTest.caller;
import static nu.fgv.revival.server.member.AccessPolicyTest.revival;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The staffing and mailing rules in {@link AccessPolicy}: who reads and writes staffing, who
 * reads, drafts and sends mailings, and the mailing plan's templates. Participants and helpers, and observers, are
 * refused everything of the steering group's.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AccessPolicyStaffingMailingTest {

    private static final long REVIVAL_ID = 1L;

    private final AccessPolicy policy = new AccessPolicy();

    private static Revival revivalIn(final RevivalStatus status) {
        return revival(REVIVAL_ID, status);
    }

    private static Caller steeringGroupWith(final String... capabilities) {
        final Person person = new Person();

        person.setId(10L);
        return new Caller(person, Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.STEERING_GROUP, Set.of(capabilities))));
    }

    @ParameterizedTest(name = "{0} in {1} reads staffing and mailings: {2}")
    @CsvSource({
            "SUPER_ADMIN,    PROPOSED,   true",
            "SUPER_ADMIN,    ARCHIVED,   true",
            "OBSERVER,       OPEN,       false",
            "DIRECTEUR,      APPROVED,   true",
            "DIRECTEUR,      SETTLING,   true",
            "DIRECTEUR,      ARCHIVED,   false",
            "STEERING_GROUP, ORGANISING, true",
            "STEERING_GROUP, CLOSING,    true",
            "STEERING_GROUP, SETTLING,   false",
            "PARTICIPANT,    OPEN,       false",
            "HELPER,         OPEN,       false",
            "NONE,           OPEN,       false"
    })
    void readers(final String role, final RevivalStatus status, final boolean expected) {
        final RevivalPermissionsDto permissions = policy.permissions(caller(role), revivalIn(status));

        assertThat(policy.canReadStaffing(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(policy.canReadMailings(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(permissions.canReadStaffing()).isEqualTo(expected);
        assertThat(permissions.canReadMailings()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} in {1} drafts mailings: {2}")
    @CsvSource({
            "SUPER_ADMIN,    OPEN,       false",
            "OBSERVER,       OPEN,       false",
            "DIRECTEUR,      APPROVED,   true",
            "DIRECTEUR,      OPEN,       true",
            "DIRECTEUR,      ARCHIVED,   false",
            "STEERING_GROUP, OPEN,       true",
            "STEERING_GROUP, CLOSING,    true",
            "STEERING_GROUP, SETTLING,   false",
            "PARTICIPANT,    OPEN,       false",
            "HELPER,         OPEN,       false"
    })
    void drafters(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canDraftMailings(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(policy.permissions(caller(role), revivalIn(status)).canDraftMailings()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} in {1} writes staffing and sends mailings: {2}")
    @CsvSource({
            "SUPER_ADMIN,    OPEN,       false",
            "OBSERVER,       OPEN,       false",
            "DIRECTEUR,      APPROVED,   true",
            "DIRECTEUR,      OPEN,       true",
            "DIRECTEUR,      SETTLING,   true",
            "DIRECTEUR,      ARCHIVED,   false",
            "PARTICIPANT,    OPEN,       false",
            "HELPER,         OPEN,       false",
            "NONE,           OPEN,       false"
    })
    void theDirecteurWritesStaffingAndSends(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canWriteStaffing(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(policy.canSendMailings(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(policy.permissions(caller(role), revivalIn(status)).canWriteStaffing()).isEqualTo(expected);
        assertThat(policy.permissions(caller(role), revivalIn(status)).canSendMailings()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORGANISING", "OPEN", "CLOSING"})
    void theSteeringGroupNeedsItsArea(final RevivalStatus status) {
        final Revival revival = revivalIn(status);

        assertThat(policy.canWriteStaffing(steeringGroupWith(AccessPolicy.STAFFING), revival)).isTrue();
        assertThat(policy.canWriteStaffing(steeringGroupWith(AccessPolicy.COMMS, AccessPolicy.FINANCE), revival)).isFalse();
        assertThat(policy.canSendMailings(steeringGroupWith(AccessPolicy.COMMS), revival)).isTrue();
        assertThat(policy.canSendMailings(steeringGroupWith(AccessPolicy.STAFFING), revival)).isFalse();
        assertThat(policy.canDraftMailings(steeringGroupWith(), revival)).isTrue();
        assertThat(policy.canReadStaffing(steeringGroupWith(), revival)).isTrue();
    }

    @Test
    void theSteeringGroupLosesEverythingOnceItCannotSeeTheRevival() {
        final Revival settling = revivalIn(RevivalStatus.SETTLING);
        final Caller member = steeringGroupWith(AccessPolicy.STAFFING, AccessPolicy.COMMS);

        assertThat(policy.canReadStaffing(member, settling)).isFalse();
        assertThat(policy.canWriteStaffing(member, settling)).isFalse();
        assertThat(policy.canDraftMailings(member, settling)).isFalse();
        assertThat(policy.canSendMailings(member, settling)).isFalse();
    }

    @Test
    void mailingTemplatesAreReadByGuideReadersAndKeptByTheSuperAdmin() {
        final Revival open = revivalIn(RevivalStatus.OPEN);

        assertThat(policy.canReadMailingTemplates(caller("SUPER_ADMIN"), List.of())).isTrue();
        assertThat(policy.canReadMailingTemplates(caller("OBSERVER"), List.of())).isTrue();
        assertThat(policy.canReadMailingTemplates(caller("STEERING_GROUP"), List.of(open))).isTrue();
        assertThat(policy.canReadMailingTemplates(caller("PARTICIPANT"), List.of(open))).isFalse();
        assertThat(policy.canReadMailingTemplates(caller("HELPER"), List.of(open))).isFalse();
        assertThat(policy.canManageMailingTemplates(caller("SUPER_ADMIN"))).isTrue();
        assertThat(policy.canManageMailingTemplates(caller("DIRECTEUR"))).isFalse();
        assertThat(policy.canManageMailingTemplates(caller("OBSERVER"))).isFalse();
    }
}
