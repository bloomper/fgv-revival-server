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
import nu.fgv.revival.server.person.GlobalRole;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table-driven tests of every rule in {@link AccessPolicy} (constitution III, SC-002, SC-003).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
class AccessPolicyTest {

    private static final long REVIVAL_ID = 1L;
    private static final long OTHER_REVIVAL_ID = 2L;

    private final AccessPolicy policy = new AccessPolicy();

    /**
     * A caller in one of the five roles of the visibility table, for revival {@value #REVIVAL_ID}.
     */
    static Caller caller(final String role) {
        final Person person = new Person();
        final Set<GlobalRole> globalRoles = EnumSet.noneOf(GlobalRole.class);
        final Map<Long, RevivalRoleAssignment> revivalRoles = new HashMap<>();

        person.setId(10L);
        switch (role) {
            case "SUPER_ADMIN" -> globalRoles.add(GlobalRole.SUPER_ADMIN);
            case "OBSERVER" -> globalRoles.add(GlobalRole.OBSERVER);
            case "DIRECTEUR" -> revivalRoles.put(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.DIRECTEUR, Set.of()));
            case "STEERING_GROUP" -> revivalRoles.put(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.STEERING_GROUP, Set.of(Capability.FINANCE)));
            case "PARTICIPANT" -> revivalRoles.put(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.PARTICIPANT, Set.of()));
            case "HELPER" -> revivalRoles.put(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.HELPER, Set.of()));
            case "NONE" -> {
            }
            default -> throw new IllegalArgumentException(role);
        }
        return new Caller(person, globalRoles, revivalRoles);
    }

    static Revival revival(final long id, final RevivalStatus status) {
        final Revival revival = new Revival();

        revival.setId(id);
        revival.setStatus(status);
        return revival;
    }

    private static Revival revival(final RevivalStatus status) {
        return revival(REVIVAL_ID, status);
    }

    @Nested
    @DisplayName("Visibility (FR-015)")
    class Visibility {

        @ParameterizedTest(name = "{0} in {1} sees: {2}")
        @CsvSource({
                "SUPER_ADMIN,    PROPOSED,   true",
                "SUPER_ADMIN,    APPROVED,   true",
                "SUPER_ADMIN,    ORGANISING, true",
                "SUPER_ADMIN,    OPEN,       true",
                "SUPER_ADMIN,    CLOSING,    true",
                "SUPER_ADMIN,    SETTLING,   true",
                "SUPER_ADMIN,    ARCHIVED,   true",
                "SUPER_ADMIN,    REJECTED,   true",
                "OBSERVER,       PROPOSED,   true",
                "OBSERVER,       APPROVED,   true",
                "OBSERVER,       ORGANISING, true",
                "OBSERVER,       OPEN,       true",
                "OBSERVER,       CLOSING,    true",
                "OBSERVER,       SETTLING,   true",
                "OBSERVER,       ARCHIVED,   true",
                "OBSERVER,       REJECTED,   true",
                "DIRECTEUR,      PROPOSED,   false",
                "DIRECTEUR,      APPROVED,   true",
                "DIRECTEUR,      ORGANISING, true",
                "DIRECTEUR,      OPEN,       true",
                "DIRECTEUR,      CLOSING,    true",
                "DIRECTEUR,      SETTLING,   true",
                "DIRECTEUR,      ARCHIVED,   false",
                "DIRECTEUR,      REJECTED,   false",
                "STEERING_GROUP, PROPOSED,   false",
                "STEERING_GROUP, APPROVED,   false",
                "STEERING_GROUP, ORGANISING, true",
                "STEERING_GROUP, OPEN,       true",
                "STEERING_GROUP, CLOSING,    true",
                "STEERING_GROUP, SETTLING,   false",
                "STEERING_GROUP, ARCHIVED,   false",
                "STEERING_GROUP, REJECTED,   false",
                "NONE,           PROPOSED,   false",
                "NONE,           APPROVED,   false",
                "NONE,           ORGANISING, false",
                "NONE,           OPEN,       false",
                "NONE,           CLOSING,    false",
                "NONE,           SETTLING,   false",
                "NONE,           ARCHIVED,   false",
                "NONE,           REJECTED,   false"
        })
        void matchesTheVisibilityTable(final String role, final RevivalStatus status, final boolean visible) {
            assertThat(policy.canSee(caller(role), revival(status))).isEqualTo(visible);
        }

        @ParameterizedTest
        @EnumSource(RevivalStatus.class)
        void aRoleInAnotherRevivalGrantsNothing(final RevivalStatus status) {
            assertThat(policy.canSee(caller("DIRECTEUR"), revival(OTHER_REVIVAL_ID, status))).isFalse();
            assertThat(policy.canSee(caller("STEERING_GROUP"), revival(OTHER_REVIVAL_ID, status))).isFalse();
        }

        @Test
        void participantsAndHelpersSeeNothingInM1() {
            assertThat(AccessPolicy.visibleStatuses(RevivalRole.PARTICIPANT)).isEmpty();
            assertThat(AccessPolicy.visibleStatuses(RevivalRole.HELPER)).isEmpty();
        }

        @Test
        void aMissingOrInvisibleRevivalIsNotFound() {
            assertThatThrownBy(() -> policy.requireVisible(caller("NONE"), revival(RevivalStatus.OPEN), REVIVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> policy.requireVisible(caller("SUPER_ADMIN"), null, REVIVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Status changes (FR-013, FR-014)")
    class StatusChanges {

        @ParameterizedTest
        @EnumSource(RevivalStatus.class)
        void superAdminGetsEveryOtherStatus(final RevivalStatus status) {
            final List<RevivalStatus> expected = Arrays.stream(RevivalStatus.values()).filter(s -> s != status).toList();

            assertThat(policy.allowedStatuses(caller("SUPER_ADMIN"), revival(status))).containsExactlyElementsOf(expected);
        }

        @ParameterizedTest(name = "directeur in {0} may go to {1}")
        @CsvSource({
                "PROPOSED,",
                "APPROVED,   ORGANISING",
                "ORGANISING, OPEN",
                "OPEN,       CLOSING",
                "CLOSING,    SETTLING",
                "SETTLING,",
                "ARCHIVED,",
                "REJECTED,"
        })
        void directeurGetsOnlyTheNextStep(final RevivalStatus status, final @Nullable RevivalStatus next) {
            final List<RevivalStatus> allowed = policy.allowedStatuses(caller("DIRECTEUR"), revival(status));

            assertThat(allowed).isEqualTo(next == null ? List.of() : List.of(next));
        }

        @ParameterizedTest
        @EnumSource(RevivalStatus.class)
        void othersGetNothing(final RevivalStatus status) {
            assertThat(policy.allowedStatuses(caller("OBSERVER"), revival(status))).isEmpty();
            assertThat(policy.allowedStatuses(caller("STEERING_GROUP"), revival(status))).isEmpty();
            assertThat(policy.allowedStatuses(caller("NONE"), revival(status))).isEmpty();
        }

        @Test
        void aDirecteurSkippingAStepIsRefused() {
            assertThatThrownBy(() -> policy.requireStatusChange(caller("DIRECTEUR"), revival(RevivalStatus.APPROVED), RevivalStatus.OPEN))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Editing and members")
    class Editing {

        @ParameterizedTest(name = "{0} in {1}: core {2}, details {3}, directeurs {4}, steering group {5}, emails {6}")
        @CsvSource({
                "SUPER_ADMIN,    OPEN,       true,  true,  true,  true,  true",
                "SUPER_ADMIN,    ARCHIVED,   false, false, false, false, true",
                "OBSERVER,       OPEN,       false, false, false, false, false",
                "DIRECTEUR,      APPROVED,   false, true,  false, true,  true",
                "DIRECTEUR,      SETTLING,   false, true,  false, true,  true",
                "DIRECTEUR,      PROPOSED,   false, false, false, false, false",
                "DIRECTEUR,      ARCHIVED,   false, false, false, false, false",
                "STEERING_GROUP, OPEN,       false, false, false, false, false",
                "NONE,           OPEN,       false, false, false, false, false"
        })
        void permissions(final String role, final RevivalStatus status,
                         final boolean core, final boolean details, final boolean directeurs,
                         final boolean steeringGroup, final boolean emails) {
            final Caller caller = caller(role);
            final Revival revival = revival(status);

            assertThat(policy.canEditCore(caller, revival)).as("core").isEqualTo(core);
            assertThat(policy.canEditDetails(caller, revival)).as("details").isEqualTo(details);
            assertThat(policy.canManageDirecteurs(caller, revival)).as("directeurs").isEqualTo(directeurs);
            assertThat(policy.canManageSteeringGroup(caller, revival)).as("steering group").isEqualTo(steeringGroup);
            assertThat(policy.canSeeMemberEmails(caller, revival)).as("emails").isEqualTo(emails);
        }

        @Test
        void onlySuperAdminsSearchSpexAndCreateRevivals() {
            for (final String role : List.of("OBSERVER", "DIRECTEUR", "STEERING_GROUP", "NONE")) {
                assertThat(policy.canSearchSpex(caller(role))).as(role).isFalse();
                assertThat(policy.canCreateRevival(caller(role))).as(role).isFalse();
            }
            assertThat(policy.canSearchSpex(caller("SUPER_ADMIN"))).isTrue();
            assertThat(policy.canCreateRevival(caller("SUPER_ADMIN"))).isTrue();
        }

        @Test
        void participantsAndHelpersCannotBeManaged() {
            final Revival revival = revival(RevivalStatus.OPEN);

            assertThat(policy.canManageMembers(caller("SUPER_ADMIN"), revival, RevivalRole.PARTICIPANT)).isFalse();
            assertThat(policy.canManageMembers(caller("SUPER_ADMIN"), revival, RevivalRole.HELPER)).isFalse();
        }

        @Test
        void aDirecteurCannotManageDirecteurs() {
            assertThatThrownBy(() -> policy.requireManageMembers(caller("DIRECTEUR"), revival(RevivalStatus.OPEN), RevivalRole.DIRECTEUR))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        void superAdminWhoIsAlsoDirecteurKeepsEverySuperAdminPermission() {
            final Person person = new Person();
            final Caller caller = new Caller(person, Set.of(GlobalRole.SUPER_ADMIN),
                    Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.DIRECTEUR, Set.of())));

            for (final RevivalStatus status : RevivalStatus.values()) {
                assertThat(policy.canSee(caller, revival(status))).isTrue();
                assertThat(policy.allowedStatuses(caller, revival(status))).hasSize(RevivalStatus.values().length - 1);
            }
            assertThat(policy.canManageDirecteurs(caller, revival(RevivalStatus.OPEN))).isTrue();
        }

        @Test
        void eachRevivalFollowsTheCallersRoleInIt() {
            final Caller caller = new Caller(new Person(), Set.of(), Map.of(
                    REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.DIRECTEUR, Set.of()),
                    OTHER_REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.STEERING_GROUP, Set.of())));

            assertThat(policy.canSee(caller, revival(REVIVAL_ID, RevivalStatus.APPROVED))).isTrue();
            assertThat(policy.canSee(caller, revival(OTHER_REVIVAL_ID, RevivalStatus.APPROVED))).isFalse();
            assertThat(policy.canEditDetails(caller, revival(REVIVAL_ID, RevivalStatus.OPEN))).isTrue();
            assertThat(policy.canEditDetails(caller, revival(OTHER_REVIVAL_ID, RevivalStatus.OPEN))).isFalse();
        }

        @Test
        void aDirecteurHoldsEveryCapability() {
            assertThat(new RevivalRoleAssignment(RevivalRole.DIRECTEUR, Set.of()).capabilities())
                    .containsExactlyInAnyOrder(Capability.values());
        }
    }
}
