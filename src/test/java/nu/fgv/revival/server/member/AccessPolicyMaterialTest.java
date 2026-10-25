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

import nu.fgv.revival.server.material.MaterialVisibility;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static nu.fgv.revival.server.member.AccessPolicyTest.caller;
import static nu.fgv.revival.server.member.AccessPolicyTest.revival;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The material rules in {@link AccessPolicy}: super admins read, directeurs and steering group
 * write, participants read what is meant for them, observers and helpers nothing.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AccessPolicyMaterialTest {

    private static final long REVIVAL_ID = 1L;

    private final AccessPolicy policy = new AccessPolicy();

    private static Revival revivalIn(final RevivalStatus status) {
        return revival(REVIVAL_ID, status);
    }

    @ParameterizedTest(name = "{0} in {1}: reads all {2}, reads some {3}, writes {4}")
    @CsvSource({
            "SUPER_ADMIN,    PROPOSED,   true,  true,  false",
            "SUPER_ADMIN,    ARCHIVED,   true,  true,  false",
            "OBSERVER,       OPEN,       false, false, false",
            "DIRECTEUR,      APPROVED,   true,  true,  true",
            "DIRECTEUR,      SETTLING,   true,  true,  true",
            "DIRECTEUR,      ARCHIVED,   false, false, false",
            "STEERING_GROUP, ORGANISING, true,  true,  true",
            "STEERING_GROUP, CLOSING,    true,  true,  true",
            "STEERING_GROUP, SETTLING,   false, false, false",
            "PARTICIPANT,    OPEN,       false, true,  false",
            "PARTICIPANT,    CLOSING,    false, false, false",
            "HELPER,         OPEN,       false, false, false",
            "NONE,           OPEN,       false, false, false"
    })
    void matrix(final String role, final RevivalStatus status, final boolean readsAll, final boolean reads, final boolean writes) {
        final Caller caller = caller(role);
        final Revival revival = revivalIn(status);

        assertThat(policy.canReadAllMaterials(caller, revival)).isEqualTo(readsAll);
        assertThat(policy.canReadInventory(caller, revival)).isEqualTo(readsAll);
        assertThat(policy.canReadMaterials(caller, revival)).isEqualTo(reads);
        assertThat(policy.canWriteMaterials(caller, revival)).isEqualTo(writes);
        assertThat(policy.canWriteInventory(caller, revival)).isEqualTo(writes);
        assertThat(policy.permissions(caller, revival).canWriteMaterials()).isEqualTo(writes);
        // Every material and the inventory: participants read theirs through their own page, not the revival's list
        assertThat(policy.permissions(caller, revival).canReadMaterials()).isEqualTo(readsAll);
    }

    @Test
    void aParticipantSeesMaterialsForEveryoneAndForTheirPosition() {
        final Caller participant = caller("PARTICIPANT");
        final Revival open = revivalIn(RevivalStatus.OPEN);

        assertThat(policy.canSeeMaterial(participant, open, MaterialVisibility.PARTICIPANTS, Set.of(), null)).isTrue();
        assertThat(policy.canSeeMaterial(participant, open, MaterialVisibility.STEERING_GROUP, Set.of(), "BAND")).isFalse();
        assertThat(policy.canSeeMaterial(participant, open, MaterialVisibility.POSITIONS, Set.of("BAND", "ENSEMBLE"), "BAND")).isTrue();
        assertThat(policy.canSeeMaterial(participant, open, MaterialVisibility.POSITIONS, Set.of("ENSEMBLE"), "BAND")).isFalse();
        assertThat(policy.canSeeMaterial(participant, open, MaterialVisibility.POSITIONS, Set.of("ENSEMBLE"), null)).isFalse();
    }

    @Test
    void theSteeringGroupSeesEveryMaterialAndHelpersNone() {
        final Revival open = revivalIn(RevivalStatus.OPEN);

        assertThat(policy.canSeeMaterial(caller("STEERING_GROUP"), open, MaterialVisibility.STEERING_GROUP, Set.of(), null)).isTrue();
        assertThat(policy.canSeeMaterial(caller("SUPER_ADMIN"), open, MaterialVisibility.POSITIONS, Set.of("BAND"), null)).isTrue();
        assertThat(policy.canSeeMaterial(caller("HELPER"), open, MaterialVisibility.PARTICIPANTS, Set.of(), null)).isFalse();
        assertThat(policy.canSeeMaterial(caller("OBSERVER"), open, MaterialVisibility.PARTICIPANTS, Set.of(), null)).isFalse();
    }
}
