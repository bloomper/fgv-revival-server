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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;

import static nu.fgv.revival.server.member.AccessPolicyTest.caller;
import static nu.fgv.revival.server.member.AccessPolicyTest.revival;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The M7 rows of the permission matrix (product spec 5.11): the sync and membership marks for directeurs while closing
 * or settling and super admins from organising on, the preview for the steering group too, anonymization for directeurs
 * while settling and super admins while settling, archived or rejected, and never twice.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AccessPolicyClosingTest {

    private final AccessPolicy policy = new AccessPolicy();

    @ParameterizedTest(name = "{0} in {1}: syncs {2}, reads the sync {3}, anonymizes {4}")
    @CsvSource({
            "SUPER_ADMIN,    PROPOSED,   false, false, false",
            "SUPER_ADMIN,    APPROVED,   false, false, false",
            "SUPER_ADMIN,    ORGANISING, true,  true,  false",
            "SUPER_ADMIN,    OPEN,       true,  true,  false",
            "SUPER_ADMIN,    CLOSING,    true,  true,  false",
            "SUPER_ADMIN,    SETTLING,   true,  true,  true",
            "SUPER_ADMIN,    ARCHIVED,   true,  true,  true",
            "SUPER_ADMIN,    REJECTED,   false, false, true",
            "OBSERVER,       CLOSING,    false, false, false",
            "OBSERVER,       ARCHIVED,   false, false, false",
            "DIRECTEUR,      ORGANISING, false, true,  false",
            "DIRECTEUR,      OPEN,       false, true,  false",
            "DIRECTEUR,      CLOSING,    true,  true,  false",
            "DIRECTEUR,      SETTLING,   true,  true,  true",
            "DIRECTEUR,      ARCHIVED,   false, false, false",
            "STEERING_GROUP, CLOSING,    false, true,  false",
            "STEERING_GROUP, SETTLING,   false, false, false",
            "PARTICIPANT,    OPEN,       false, false, false",
            "HELPER,         OPEN,       false, false, false",
            "NONE,           CLOSING,    false, false, false"
    })
    void matrix(final String role, final RevivalStatus status, final boolean syncs, final boolean readsSync, final boolean anonymizes) {
        final Caller caller = caller(role);
        final Revival revival = revival(1L, status);

        assertThat(policy.canSync(caller, revival)).isEqualTo(syncs);
        assertThat(policy.canSetMembership(caller, revival)).isEqualTo(syncs);
        assertThat(policy.canReadSync(caller, revival)).isEqualTo(readsSync);
        assertThat(policy.canAnonymize(caller, revival)).isEqualTo(anonymizes);
        assertThat(policy.permissions(caller, revival).canSync()).isEqualTo(syncs);
        assertThat(policy.permissions(caller, revival).canSetMembership()).isEqualTo(syncs);
        assertThat(policy.permissions(caller, revival).canAnonymize()).isEqualTo(anonymizes);
    }

    @Test
    void nothingOnceAnonymized() {
        final Revival settling = revival(1L, RevivalStatus.SETTLING);

        settling.setAnonymizedAt(Instant.parse("2026-10-01T10:00:00Z"));
        for (final String role : new String[]{"SUPER_ADMIN", "DIRECTEUR"}) {
            assertThat(policy.canAnonymize(caller(role), settling)).as(role).isFalse();
            assertThat(policy.canSync(caller(role), settling)).as(role).isFalse();
            assertThat(policy.canSetMembership(caller(role), settling)).as(role).isFalse();
        }
        assertThatThrownBy(() -> policy.requireAnonymize(caller("SUPER_ADMIN"), settling)).isInstanceOf(AccessDeniedException.class);
    }
}
