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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static nu.fgv.revival.server.member.AccessPolicyTest.caller;
import static nu.fgv.revival.server.member.AccessPolicyTest.revival;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The M5 rules in {@link AccessPolicy}: who reads and writes a revival's budget (product spec 5.10's permission
 * matrix, M5-FR-6), who keeps the reference figures, and who reads the Chalmersspex dates.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
class AccessPolicyBudgetTest {

    private static final long REVIVAL_ID = 1L;

    private final AccessPolicy policy = new AccessPolicy();

    private static Revival revivalIn(final RevivalStatus status) {
        return revival(REVIVAL_ID, status);
    }

    private static Caller steeringGroupWithout() {
        final Person person = new Person();

        person.setId(10L);
        return new Caller(person, Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.STEERING_GROUP, Set.of("CATERING"))));
    }

    @ParameterizedTest(name = "{0} in {1} reads the budget: {2}")
    @CsvSource({
            "SUPER_ADMIN,    PROPOSED,   true",
            "SUPER_ADMIN,    ARCHIVED,   true",
            "OBSERVER,       ORGANISING, true",
            "OBSERVER,       ARCHIVED,   true",
            "DIRECTEUR,      PROPOSED,   false",
            "DIRECTEUR,      APPROVED,   true",
            "DIRECTEUR,      SETTLING,   true",
            "DIRECTEUR,      ARCHIVED,   false",
            "STEERING_GROUP, APPROVED,   false",
            "STEERING_GROUP, ORGANISING, true",
            "STEERING_GROUP, CLOSING,    true",
            "STEERING_GROUP, SETTLING,   false",
            "PARTICIPANT,    OPEN,       false",
            "HELPER,         OPEN,       false",
            "NONE,           OPEN,       false"
    })
    void readersOfTheBudget(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canReadBudget(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(policy.permissions(caller(role), revivalIn(status)).canReadBudget()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} in {1} writes the budget: {2}")
    @CsvSource({
            "SUPER_ADMIN,    ORGANISING, false",
            "OBSERVER,       ORGANISING, false",
            "DIRECTEUR,      PROPOSED,   false",
            "DIRECTEUR,      APPROVED,   true",
            "DIRECTEUR,      OPEN,       true",
            "DIRECTEUR,      SETTLING,   true",
            "DIRECTEUR,      ARCHIVED,   false",
            "STEERING_GROUP, APPROVED,   false",
            "STEERING_GROUP, ORGANISING, true",
            "STEERING_GROUP, CLOSING,    true",
            "STEERING_GROUP, SETTLING,   false",
            "PARTICIPANT,    OPEN,       false",
            "HELPER,         OPEN,       false",
            "NONE,           OPEN,       false"
    })
    void writersOfTheBudget(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canWriteBudget(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(policy.permissions(caller(role), revivalIn(status)).canWriteBudget()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = RevivalStatus.class, names = {"ORGANISING", "OPEN", "CLOSING"})
    void aSteeringGroupMemberWithoutTheFinanceAreaOnlyReads(final RevivalStatus status) {
        assertThat(policy.canReadBudget(steeringGroupWithout(), revivalIn(status))).isTrue();
        assertThat(policy.canWriteBudget(steeringGroupWithout(), revivalIn(status))).isFalse();
        assertThatThrownBy(() -> policy.requireWriteBudget(steeringGroupWithout(), revivalIn(status)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void participantsAndHelpersNeverReachTheBudget() {
        for (final RevivalStatus status : RevivalStatus.values()) {
            for (final String role : List.of("PARTICIPANT", "HELPER")) {
                assertThat(policy.canReadBudget(caller(role), revivalIn(status))).as("%s in %s", role, status).isFalse();
                assertThat(policy.canWriteBudget(caller(role), revivalIn(status))).as("%s in %s", role, status).isFalse();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OBSERVER", "DIRECTEUR", "STEERING_GROUP", "PARTICIPANT", "HELPER", "NONE"})
    void onlyTheSuperAdminKeepsReferenceFiguresAndTheSpexCalendar(final String role) {
        assertThat(policy.canManageReferenceFigures(caller("SUPER_ADMIN"))).isTrue();
        assertThat(policy.canManageSpexCalendar(caller("SUPER_ADMIN"))).isTrue();
        assertThat(policy.canManageReferenceFigures(caller(role))).isFalse();
        assertThat(policy.canManageSpexCalendar(caller(role))).isFalse();
    }

    @ParameterizedTest(name = "{0} reads the Chalmersspex dates: {1}")
    @CsvSource({
            "SUPER_ADMIN,    true",
            "OBSERVER,       true",
            "DIRECTEUR,      false",
            "STEERING_GROUP, false",
            "PARTICIPANT,    false",
            "NONE,           false"
    })
    void theSpexCalendarIsForSuperAdminsAndObservers(final String role, final boolean expected) {
        assertThat(policy.canReadSpexCalendar(caller(role))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} in {1} reads reference figures: {2}")
    @CsvSource({
            "SUPER_ADMIN,    PROPOSED,   true",
            "OBSERVER,       PROPOSED,   true",
            "DIRECTEUR,      APPROVED,   true",
            "STEERING_GROUP, ORGANISING, true",
            "STEERING_GROUP, APPROVED,   false",
            "PARTICIPANT,    OPEN,       false",
            "NONE,           OPEN,       false"
    })
    void referenceFiguresAreReadAsTheGuide(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canReadReferenceFigures(caller(role), List.of(revivalIn(status)))).isEqualTo(expected);
    }
}
