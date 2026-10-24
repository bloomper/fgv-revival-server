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

import nu.fgv.revival.server.guide.GuideVersionSummaryDto;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static nu.fgv.revival.server.member.AccessPolicyTest.caller;
import static nu.fgv.revival.server.member.AccessPolicyTest.revival;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M6 rules in {@link AccessPolicy} (M6-FR-1, constitution III): participants and helpers see their revival while it
 * is {@code OPEN}, for their own page, and every row of the permission matrix that belongs to the steering group stays
 * closed to them; and who reads and writes participants and their dietary needs.
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
class AccessPolicyParticipantTest {

    private static final long REVIVAL_ID = 1L;
    private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-01T00:00:00Z");

    private final AccessPolicy policy = new AccessPolicy();

    private static Revival revivalIn(final RevivalStatus status) {
        return revival(REVIVAL_ID, status);
    }

    private static Caller steeringGroupWith(final String capability) {
        final Person person = new Person();

        person.setId(10L);
        return new Caller(person, Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.STEERING_GROUP, Set.of(capability))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARTICIPANT", "HELPER"})
    void participantsAndHelpersSeeTheirRevivalOnlyWhileItIsOpen(final String role) {
        for (final RevivalStatus status : RevivalStatus.values()) {
            assertThat(policy.canSee(caller(role), revivalIn(status))).as("%s in %s", role, status).isEqualTo(status == RevivalStatus.OPEN);
            assertThat(policy.canParticipate(caller(role), revivalIn(status))).as("%s in %s", role, status).isEqualTo(status == RevivalStatus.OPEN);
            assertThat(policy.canSubscribeCalendar(caller(role), revivalIn(status))).as("%s in %s", role, status)
                    .isEqualTo(status == RevivalStatus.OPEN);
            assertThat(policy.isParticipant(caller(role), revivalIn(status))).isTrue();
        }
    }

    /**
     * Walks every steering group operation of the permission matrix (product spec 3) for a participant and a helper, in
     * every status: all refused.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PARTICIPANT", "HELPER"})
    void everySteeringGroupOperationIsRefused(final String role) {
        final Caller caller = caller(role);

        for (final RevivalStatus status : RevivalStatus.values()) {
            final Revival revival = revivalIn(status);
            final String as = "%s in %s".formatted(role, status);
            final List<GuideVersionSummaryDto> published = List.of(
                    new GuideVersionSummaryDto(1L, "v1", null, EARLIER, 1L, 0L),
                    new GuideVersionSummaryDto(2L, "v2", null, LATER, 1L, 0L));

            // Revival settings and roles
            assertThat(policy.canEditCore(caller, revival)).as(as).isFalse();
            assertThat(policy.canEditDetails(caller, revival)).as(as).isFalse();
            assertThat(policy.allowedStatuses(caller, revival)).as(as).isEmpty();
            assertThat(policy.canManageDirecteurs(caller, revival)).as(as).isFalse();
            assertThat(policy.canManageSteeringGroup(caller, revival)).as(as).isFalse();
            for (final RevivalRole managed : RevivalRole.values()) {
                assertThat(policy.canManageMembers(caller, revival, managed)).as(as).isFalse();
            }
            assertThat(policy.canSearchMemberCandidates(caller, revival)).as(as).isFalse();
            assertThat(policy.canSeeMemberEmails(caller, revival)).as(as).isFalse();
            assertThat(policy.canDeleteRevival(caller)).as(as).isFalse();
            assertThat(policy.canCreateRevival(caller)).as(as).isFalse();
            assertThat(policy.canSearchSpex(caller)).as(as).isFalse();
            // Guide, templates and library
            assertThat(policy.canReadGuide(caller, List.of(revival))).as(as).isFalse();
            assertThat(policy.canReadRevivalGuide(caller, revival)).as(as).isFalse();
            assertThat(policy.canManageGuide(caller)).as(as).isFalse();
            assertThat(policy.canSuggest(caller, revival)).as(as).isFalse();
            assertThat(policy.canSeeSuggestion(caller, revival)).as(as).isFalse();
            assertThat(policy.canWithdrawSuggestion(caller, revival, 10L)).as(as).isFalse();
            assertThat(policy.canDecideSuggestion(caller)).as(as).isFalse();
            assertThat(policy.canChangeGuideVersion(caller, revival, EARLIER, LATER)).as(as).isFalse();
            assertThat(policy.guideVersionsAllowed(caller, revival, published)).as(as).isEmpty();
            assertThat(policy.canReadChecklistTemplates(caller, List.of(revival))).as(as).isFalse();
            assertThat(policy.canManageChecklistTemplates(caller)).as(as).isFalse();
            assertThat(policy.canManageCatalogs(caller)).as(as).isFalse();
            assertThat(policy.canReadReferenceFigures(caller, List.of(revival))).as(as).isFalse();
            assertThat(policy.canManageReferenceFigures(caller)).as(as).isFalse();
            // Decision log, meetings, checklists, events as work
            assertThat(policy.canReadRevivalWork(caller, revival)).as(as).isFalse();
            assertThat(policy.canWriteRevivalWork(caller, revival)).as(as).isFalse();
            assertThat(policy.canWaiveRequirement(caller, revival)).as(as).isFalse();
            assertThat(policy.canManageEventTypes(caller)).as(as).isFalse();
            assertThat(policy.canSeeMeetingsInCalendar(caller, revival)).as(as).isFalse();
            // Participants
            assertThat(policy.canReadParticipants(caller, revival)).as(as).isFalse();
            assertThat(policy.canWriteParticipants(caller, revival)).as(as).isFalse();
            assertThat(policy.canReadDietary(caller, revival)).as(as).isFalse();
            // Budget and finance
            assertThat(policy.canReadBudget(caller, revival)).as(as).isFalse();
            assertThat(policy.canWriteBudget(caller, revival)).as(as).isFalse();
            assertThat(policy.canReadSpexCalendar(caller)).as(as).isFalse();
            assertThat(policy.canManageSpexCalendar(caller)).as(as).isFalse();
            // Staffing and mailings (M6b): their own on their page, nothing of the steering group's
            assertThat(policy.canReadStaffing(caller, revival)).as(as).isFalse();
            assertThat(policy.canWriteStaffing(caller, revival)).as(as).isFalse();
            assertThat(policy.canReadMailings(caller, revival)).as(as).isFalse();
            assertThat(policy.canDraftMailings(caller, revival)).as(as).isFalse();
            assertThat(policy.canSendMailings(caller, revival)).as(as).isFalse();
            assertThat(policy.canReadMailingTemplates(caller, List.of(revival))).as(as).isFalse();
            assertThat(policy.canManageMailingTemplates(caller)).as(as).isFalse();

            final RevivalPermissionsDto permissions = policy.permissions(caller, revival, published);

            assertThat(permissions.canEditCore() || permissions.canEditDetails() || permissions.canManageDirecteurs()
                    || permissions.canManageSteeringGroup() || permissions.canSuggestGuideChange() || permissions.canWriteLog()
                    || permissions.canWaiveRequirement() || permissions.canDelete() || permissions.canReadBudget() || permissions.canWriteBudget()
                    || permissions.canReadParticipants() || permissions.canWriteParticipants() || permissions.canReadDietary()
                    || permissions.canReadWork() || permissions.canReadStaffing() || permissions.canWriteStaffing() || permissions.canReadMailings()
                    || permissions.canDraftMailings() || permissions.canSendMailings()).as(as).isFalse();
            assertThat(permissions.allowedStatuses()).as(as).isEmpty();
            assertThat(permissions.guideVersionsAllowed()).as(as).isEmpty();
            assertThat(permissions.isParticipant()).as(as).isTrue();
        }
    }

    @ParameterizedTest(name = "{0} in {1} reads events: {2}")
    @CsvSource({
            "SUPER_ADMIN,    PROPOSED,   true",
            "OBSERVER,       ARCHIVED,   true",
            "DIRECTEUR,      OPEN,       true",
            "STEERING_GROUP, OPEN,       true",
            "PARTICIPANT,    OPEN,       true",
            "PARTICIPANT,    ORGANISING, false",
            "PARTICIPANT,    CLOSING,    false",
            "HELPER,         OPEN,       false",
            "NONE,           OPEN,       false"
    })
    void participantsReadEventsHelpersOnlyOnTheirPage(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canReadEvents(caller(role), revivalIn(status))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} in {1} reads participants: {2}")
    @CsvSource({
            "SUPER_ADMIN,    PROPOSED,   true",
            "SUPER_ADMIN,    ARCHIVED,   true",
            "OBSERVER,       OPEN,       false",
            "DIRECTEUR,      APPROVED,   true",
            "DIRECTEUR,      SETTLING,   true",
            "DIRECTEUR,      ARCHIVED,   false",
            "STEERING_GROUP, ORGANISING, true",
            "STEERING_GROUP, OPEN,       true",
            "STEERING_GROUP, SETTLING,   false",
            "PARTICIPANT,    OPEN,       false",
            "HELPER,         OPEN,       false",
            "NONE,           OPEN,       false"
    })
    void readersOfParticipants(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canReadParticipants(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(policy.permissions(caller(role), revivalIn(status)).canReadParticipants()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} in {1} writes participants: {2}")
    @CsvSource({
            "SUPER_ADMIN,    OPEN,       false",
            "OBSERVER,       OPEN,       false",
            "DIRECTEUR,      APPROVED,   true",
            "DIRECTEUR,      OPEN,       true",
            "DIRECTEUR,      SETTLING,   true",
            "DIRECTEUR,      ARCHIVED,   false",
            "STEERING_GROUP, OPEN,       true",
            "STEERING_GROUP, CLOSING,    true",
            "STEERING_GROUP, SETTLING,   false",
            "PARTICIPANT,    OPEN,       false",
            "HELPER,         OPEN,       false"
    })
    void writersOfParticipants(final String role, final RevivalStatus status, final boolean expected) {
        assertThat(policy.canWriteParticipants(caller(role), revivalIn(status))).isEqualTo(expected);
        assertThat(policy.permissions(caller(role), revivalIn(status)).canWriteParticipants()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = RevivalStatus.class, names = {"ORGANISING", "OPEN", "CLOSING"})
    void dietaryNeedsNeedTheCateringAreaOrTheDirecteur(final RevivalStatus status) {
        assertThat(policy.canReadDietary(caller("DIRECTEUR"), revivalIn(status))).isTrue();
        assertThat(policy.canReadDietary(steeringGroupWith(AccessPolicy.CATERING), revivalIn(status))).isTrue();
        assertThat(policy.canReadDietary(steeringGroupWith(AccessPolicy.FINANCE), revivalIn(status))).isFalse();
        assertThat(policy.canReadDietary(caller("SUPER_ADMIN"), revivalIn(status))).isFalse();
        assertThat(policy.canReadDietary(caller("OBSERVER"), revivalIn(status))).isFalse();
    }

    @Test
    void workReadersAreTheSteeringGroupAndTheGlobalRoles() {
        final Revival open = revivalIn(RevivalStatus.OPEN);

        assertThat(policy.permissions(caller("SUPER_ADMIN"), open).canReadWork()).isTrue();
        assertThat(policy.permissions(caller("OBSERVER"), open).canReadWork()).isTrue();
        assertThat(policy.permissions(caller("DIRECTEUR"), open).canReadWork()).isTrue();
        assertThat(policy.permissions(caller("STEERING_GROUP"), open).canReadWork()).isTrue();
        assertThat(policy.permissions(caller("PARTICIPANT"), open).canReadWork()).isFalse();
        assertThat(policy.permissions(caller("DIRECTEUR"), open).isParticipant()).isFalse();
    }
}
