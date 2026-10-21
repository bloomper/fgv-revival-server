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
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;

import static nu.fgv.revival.server.member.AccessPolicyTest.caller;
import static nu.fgv.revival.server.member.AccessPolicyTest.revival;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table-driven tests of the guide rules in {@link AccessPolicy}, one per row of the operation rules in
 * specs/003-m2-guide/contracts/README.md (constitution III, SC-006).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
class AccessPolicyGuideTest {

    private static final long REVIVAL_ID = 1L;
    private static final Instant V1 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant V2 = Instant.parse("2027-03-01T10:00:00Z");

    private final AccessPolicy policy = new AccessPolicy();

    private static List<Revival> revivals(final RevivalStatus status) {
        return List.of(revival(REVIVAL_ID, status));
    }

    @Nested
    class ReadGuide {

        @ParameterizedTest
        @EnumSource(RevivalStatus.class)
        void superAdminAndObserverReadInEveryStatus(final RevivalStatus status) {
            assertThat(policy.canReadGuide(caller("SUPER_ADMIN"), revivals(status))).isTrue();
            assertThat(policy.canReadGuide(caller("OBSERVER"), revivals(status))).isTrue();
            assertThat(policy.canReadGuide(caller("SUPER_ADMIN"), List.of())).isTrue();
        }

        @ParameterizedTest
        @CsvSource({
                "DIRECTEUR, PROPOSED, false",
                "DIRECTEUR, APPROVED, true",
                "DIRECTEUR, ORGANISING, true",
                "DIRECTEUR, OPEN, true",
                "DIRECTEUR, CLOSING, true",
                "DIRECTEUR, SETTLING, true",
                "DIRECTEUR, ARCHIVED, false",
                "DIRECTEUR, REJECTED, false",
                "STEERING_GROUP, APPROVED, false",
                "STEERING_GROUP, ORGANISING, true",
                "STEERING_GROUP, OPEN, true",
                "STEERING_GROUP, CLOSING, true",
                "STEERING_GROUP, SETTLING, false",
                "NONE, ORGANISING, false"
        })
        void revivalRolesReadWhileTheirRevivalIsVisible(final String role, final RevivalStatus status, final boolean expected) {
            assertThat(policy.canReadGuide(caller(role), revivals(status))).isEqualTo(expected);
        }

        @Test
        void aRoleInAnotherRevivalDoesNotCount() {
            assertThat(policy.canReadGuide(caller("DIRECTEUR"), List.of(revival(2L, RevivalStatus.ORGANISING)))).isFalse();
        }

        @Test
        void requireReadGuideRefuses() {
            assertThatThrownBy(() -> policy.requireReadGuide(caller("NONE"), List.of()))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @ParameterizedTest
        @EnumSource(RevivalStatus.class)
        void revivalGuideFollowsRevivalVisibility(final RevivalStatus status) {
            for (final String role : List.of("SUPER_ADMIN", "OBSERVER", "DIRECTEUR", "STEERING_GROUP", "NONE")) {
                final Revival revival = revival(REVIVAL_ID, status);

                assertThat(policy.canReadRevivalGuide(caller(role), revival)).isEqualTo(policy.canSee(caller(role), revival));
            }
        }
    }

    @Nested
    class Draft {

        @Test
        void onlySuperAdminsManageTheDraft() {
            assertThat(policy.canManageGuide(caller("SUPER_ADMIN"))).isTrue();
            for (final String role : List.of("OBSERVER", "DIRECTEUR", "STEERING_GROUP", "NONE")) {
                final Caller caller = caller(role);

                assertThat(policy.canManageGuide(caller)).isFalse();
                assertThatThrownBy(() -> policy.requireManageGuide(caller)).isInstanceOf(AccessDeniedException.class);
            }
        }
    }

    @Nested
    class Suggest {

        @ParameterizedTest
        @CsvSource({
                "DIRECTEUR, APPROVED, true",
                "DIRECTEUR, SETTLING, true",
                "DIRECTEUR, ARCHIVED, false",
                "STEERING_GROUP, ORGANISING, true",
                "STEERING_GROUP, CLOSING, true",
                "STEERING_GROUP, SETTLING, false",
                "STEERING_GROUP, APPROVED, false",
                "SUPER_ADMIN, ORGANISING, false",
                "OBSERVER, ORGANISING, false",
                "NONE, ORGANISING, false"
        })
        void revivalRolesSuggestFromAVisibleRevival(final String role, final RevivalStatus status, final boolean expected) {
            assertThat(policy.canSuggest(caller(role), revival(REVIVAL_ID, status))).isEqualTo(expected);
        }

        @Test
        void onlySuperAdminsDecide() {
            assertThat(policy.canDecideSuggestion(caller("SUPER_ADMIN"))).isTrue();
            for (final String role : List.of("OBSERVER", "DIRECTEUR", "STEERING_GROUP", "NONE")) {
                assertThat(policy.canDecideSuggestion(caller(role))).isFalse();
            }
        }

        @Test
        void onlyTheAuthorWithdrawsWhileTheyMaySuggest() {
            final Caller author = caller("STEERING_GROUP");
            final long authorId = author.person().getId();

            assertThat(policy.canWithdrawSuggestion(author, revival(REVIVAL_ID, RevivalStatus.OPEN), authorId)).isTrue();
            assertThat(policy.canWithdrawSuggestion(author, revival(REVIVAL_ID, RevivalStatus.OPEN), authorId + 1)).isFalse();
            assertThat(policy.canWithdrawSuggestion(author, revival(REVIVAL_ID, RevivalStatus.SETTLING), authorId)).isFalse();
            assertThat(policy.canWithdrawSuggestion(caller("SUPER_ADMIN"), revival(REVIVAL_ID, RevivalStatus.OPEN), authorId)).isFalse();
        }

        @ParameterizedTest
        @CsvSource({
                "SUPER_ADMIN, ARCHIVED, true",
                "OBSERVER, PROPOSED, true",
                "DIRECTEUR, OPEN, true",
                "STEERING_GROUP, OPEN, true",
                "STEERING_GROUP, SETTLING, false",
                "NONE, OPEN, false"
        })
        void suggestionsAreSeenByWhoMaySuggestAndByFgv(final String role, final RevivalStatus status, final boolean expected) {
            assertThat(policy.canSeeSuggestion(caller(role), revival(REVIVAL_ID, status))).isEqualTo(expected);
        }
    }

    @Nested
    class GuideVersionChange {

        @Test
        void superAdminPinsAnyPublishedVersionUnlessArchived() {
            assertThat(policy.canChangeGuideVersion(caller("SUPER_ADMIN"), revival(REVIVAL_ID, RevivalStatus.OPEN), V2, V1)).isTrue();
            assertThat(policy.canChangeGuideVersion(caller("SUPER_ADMIN"), revival(REVIVAL_ID, RevivalStatus.PROPOSED), null, V1)).isTrue();
            assertThat(policy.canChangeGuideVersion(caller("SUPER_ADMIN"), revival(REVIVAL_ID, RevivalStatus.ARCHIVED), V1, V2)).isFalse();
        }

        @Test
        void directeurMovesOnlyForwardWhileVisibleAndNotArchived() {
            assertThat(policy.canChangeGuideVersion(caller("DIRECTEUR"), revival(REVIVAL_ID, RevivalStatus.ORGANISING), V1, V2)).isTrue();
            assertThat(policy.canChangeGuideVersion(caller("DIRECTEUR"), revival(REVIVAL_ID, RevivalStatus.ORGANISING), null, V2)).isTrue();
            assertThat(policy.canChangeGuideVersion(caller("DIRECTEUR"), revival(REVIVAL_ID, RevivalStatus.ORGANISING), V2, V1)).isFalse();
            assertThat(policy.canChangeGuideVersion(caller("DIRECTEUR"), revival(REVIVAL_ID, RevivalStatus.ORGANISING), V2, V2)).isFalse();
            assertThat(policy.canChangeGuideVersion(caller("DIRECTEUR"), revival(REVIVAL_ID, RevivalStatus.PROPOSED), V1, V2)).isFalse();
            assertThat(policy.canChangeGuideVersion(caller("DIRECTEUR"), revival(REVIVAL_ID, RevivalStatus.ARCHIVED), V1, V2)).isFalse();
        }

        @Test
        void steeringGroupObserverAndOthersNever() {
            for (final String role : List.of("STEERING_GROUP", "OBSERVER", "NONE")) {
                assertThat(policy.canChangeGuideVersion(caller(role), revival(REVIVAL_ID, RevivalStatus.OPEN), V1, V2)).isFalse();
            }
        }
    }
}
