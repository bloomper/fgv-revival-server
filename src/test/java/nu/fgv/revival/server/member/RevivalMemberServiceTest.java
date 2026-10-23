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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 0.1
 */
@ExtendWith(MockitoExtension.class)
class RevivalMemberServiceTest {

    private static final long MEMBER_ID = 20L;

    @Mock
    private RevivalMemberRepository repository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private PersonRepository personRepository;
    @Mock
    private CallerService callerService;

    private RevivalMemberService service;

    private static RevivalMember member(final RevivalRole role, final Long personId, final String pendingEmail) {
        final RevivalMember member = new RevivalMember();

        member.setId(MEMBER_ID);
        member.setRevivalId(REVIVAL_ID);
        member.setRole(role);
        member.setPersonId(personId);
        member.setPendingEmail(pendingEmail);
        member.setVersion(1L);
        return member;
    }

    private static Person person(final long id) {
        final Person person = new Person();

        person.setId(id);
        person.setEmail("kim@example.org");
        person.setDisplayName("Kim");
        return person;
    }

    @BeforeEach
    void setUp() {
        service = new RevivalMemberService(repository, revivalRepository, personRepository, callerService, new AccessPolicy(),
                mock(CapabilityService.class));
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.APPROVED)));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    class Directeurs {

        @Test
        void aKnownVerifiedEmailIsActive() {
            when(personRepository.findByEmailAndEmailVerifiedTrue("kim@example.org")).thenReturn(List.of(person(5L)));

            final RevivalMemberDto added = service.add(new RevivalMemberAddDto(REVIVAL_ID, "Kim@Example.org", RevivalRole.DIRECTEUR, Set.of()));

            assertThat(added.state()).isEqualTo(MemberState.ACTIVE);
            assertThat(added.email()).isEqualTo("kim@example.org");
        }

        @Test
        void anUnknownEmailWaitsLowerCased() {
            when(personRepository.findByEmailAndEmailVerifiedTrue("ny@example.org")).thenReturn(List.of());

            final RevivalMemberDto added = service.add(new RevivalMemberAddDto(REVIVAL_ID, " NY@example.org ", RevivalRole.DIRECTEUR, null));

            assertThat(added.state()).isEqualTo(MemberState.WAITING);
            assertThat(added.email()).isEqualTo("ny@example.org");
        }

        @Test
        void twoPeopleWithTheSameEmailMakeItWait() {
            when(personRepository.findByEmailAndEmailVerifiedTrue("kim@example.org")).thenReturn(List.of(person(5L), person(6L)));

            assertThat(service.add(new RevivalMemberAddDto(REVIVAL_ID, "kim@example.org", RevivalRole.DIRECTEUR, null)).state())
                    .isEqualTo(MemberState.WAITING);
        }

        @Test
        void theSameEmailOrPersonTwiceIsRefused() {
            when(personRepository.findByEmailAndEmailVerifiedTrue("kim@example.org")).thenReturn(List.of(person(5L)));
            when(repository.existsByRevivalIdAndPersonIdAndRemovedAtIsNull(REVIVAL_ID, 5L)).thenReturn(true);
            when(personRepository.findByEmailAndEmailVerifiedTrue("ny@example.org")).thenReturn(List.of());
            when(repository.existsByRevivalIdAndPendingEmailAndRemovedAtIsNull(REVIVAL_ID, "ny@example.org")).thenReturn(true);

            assertThatThrownBy(() -> service.add(new RevivalMemberAddDto(REVIVAL_ID, "kim@example.org", RevivalRole.STEERING_GROUP, null)))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.add(new RevivalMemberAddDto(REVIVAL_ID, "ny@example.org", RevivalRole.DIRECTEUR, null)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void participantsAndHelpersCannotBeAdded() {
            assertThatThrownBy(() -> service.add(new RevivalMemberAddDto(REVIVAL_ID, "kim@example.org", RevivalRole.PARTICIPANT, null)))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.add(new RevivalMemberAddDto(REVIVAL_ID, "kim@example.org", RevivalRole.HELPER, null)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void aDirecteurCannotHaveCapabilities() {
            assertThatThrownBy(() -> service.add(new RevivalMemberAddDto(REVIVAL_ID, "kim@example.org", RevivalRole.DIRECTEUR, Set.of("FINANCE"))))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void removingClosesTheRowAndClearsTheEmail() {
            final RevivalMember waiting = member(RevivalRole.DIRECTEUR, null, "ny@example.org");

            when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(waiting));

            service.remove(MEMBER_ID);

            assertThat(waiting.getRemovedAt()).isNotNull();
            assertThat(waiting.getRemovedBy()).isEqualTo(TestCallers.PERSON_ID);
            assertThat(waiting.getPendingEmail()).isNull();
            verify(repository, never()).delete(any());
        }

        @Test
        void aRemovedMemberCannotBeRemovedAgainButTheEmailCanBeAddedAgain() {
            final RevivalMember removed = member(RevivalRole.DIRECTEUR, 5L, null);

            removed.setRemovedAt(java.time.Instant.now());
            when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(removed));
            when(personRepository.findByEmailAndEmailVerifiedTrue("kim@example.org")).thenReturn(List.of(person(5L)));
            when(repository.existsByRevivalIdAndPersonIdAndRemovedAtIsNull(REVIVAL_ID, 5L)).thenReturn(false);

            assertThatThrownBy(() -> service.remove(MEMBER_ID)).isInstanceOf(nu.fgv.revival.server.util.error.ResourceNotFoundException.class);
            assertThat(service.add(new RevivalMemberAddDto(REVIVAL_ID, "kim@example.org", RevivalRole.DIRECTEUR, null)).state())
                    .isEqualTo(MemberState.ACTIVE);
        }

        @Test
        void onlySuperAdminsManageDirecteurs() {
            when(callerService.current()).thenReturn(TestCallers.directeur());
            when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(RevivalRole.DIRECTEUR, 5L, null)));

            assertThatThrownBy(() -> service.add(new RevivalMemberAddDto(REVIVAL_ID, "kim@example.org", RevivalRole.DIRECTEUR, null)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.remove(MEMBER_ID)).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    class SteeringGroup {

        @BeforeEach
        void asDirecteur() {
            when(callerService.current()).thenReturn(TestCallers.directeur());
        }

        @Test
        void aDirecteurAddsAndRemovesMembersAndSetsCapabilities() {
            final RevivalMember member = member(RevivalRole.STEERING_GROUP, 5L, null);

            when(personRepository.findByEmailAndEmailVerifiedTrue("stina@example.org")).thenReturn(List.of());
            when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member));

            assertThat(service.add(new RevivalMemberAddDto(REVIVAL_ID, "stina@example.org", RevivalRole.STEERING_GROUP, Set.of("FINANCE"))).capabilities())
                    .containsExactly("FINANCE");
            assertThat(service.setCapabilities(new RevivalMemberCapabilitiesDto(MEMBER_ID, 1L, Set.of("FINANCE", "COMMS"))).capabilities())
                    .containsExactlyInAnyOrder("FINANCE", "COMMS");

            service.remove(MEMBER_ID);

            assertThat(member.getRemovedAt()).isNotNull();
        }

        @Test
        void capabilitiesOnlyOnSteeringGroupMembers() {
            when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(RevivalRole.DIRECTEUR, 5L, null)));
            when(callerService.current()).thenReturn(TestCallers.superAdmin());

            assertThatThrownBy(() -> service.setCapabilities(new RevivalMemberCapabilitiesDto(MEMBER_ID, 1L, Set.of("COMMS"))))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void aStaleVersionOnCapabilitiesIsAConflict() {
            when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(RevivalRole.STEERING_GROUP, 5L, null)));

            assertThatThrownBy(() -> service.setCapabilities(new RevivalMemberCapabilitiesDto(MEMBER_ID, 0L, Set.of("COMMS"))))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void allWritesAreRefusedWhileArchived() {
            when(callerService.current()).thenReturn(TestCallers.superAdmin());
            when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.ARCHIVED)));
            when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(member(RevivalRole.STEERING_GROUP, 5L, null)));

            assertThatThrownBy(() -> service.add(new RevivalMemberAddDto(REVIVAL_ID, "stina@example.org", RevivalRole.STEERING_GROUP, null)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.setCapabilities(new RevivalMemberCapabilitiesDto(MEMBER_ID, 1L, Set.of())))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.remove(MEMBER_ID)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        void emailsAreHiddenFromSteeringGroupMembers() {
            when(callerService.current()).thenReturn(TestCallers.steeringGroup());
            when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));
            when(repository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(REVIVAL_ID)).thenReturn(List.of(member(RevivalRole.STEERING_GROUP, null, "ny@example.org")));

            final List<RevivalMemberDto> members = service.findByRevival(REVIVAL_ID);

            assertThat(members).singleElement().satisfies(member -> {
                assertThat(member.state()).isEqualTo(MemberState.WAITING);
                assertThat(member.email()).isNull();
            });
        }
    }
}
