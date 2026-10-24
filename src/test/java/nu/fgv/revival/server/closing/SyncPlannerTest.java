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

package nu.fgv.revival.server.closing;

import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.participant.ParticipantResponse;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.spex.SpexregisterClient.ActivityDetail;
import nu.fgv.revival.server.spex.SpexregisterClient.Actor;
import nu.fgv.revival.server.spex.SpexregisterClient.IdRef;
import nu.fgv.revival.server.spex.SpexregisterClient.SpexActivityRef;
import nu.fgv.revival.server.spex.SpexregisterClient.SpexareActivities;
import nu.fgv.revival.server.spex.SpexregisterClient.TaskActivity;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sync's preview (M7-FR-3): who is included, what already exists in Spexregister, and a hash that is stable and
 * follows what would be written.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncPlannerTest {

    private static final String ACTING = "Dana Directeur (via Nyuppsättning)";
    private static final long REMOTE_REVIVAL = 500L;

    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private SyncStepRepository stepRepository;
    @Mock
    private SpexregisterClient client;
    @Mock
    private SpexareDirectory directory;

    private SyncPlanner planner;
    private Revival revival;

    private static Participant participant(final long id, final @Nullable Long spexareId, final ParticipantKind kind, final ParticipantResponse response,
                                           final @Nullable String position, final @Nullable String role, final boolean membership) {
        final Participant participant = new Participant();

        participant.setId(id);
        participant.setRevivalId(1L);
        participant.setSpexareId(spexareId);
        participant.setKind(kind);
        participant.setResponse(response);
        participant.setPositionTaskId(position);
        participant.setRoleName(role);
        participant.setVocalPartId(role == null ? null : "B1");
        participant.setMembershipPaid(membership);
        return participant;
    }

    @BeforeEach
    void setUp() {
        planner = new SyncPlanner(participantRepository, stepRepository, client, directory);
        revival = new Revival();
        revival.setId(1L);
        revival.setSpexId(10L);
        revival.setYear(2028);
        revival.setMembershipYear(2028);
        when(directory.positions()).thenReturn(List.of(new SpexareDirectory.Position("ROLE", "Roll", "Ensemble", true),
                new SpexareDirectory.Position("TRUMPET", "Trumpet", "Orkester", false)));
        when(directory.vocalPartsOrEmpty()).thenReturn(Map.of("B1", new SpexareDirectory.VocalPart("B1", "Bas 1")));
        when(stepRepository.membershipsRecorded(anyCollection(), anyString(), anyCollection())).thenReturn(List.of());
    }

    private void participants(final Participant... participants) {
        when(participantRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(1L)).thenReturn(List.of(participants));
    }

    @Test
    void withoutTheRevivalInSpexregisterEverythingIsPlanned() {
        participants(
                participant(1, 7L, ParticipantKind.PARTICIPANT, ParticipantResponse.YES, "ROLE", "Kalifen", true),
                participant(2, null, ParticipantKind.PARTICIPANT, ParticipantResponse.YES, null, null, false),
                participant(3, 8L, ParticipantKind.PARTICIPANT, ParticipantResponse.NO, null, null, false),
                participant(4, 9L, ParticipantKind.HELPER, ParticipantResponse.YES, null, null, false));
        when(client.spexRevivals(10L)).thenReturn(List.of(new SpexregisterClient.SpexRevival(400L, 2005)));

        final SyncPlanner.Plan plan = planner.plan(revival, ACTING);

        assertThat(plan.spexregisterRevivalId()).isNull();
        assertThat(plan.revival().exists()).isFalse();
        assertThat(plan.excluded()).extracting(Participant::getId).containsExactly(2L);
        assertThat(plan.participants()).hasSize(1);
        assertThat(plan.participants().getFirst().operations())
                .extracting(SyncPlanner.Operation::operation, SyncPlanner.Operation::detail, SyncPlanner.Operation::exists)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SyncOperation.PARTICIPATION, null, false),
                        org.assertj.core.groups.Tuple.tuple(SyncOperation.TASK, "Roll", false),
                        org.assertj.core.groups.Tuple.tuple(SyncOperation.ACTOR, "Kalifen (Bas 1)", false),
                        org.assertj.core.groups.Tuple.tuple(SyncOperation.MEMBERSHIP, "2028", false));
        assertThat(plan.planned()).extracting(SyncPlanner.Operation::operation).first().isEqualTo(SyncOperation.REVIVAL);
        // Nothing to look for in Spexregister's activities while its revival does not exist
        verify(client, never()).spexareActivities(anyCollection(), any());
    }

    @Test
    void whatSpexregisterHasIsMarkedExisting() {
        participants(participant(1, 7L, ParticipantKind.PARTICIPANT, ParticipantResponse.YES, "ROLE", "Kalifen", true),
                participant(2, 8L, ParticipantKind.PARTICIPANT, ParticipantResponse.YES, "TRUMPET", null, false));
        revival.setSpexregisterRevivalId(REMOTE_REVIVAL);
        when(client.spexareActivities(anyCollection(), anyString())).thenReturn(List.of(
                new SpexareActivities("7", List.of(new ActivityDetail("70", new SpexActivityRef("71", new IdRef(String.valueOf(REMOTE_REVIVAL))),
                        List.of(new TaskActivity("72", new IdRef("ROLE"), List.of(new Actor("73", "Kalifen", new IdRef("B1")))))))),
                new SpexareActivities("8", List.of(new ActivityDetail("80", new SpexActivityRef("81", new IdRef("1")), List.of())))));
        when(stepRepository.membershipsRecorded(anyCollection(), anyString(), anyCollection())).thenReturn(List.of(1L));

        final SyncPlanner.Plan plan = planner.plan(revival, ACTING);

        assertThat(plan.revival().exists()).isTrue();
        assertThat(plan.participants().get(0).operations()).allMatch(SyncPlanner.Operation::exists);
        // An activity for another spex is not the revival's
        assertThat(plan.participants().get(1).operations()).extracting(SyncPlanner.Operation::operation, SyncPlanner.Operation::exists)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SyncOperation.PARTICIPATION, false),
                        org.assertj.core.groups.Tuple.tuple(SyncOperation.TASK, false));
        assertThat(plan.planned()).extracting(SyncPlanner.Operation::participantId).containsOnly(2L);
        verify(client, never()).spexRevivals(anyLong());
    }

    @Test
    void theHashIsStableAndFollowsWhatWouldBeWritten() {
        final Participant anna = participant(1, 7L, ParticipantKind.PARTICIPANT, ParticipantResponse.YES, "TRUMPET", null, false);

        participants(anna);
        when(client.spexRevivals(10L)).thenReturn(List.of());

        final String first = planner.plan(revival, ACTING).hash();

        assertThat(planner.plan(revival, ACTING).hash()).isEqualTo(first).hasSize(64);

        anna.setPositionTaskId("ROLE");
        assertThat(planner.plan(revival, ACTING).hash()).isNotEqualTo(first);

        anna.setPositionTaskId("TRUMPET");
        anna.setMembershipPaid(true);
        assertThat(planner.plan(revival, ACTING).hash()).isNotEqualTo(first);

        anna.setMembershipPaid(false);
        assertThat(planner.plan(revival, ACTING).hash()).isEqualTo(first);
    }
}
