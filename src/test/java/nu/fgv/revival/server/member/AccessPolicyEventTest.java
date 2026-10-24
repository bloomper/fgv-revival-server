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

import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static nu.fgv.revival.server.member.AccessPolicyTest.caller;
import static nu.fgv.revival.server.member.AccessPolicyTest.revival;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M4 rules in {@link AccessPolicy}: events follow the rules of the revival's other work, the super admin keeps the
 * event types, and a calendar link needs a role in a revival the caller can see.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
class AccessPolicyEventTest {

    private static final long REVIVAL_ID = 1L;

    private final AccessPolicy policy = new AccessPolicy();

    private static Revival revivalIn(final RevivalStatus status) {
        return revival(REVIVAL_ID, status);
    }

    @ParameterizedTest
    @EnumSource(RevivalStatus.class)
    void superAdminsAndObserversOnlyReadEvents(final RevivalStatus status) {
        for (final String role : List.of("SUPER_ADMIN", "OBSERVER")) {
            assertThat(policy.canReadRevivalWork(caller(role), revivalIn(status))).as("%s in %s", role, status).isTrue();
            assertThat(policy.canWriteRevivalWork(caller(role), revivalIn(status))).as("%s in %s", role, status).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OBSERVER", "DIRECTEUR", "STEERING_GROUP", "PARTICIPANT", "HELPER", "NONE"})
    void onlyTheSuperAdminManagesEventTypes(final String role) {
        assertThat(policy.canManageEventTypes(caller("SUPER_ADMIN"))).isTrue();
        assertThat(policy.canManageEventTypes(caller(role))).isFalse();
    }

    @ParameterizedTest(name = "{0} in {1} subscribes: {2}")
    @CsvSource({
            "SUPER_ADMIN,    ORGANISING, false",
            "OBSERVER,       ORGANISING, false",
            "DIRECTEUR,      PROPOSED,   false",
            "DIRECTEUR,      APPROVED,   true",
            "DIRECTEUR,      ORGANISING, true",
            "DIRECTEUR,      SETTLING,   true",
            "DIRECTEUR,      ARCHIVED,   false",
            "STEERING_GROUP, APPROVED,   false",
            "STEERING_GROUP, ORGANISING, true",
            "STEERING_GROUP, CLOSING,    true",
            "STEERING_GROUP, SETTLING,   false",
            "PARTICIPANT,    OPEN,       true",
            "PARTICIPANT,    ORGANISING, false",
            "PARTICIPANT,    CLOSING,    false",
            "HELPER,         OPEN,       true",
            "HELPER,         ORGANISING, false",
            "NONE,           OPEN,       false"
    })
    void aCalendarLinkNeedsARoleInAVisibleRevival(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canSubscribeCalendar(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(policy.permissions(caller(role), revivalIn(status)).canSubscribeCalendar()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} in {1} sees meetings in the calendar: {2}")
    @CsvSource({
            "DIRECTEUR,      ORGANISING, true",
            "DIRECTEUR,      ARCHIVED,   false",
            "STEERING_GROUP, OPEN,       true",
            "PARTICIPANT,    OPEN,       false",
            "HELPER,         OPEN,       false",
            "SUPER_ADMIN,    OPEN,       false"
    })
    void meetingsInTheCalendarAreForTheSteeringGroup(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canSeeMeetingsInCalendar(caller(role), revivalIn(status))).isEqualTo(expected);
    }
}
