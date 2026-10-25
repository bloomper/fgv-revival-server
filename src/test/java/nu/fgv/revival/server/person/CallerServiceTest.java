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

package nu.fgv.revival.server.person;

import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class CallerServiceTest {

    private static final String SUBJECT = "6b0f6a2e-3c8a-4d43-9a7e-1f2b3c4d5e6f";

    @Mock
    private PersonRepository personRepository;
    @Mock
    private RevivalMemberRepository memberRepository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private CallerService service;

    private static void authenticate(final String email, final boolean emailVerified, final String name, final String... roles) {
        final Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(SUBJECT)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("name", name)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        final List<SimpleGrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_fgv-revival_" + role))
                .toList();

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private static Person person(final long id, final String email, final boolean verified, final String name) {
        final Person person = new Person();

        person.setId(id);
        person.setExternalId(SUBJECT);
        person.setEmail(email);
        person.setEmailVerified(verified);
        person.setDisplayName(name);
        return person;
    }

    private static RevivalMember waiting(final long revivalId, final String email) {
        final RevivalMember member = new RevivalMember();

        member.setRevivalId(revivalId);
        member.setRole(RevivalRole.STEERING_GROUP);
        member.setPendingEmail(email);
        return member;
    }

    @BeforeEach
    void setUp() {
        service = new CallerService(personRepository, memberRepository, transactionManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noTokenIsRefused() {
        assertThatThrownBy(() -> service.current()).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void aTokenWithoutUserIsRefused() {
        authenticate("kim@example.org", true, "Kim", "SUPER_ADMIN");

        assertThatThrownBy(() -> service.current()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theFirstRequestCreatesThePersonWithTheLowerCasedEmail() {
        authenticate("Kim.Svensson@Example.org", true, "Kim Svensson", "USER", "OBSERVER");
        when(personRepository.findByExternalId(SUBJECT)).thenReturn(Optional.empty());
        when(personRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            final Person saved = invocation.getArgument(0);

            saved.setId(5L);
            return saved;
        });

        final Caller caller = service.current();

        assertThat(caller.person().getEmail()).isEqualTo("kim.svensson@example.org");
        assertThat(caller.person().getDisplayName()).isEqualTo("Kim Svensson");
        assertThat(caller.globalRoles()).containsExactly(GlobalRole.OBSERVER);
    }

    @Test
    void aChangedNameIsUpdatedAndUnchangedClaimsWriteNothing() {
        authenticate("kim@example.org", true, "Kim S", "USER");
        when(personRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(person(5L, "kim@example.org", true, "Kim")));
        when(personRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.current().person().getDisplayName()).isEqualTo("Kim S");

        SecurityContextHolder.clearContext();
        authenticate("kim@example.org", true, "Kim", "USER");
        when(personRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(person(5L, "kim@example.org", true, "Kim")));

        service.current();

        verify(personRepository, org.mockito.Mockito.times(1)).saveAndFlush(any());
    }

    @Test
    void aPersonWithoutATokenHoldsOnlyTheirActiveRevivalRoles() {
        final Person person = person(5L, "kim@example.org", true, "Kim");
        final RevivalMember member = new RevivalMember();

        member.setRevivalId(3L);
        member.setPersonId(5L);
        member.setRole(RevivalRole.STEERING_GROUP);
        member.setCapabilities(Set.of("FINANCE"));
        when(memberRepository.findByPersonIdAndRemovedAtIsNull(5L)).thenReturn(List.of(member));

        final Caller caller = service.forPerson(person);

        assertThat(caller.globalRoles()).isEmpty();
        assertThat(caller.revivalRoles()).containsOnlyKeys(3L);
        assertThat(caller.roleIn(3L)).isEqualTo(new RevivalRoleAssignment(RevivalRole.STEERING_GROUP, Set.of("FINANCE")));
    }

    @Test
    void aVerifiedEmailClaimsWaitingRoles() {
        final RevivalMember waiting = waiting(3L, "kim@example.org");

        authenticate("KIM@example.org", true, "Kim", "USER");
        when(personRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(person(5L, "kim@example.org", true, "Kim")));
        when(memberRepository.findByPendingEmailAndRemovedAtIsNull("kim@example.org")).thenReturn(List.of(waiting));
        when(memberRepository.existsByRevivalIdAndPersonIdAndRemovedAtIsNull(3L, 5L)).thenReturn(false);

        service.current();

        final ArgumentCaptor<RevivalMember> saved = ArgumentCaptor.forClass(RevivalMember.class);

        verify(memberRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPersonId()).isEqualTo(5L);
        assertThat(saved.getValue().getPendingEmail()).isNull();
    }

    @Test
    void anUnverifiedEmailClaimsNothing() {
        authenticate("kim@example.org", false, "Kim", "USER");
        when(personRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(person(5L, "kim@example.org", false, "Kim")));

        service.current();

        verify(memberRepository, never()).findByPendingEmailAndRemovedAtIsNull(any());
    }

    @Test
    void aClaimThatWouldDuplicateARoleLeavesTheWaitingRowAlone() {
        final RevivalMember waiting = waiting(3L, "kim@example.org");

        authenticate("kim@example.org", true, "Kim", "USER");
        when(personRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(person(5L, "kim@example.org", true, "Kim")));
        when(memberRepository.findByPendingEmailAndRemovedAtIsNull("kim@example.org")).thenReturn(List.of(waiting));
        when(memberRepository.existsByRevivalIdAndPersonIdAndRemovedAtIsNull(3L, 5L)).thenReturn(true);

        service.current();

        verify(memberRepository, never()).saveAndFlush(any());
        assertThat(waiting.getPendingEmail()).isEqualTo("kim@example.org");
    }

    @Test
    void removedWaitingMembersAreNeverClaimed() {
        authenticate("kim@example.org", true, "Kim", "USER");
        when(personRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(person(5L, "kim@example.org", true, "Kim")));

        service.current();

        // Only rows that are not removed are looked up; a removed waiting row has no pending email at all
        verify(memberRepository).findByPendingEmailAndRemovedAtIsNull("kim@example.org");
    }

    @Test
    void activeRevivalRolesAreLoaded() {
        final RevivalMember member = new RevivalMember();

        member.setRevivalId(7L);
        member.setPersonId(5L);
        member.setRole(RevivalRole.STEERING_GROUP);
        member.setCapabilities(Set.of("FINANCE"));
        authenticate("kim@example.org", false, "Kim", "USER");
        when(personRepository.findByExternalId(SUBJECT)).thenReturn(Optional.of(person(5L, "kim@example.org", false, "Kim")));
        when(memberRepository.findByPersonIdAndRemovedAtIsNull(5L)).thenReturn(List.of(member));

        final Caller caller = service.current();

        assertThat(caller.roleIn(7L)).isNotNull();
        assertThat(caller.roleIn(7L).role()).isEqualTo(RevivalRole.STEERING_GROUP);
        assertThat(caller.roleIn(7L).capabilities()).containsExactly("FINANCE");
    }
}
