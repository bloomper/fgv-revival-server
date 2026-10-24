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
package nu.fgv.revival.server.material;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.material.MaterialDtos.MaterialDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantAccess;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Materials (M8-FR-2, M8-FR-3, M8-FR-5, M8-FR-6): the service is chosen or recognised when saved, positions are
 * Spexregister task ids, and a participant sees what is for every participant or for their position.
 *
 * @author Anders Jacobsson
 */
@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {

    private static final long REVIVAL_ID = TestCallers.REVIVAL_ID;

    @Mock
    private MaterialRepository repository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private LinkServiceCatalog services;
    @Mock
    private SpexareDirectory directory;
    @Mock
    private ParticipantAccess participantAccess;
    @Mock
    private CallerService callerService;

    private MaterialService service;
    private final List<Material> materials = new ArrayList<>();
    private Revival revival;

    @BeforeEach
    void setUp() {
        service = new MaterialService(repository, revivalRepository, services, directory, participantAccess, callerService, new AccessPolicy(),
                TestClocks.at(LocalDate.of(2026, 10, 23)));
        revival = TestCallers.revival(RevivalStatus.OPEN);
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(repository.findByRevivalId(REVIVAL_ID)).thenAnswer(invocation -> List.copyOf(materials));
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                materials.stream().filter(material -> material.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(services.recognise(anyString())).thenAnswer(invocation -> Links.recognise(invocation.getArgument(0), TestLinkServices.seeded()));
        lenient().when(directory.positions()).thenReturn(List.of(new SpexareDirectory.Position("BAND", "Orkester", "Orkester", false),
                new SpexareDirectory.Position("ENSEMBLE", "Ensemble", "Ensemble", true)));

        material(1L, "Manus", MaterialVisibility.PARTICIPANTS);
        material(2L, "Budget", MaterialVisibility.STEERING_GROUP);
        material(3L, "Orkesterarr", MaterialVisibility.POSITIONS, "BAND");
        material(4L, "Gammalt manus", MaterialVisibility.PARTICIPANTS).setRemovedAt(Instant.parse("2026-10-01T10:00:00Z"));
    }

    private Material material(final long id, final String title, final MaterialVisibility visibility, final String... positions) {
        final Material material = new Material();

        material.setId(id);
        material.setRevivalId(REVIVAL_ID);
        material.setTitle(title);
        material.setUrl("https://drive.google.com/" + id);
        material.setCategory(MaterialCategory.OTHER);
        material.setVisibility(visibility);
        material.setPositions(new HashSet<>(Set.of(positions)));
        material.setVersion(0L);
        materials.add(material);
        return material;
    }

    private static Caller participantCaller(final RevivalRole role) {
        final Person person = new Person();

        person.setId(20L);
        return new Caller(person, Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(role, Set.of())));
    }

    private void participant(final RevivalRole role, final ParticipantKind kind, final @Nullable String position) {
        final Caller caller = participantCaller(role);
        final Participant participant = new Participant();

        participant.setRevivalId(REVIVAL_ID);
        participant.setKind(kind);
        participant.setPositionTaskId(position);
        when(callerService.current()).thenReturn(caller);
        lenient().when(participantAccess.own(caller, REVIVAL_ID)).thenReturn(new ParticipantAccess.Own(revival, new RevivalMember(), participant));
    }

    private static MaterialService.Input input(final String url, final @Nullable String serviceId, final MaterialVisibility visibility,
                                               final @Nullable List<String> positions) {
        return new MaterialService.Input(REVIVAL_ID, " Manus ", url, serviceId, MaterialCategory.SCRIPT, visibility, positions, "  ");
    }

    @Test
    void theSteeringGroupSeesEverythingAndWritersTheRemovedToo() {
        when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        revival.setStatus(RevivalStatus.ORGANISING);

        assertThat(service.list(REVIVAL_ID, false)).extracting(MaterialDto::title).containsExactly("Budget", "Manus", "Orkesterarr");
        assertThat(service.list(REVIVAL_ID, true)).extracting(MaterialDto::title).containsExactly("Budget", "Gammalt manus", "Manus", "Orkesterarr");
    }

    @Test
    void theSuperAdminReadsAllButNotTheRemoved() {
        when(callerService.current()).thenReturn(TestCallers.superAdmin());

        assertThat(service.list(REVIVAL_ID, true)).extracting(MaterialDto::title).containsExactly("Budget", "Manus", "Orkesterarr");
    }

    @Test
    void aParticipantSeesWhatIsForEveryoneAndForTheirPosition() {
        participant(RevivalRole.PARTICIPANT, ParticipantKind.PARTICIPANT, "BAND");

        assertThat(service.list(REVIVAL_ID, true)).extracting(MaterialDto::title).containsExactly("Manus", "Orkesterarr");
        assertThat(service.forParticipation(REVIVAL_ID)).extracting(MaterialDto::title).containsExactly("Manus", "Orkesterarr");
    }

    @Test
    void aParticipantWithAnotherPositionOrNoneDoesNotSeeIt() {
        participant(RevivalRole.PARTICIPANT, ParticipantKind.PARTICIPANT, "ENSEMBLE");

        assertThat(service.forParticipation(REVIVAL_ID)).extracting(MaterialDto::title).containsExactly("Manus");

        participant(RevivalRole.PARTICIPANT, ParticipantKind.PARTICIPANT, null);

        assertThat(service.list(REVIVAL_ID, false)).extracting(MaterialDto::title).containsExactly("Manus");
    }

    @Test
    void helpersAndObserversSeeNone() {
        participant(RevivalRole.HELPER, ParticipantKind.HELPER, "BAND");

        assertThat(service.forParticipation(REVIVAL_ID)).isEmpty();
        assertThatThrownBy(() -> service.list(REVIVAL_ID, false)).isInstanceOf(AccessDeniedException.class);

        when(callerService.current()).thenReturn(TestCallers.observer());
        assertThatThrownBy(() -> service.list(REVIVAL_ID, false)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theServiceIsRecognisedFromTheHostUnlessChosen() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        final MaterialDto recognised = service.create(input("https://docs.google.com/document/d/x", null, MaterialVisibility.PARTICIPANTS, null));
        final MaterialDto unknown = service.create(input("https://example.org/manus.pdf", null, MaterialVisibility.PARTICIPANTS, null));
        final MaterialDto chosen = service.create(input("https://spexflix.example.org/x", "SPEXFLIX", MaterialVisibility.PARTICIPANTS, null));

        assertThat(recognised.serviceId()).isEqualTo("GOOGLE_DRIVE");
        assertThat(recognised.title()).isEqualTo("Manus");
        assertThat(recognised.comment()).isNull();
        assertThat(unknown.serviceId()).isNull();
        assertThat(chosen.serviceId()).isEqualTo("SPEXFLIX");
        assertThat(chosen.serviceChosen()).isTrue();
        assertThat(recognised.serviceChosen()).isFalse();
        assertThat(unknown.serviceChosen()).isFalse();
    }

    @Test
    void positionsAreRequiredForPositionsAndClearedOtherwise() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        assertThatThrownBy(() -> service.create(input("https://youtu.be/x", null, MaterialVisibility.POSITIONS, List.of(" "))))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.material.positions");
        assertThatThrownBy(() -> service.create(input("https://youtu.be/x", null, MaterialVisibility.POSITIONS, List.of("NOPE"))))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.material.position.unknown");
        assertThat(service.create(input("https://youtu.be/x", null, MaterialVisibility.POSITIONS, List.of("BAND", "ENSEMBLE"))).positionIds())
                .containsExactly("BAND", "ENSEMBLE");
        assertThat(service.create(input("https://youtu.be/x", null, MaterialVisibility.PARTICIPANTS, List.of("BAND"))).positionIds()).isEmpty();
    }

    @Test
    void positionsAlreadyHeldNeedNoSpexregister() {
        when(callerService.current()).thenReturn(TestCallers.directeur());
        when(directory.positions()).thenThrow(new SpexregisterUnavailableException(new RuntimeException("down")));

        final MaterialDto kept = service.update(3L, 0L, input("https://youtu.be/x", null, MaterialVisibility.POSITIONS, List.of("BAND")));

        assertThat(kept.positionIds()).containsExactly("BAND");
        assertThatThrownBy(() -> service.update(3L, 0L, input("https://youtu.be/x", null, MaterialVisibility.POSITIONS, List.of("BAND", "ENSEMBLE"))))
                .isInstanceOf(SpexregisterUnavailableException.class);
    }

    @Test
    void onlyTheSteeringGroupWritesAndNotOnceArchived() {
        when(callerService.current()).thenReturn(TestCallers.superAdmin());
        assertThatThrownBy(() -> service.create(input("https://youtu.be/x", null, MaterialVisibility.PARTICIPANTS, null)))
                .isInstanceOf(AccessDeniedException.class);

        participant(RevivalRole.PARTICIPANT, ParticipantKind.PARTICIPANT, "BAND");
        assertThatThrownBy(() -> service.remove(1L, 0L)).isInstanceOf(ResourceNotFoundException.class);

        when(callerService.current()).thenReturn(TestCallers.directeur());
        revival.setStatus(RevivalStatus.ARCHIVED);
        assertThatThrownBy(() -> service.remove(1L, 0L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void removedAndRestored() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        assertThat(service.remove(1L, 0L).removed()).isTrue();
        assertThatThrownBy(() -> service.update(1L, 0L, input("https://youtu.be/x", null, MaterialVisibility.PARTICIPANTS, null)))
                .isInstanceOf(BadRequestException.class).hasFieldOrPropertyWithValue("detailMessageCode", "error.material.removed");
        assertThat(service.restore(1L, 0L).removed()).isFalse();
        assertThatThrownBy(() -> service.restore(1L, 0L)).hasFieldOrPropertyWithValue("detailMessageCode", "error.material.notRemoved");
    }

    @Test
    void anotherVersionIsAConflict() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        assertThatThrownBy(() -> service.remove(1L, 7L)).isInstanceOf(ConflictException.class);
    }
}
