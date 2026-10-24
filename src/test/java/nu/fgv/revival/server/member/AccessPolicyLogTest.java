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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static nu.fgv.revival.server.member.AccessPolicyTest.caller;
import static nu.fgv.revival.server.member.AccessPolicyTest.revival;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table-driven tests of the decision log, meeting and checklist rules in {@link AccessPolicy}, one per row of the
 * operation rules in specs/004-m3-log/contracts/README.md (constitution III, FR-006, SC-006). Waiting and removed
 * members hold no role in their {@code Caller}, so they are {@code NONE} here.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class AccessPolicyLogTest {

    private static final long REVIVAL_ID = 1L;

    private final AccessPolicy policy = new AccessPolicy();

    private static Revival revivalIn(final RevivalStatus status) {
        return revival(REVIVAL_ID, status);
    }

    @Nested
    class Read {

        @ParameterizedTest
        @EnumSource(RevivalStatus.class)
        void readingFollowsVisibility(final RevivalStatus status) {
            // Participants and helpers see an open revival only for their own page (M6-FR-1)
            for (final String role : List.of("SUPER_ADMIN", "OBSERVER", "DIRECTEUR", "STEERING_GROUP", "NONE")) {
                assertThat(policy.canReadRevivalWork(caller(role), revivalIn(status)))
                        .as("%s in %s", role, status)
                        .isEqualTo(policy.canSee(caller(role), revivalIn(status)));
            }
        }

        @ParameterizedTest
        @EnumSource(RevivalStatus.class)
        void participantsAndHelpersNeverRead(final RevivalStatus status) {
            assertThat(policy.canReadRevivalWork(caller("PARTICIPANT"), revivalIn(status))).isFalse();
            assertThat(policy.canReadRevivalWork(caller("HELPER"), revivalIn(status))).isFalse();
        }
    }

    @Nested
    class Write {

        @ParameterizedTest(name = "{0} in {1} writes: {2}")
        @CsvSource({
                "DIRECTEUR,      PROPOSED,   false",
                "DIRECTEUR,      APPROVED,   true",
                "DIRECTEUR,      ORGANISING, true",
                "DIRECTEUR,      OPEN,       true",
                "DIRECTEUR,      CLOSING,    true",
                "DIRECTEUR,      SETTLING,   true",
                "DIRECTEUR,      ARCHIVED,   false",
                "DIRECTEUR,      REJECTED,   false",
                "STEERING_GROUP, APPROVED,   false",
                "STEERING_GROUP, ORGANISING, true",
                "STEERING_GROUP, OPEN,       true",
                "STEERING_GROUP, CLOSING,    true",
                "STEERING_GROUP, SETTLING,   false",
                "STEERING_GROUP, ARCHIVED,   false"
        })
        void directeursAndSteeringGroupWriteWhileVisibleAndNotArchived(final String role, final RevivalStatus status, final boolean expected) {
            assertThat(policy.canWriteRevivalWork(caller(role), revivalIn(status))).isEqualTo(expected);
        }

        @ParameterizedTest
        @EnumSource(RevivalStatus.class)
        void superAdminsObserversParticipantsHelpersAndOthersNeverWrite(final RevivalStatus status) {
            for (final String role : List.of("SUPER_ADMIN", "OBSERVER", "PARTICIPANT", "HELPER", "NONE")) {
                assertThat(policy.canWriteRevivalWork(caller(role), revivalIn(status))).as("%s in %s", role, status).isFalse();
                assertThatThrownBy(() -> policy.requireWriteRevivalWork(caller(role), revivalIn(status))).isInstanceOf(AccessDeniedException.class);
            }
        }

        @Test
        void aRoleInAnotherRevivalDoesNotCount() {
            assertThat(policy.canWriteRevivalWork(caller("DIRECTEUR"), revival(2L, RevivalStatus.ORGANISING))).isFalse();
        }
    }

    @Nested
    class DirecteurOnly {

        @ParameterizedTest(name = "{0} in {1}: {2}")
        @CsvSource({
                "DIRECTEUR,      APPROVED,   true",
                "DIRECTEUR,      ORGANISING, true",
                "DIRECTEUR,      SETTLING,   true",
                "DIRECTEUR,      ARCHIVED,   false",
                "DIRECTEUR,      PROPOSED,   false",
                "STEERING_GROUP, ORGANISING, false",
                "SUPER_ADMIN,    ORGANISING, false",
                "OBSERVER,       ORGANISING, false",
                "PARTICIPANT,    OPEN,       false",
                "NONE,           ORGANISING, false"
        })
        void onlyAVisibleDirecteurWaivesRequirements(final String role, final RevivalStatus status, final boolean expected) {
            assertThat(policy.canWaiveRequirement(caller(role), revivalIn(status))).isEqualTo(expected);
            if (!expected) {
                assertThatThrownBy(() -> policy.requireWaiveRequirement(caller(role), revivalIn(status))).isInstanceOf(AccessDeniedException.class);
            }
        }
    }

    @Nested
    class Templates {

        @ParameterizedTest
        @EnumSource(RevivalStatus.class)
        void readingTemplatesFollowsReadingTheGuide(final RevivalStatus status) {
            for (final String role : List.of("SUPER_ADMIN", "OBSERVER", "DIRECTEUR", "STEERING_GROUP", "PARTICIPANT", "NONE")) {
                final List<Revival> revivals = List.of(revivalIn(status));

                assertThat(policy.canReadChecklistTemplates(caller(role), revivals))
                        .as("%s in %s", role, status)
                        .isEqualTo(policy.canReadGuide(caller(role), revivals));
            }
        }

        @Test
        void onlySuperAdminsManageTemplates() {
            assertThat(policy.canManageChecklistTemplates(caller("SUPER_ADMIN"))).isTrue();
            for (final String role : List.of("OBSERVER", "DIRECTEUR", "STEERING_GROUP", "PARTICIPANT", "NONE")) {
                assertThat(policy.canManageChecklistTemplates(caller(role))).isFalse();
                assertThatThrownBy(() -> policy.requireManageChecklistTemplates(caller(role))).isInstanceOf(AccessDeniedException.class);
            }
        }
    }

    @Nested
    class Permissions {

        @Test
        void permissionsCarryTheLogRules() {
            final RevivalPermissionsDto directeur = policy.permissions(caller("DIRECTEUR"), revivalIn(RevivalStatus.ORGANISING));
            final RevivalPermissionsDto steeringGroup = policy.permissions(caller("STEERING_GROUP"), revivalIn(RevivalStatus.ORGANISING));
            final RevivalPermissionsDto observer = policy.permissions(caller("OBSERVER"), revivalIn(RevivalStatus.ORGANISING));

            assertThat(directeur.canWriteLog()).isTrue();
            assertThat(directeur.canWaiveRequirement()).isTrue();
            assertThat(steeringGroup.canWriteLog()).isTrue();
            assertThat(steeringGroup.canWaiveRequirement()).isFalse();
            assertThat(observer.canWriteLog()).isFalse();
            assertThat(observer.canWaiveRequirement()).isFalse();
        }
    }
}
