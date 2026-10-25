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

import nu.fgv.revival.server.member.OwnerResolver.Resolution;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owners by display name for MCP and the import, and owner validation.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class OwnerResolverTest {

    private static final long REVIVAL_ID = 1L;
    private static final long KIM = 11L;
    private static final long KIM_BERG = 12L;
    private static final long ROBIN = 13L;
    private static final long REMOVED = 14L;
    private static final long PARTICIPANT = 15L;

    private final RevivalMemberRepository memberRepository = mock(RevivalMemberRepository.class);
    private final PersonRepository personRepository = mock(PersonRepository.class);
    private final OwnerResolver resolver = new OwnerResolver(memberRepository, personRepository);

    private static RevivalMember member(final @Nullable Long personId, final RevivalRole role, final boolean removed) {
        final RevivalMember member = new RevivalMember();

        member.setRevivalId(REVIVAL_ID);
        member.setPersonId(personId);
        member.setRole(role);
        if (removed) {
            member.setRemovedAt(Instant.now());
        }
        return member;
    }

    private static Person person(final long id, final String displayName) {
        final Person person = new Person();

        person.setId(id);
        person.setDisplayName(displayName);
        return person;
    }

    private static Caller caller(final long personId) {
        return new Caller(person(personId, "Me"), Set.of(), Map.of());
    }

    @BeforeEach
    void members() {
        when(memberRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(REVIVAL_ID)).thenReturn(List.of(
                member(KIM, RevivalRole.DIRECTEUR, false),
                member(KIM_BERG, RevivalRole.STEERING_GROUP, false),
                member(ROBIN, RevivalRole.STEERING_GROUP, false),
                member(PARTICIPANT, RevivalRole.PARTICIPANT, false),
                member(null, RevivalRole.STEERING_GROUP, false)));
        when(personRepository.findAllById(any())).thenReturn(List.of(
                person(KIM, "Kim Svensson"), person(KIM_BERG, "Kim Berg"), person(ROBIN, "Robin Ek"),
                person(PARTICIPANT, "Pia Deltagare"), person(REMOVED, "Rut Borta")));
    }

    @Nested
    class Resolve {

        @Test
        void aFullNameMatchesIgnoringCaseSpacesAndDiacritics() {
            assertThat(resolver.resolve(REVIVAL_ID, "  kim svensson ", caller(ROBIN))).isEqualTo(new Resolution.Member(KIM, "Kim Svensson"));
            assertThat(resolver.resolve(REVIVAL_ID, "Kim Svénsson", caller(ROBIN))).isEqualTo(new Resolution.Member(KIM, "Kim Svensson"));
        }

        @Test
        void aUniqueFirstNameMatches() {
            assertThat(resolver.resolve(REVIVAL_ID, "robin", caller(KIM))).isEqualTo(new Resolution.Member(ROBIN, "Robin Ek"));
        }

        @Test
        void severalMatchesAreAmbiguous() {
            assertThat(resolver.resolve(REVIVAL_ID, "Kim", caller(ROBIN)))
                    .isEqualTo(new Resolution.Ambiguous(List.of("Kim Berg", "Kim Svensson")));
        }

        @Test
        void noMatchIsFreeText() {
            assertThat(resolver.resolve(REVIVAL_ID, " Lisa from the venue ", caller(KIM))).isEqualTo(new Resolution.FreeText("Lisa from the venue"));
        }

        @Test
        void participantsAndWaitingMembersNeverMatch() {
            assertThat(resolver.resolve(REVIVAL_ID, "Pia Deltagare", caller(KIM))).isEqualTo(new Resolution.FreeText("Pia Deltagare"));
        }

        @Test
        void meIsTheCaller() {
            assertThat(resolver.resolve(REVIVAL_ID, "me", caller(ROBIN))).isEqualTo(new Resolution.Member(ROBIN, "Robin Ek"));
            assertThat(resolver.resolve(REVIVAL_ID, "ME", caller(ROBIN))).isEqualTo(new Resolution.Member(ROBIN, "Robin Ek"));
        }

        @Test
        void meIsRefusedForSomeoneWhoIsNotAMember() {
            assertThatThrownBy(() -> resolver.resolve(REVIVAL_ID, "me", caller(REMOVED))).isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    class Validate {

        @Test
        void neitherIsNoOwner() {
            assertThat(resolver.validate(REVIVAL_ID, null)).isEqualTo(Owner.none());
            assertThat(resolver.validate(REVIVAL_ID, new OwnerInputDto(null, null))).isEqualTo(Owner.none());
        }

        @Test
        void anActiveMember() {
            assertThat(resolver.validate(REVIVAL_ID, new OwnerInputDto(ROBIN, null))).isEqualTo(Owner.member(ROBIN));
        }

        @Test
        void aNameIsTrimmed() {
            assertThat(resolver.validate(REVIVAL_ID, new OwnerInputDto(null, "  Sam  "))).isEqualTo(Owner.named("Sam"));
        }

        @Test
        void bothAreRefused() {
            assertThatThrownBy(() -> resolver.validate(REVIVAL_ID, new OwnerInputDto(ROBIN, "Sam"))).isInstanceOf(BadRequestException.class);
        }

        @Test
        void someoneWhoIsNotAnActiveDirecteurOrSteeringGroupMemberIsRefused() {
            for (final long personId : List.of(REMOVED, PARTICIPANT, 99L)) {
                assertThatThrownBy(() -> resolver.validate(REVIVAL_ID, new OwnerInputDto(personId, null))).isInstanceOf(BadRequestException.class);
            }
        }

        @Test
        void aBlankOrTooLongNameIsRefused() {
            assertThatThrownBy(() -> resolver.validate(REVIVAL_ID, new OwnerInputDto(null, "   "))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> resolver.validate(REVIVAL_ID, new OwnerInputDto(null, "x".repeat(101)))).isInstanceOf(BadRequestException.class);
            assertThat(resolver.validate(REVIVAL_ID, new OwnerInputDto(null, "x".repeat(100)))).isEqualTo(Owner.named("x".repeat(100)));
        }
    }

    @Nested
    class Change {

        @Test
        void theCurrentOwnerIsKeptEvenAfterLeavingTheSteeringGroup() {
            assertThat(resolver.change(REVIVAL_ID, Owner.member(REMOVED), new OwnerInputDto(REMOVED, null))).isEqualTo(Owner.member(REMOVED));
            assertThat(resolver.change(REVIVAL_ID, Owner.named("Sam"), new OwnerInputDto(null, " Sam "))).isEqualTo(Owner.named("Sam"));
        }

        @Test
        void aNewOwnerIsValidated() {
            assertThat(resolver.change(REVIVAL_ID, Owner.member(REMOVED), new OwnerInputDto(ROBIN, null))).isEqualTo(Owner.member(ROBIN));
            assertThatThrownBy(() -> resolver.change(REVIVAL_ID, Owner.member(KIM), new OwnerInputDto(REMOVED, null)))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    class Owned {

        @Test
        void aFreeTextNameOrAnActiveMemberIsOwned() {
            assertThat(resolver.isOwned(REVIVAL_ID, Owner.named("Sam"))).isTrue();
            assertThat(resolver.isOwned(REVIVAL_ID, Owner.member(KIM))).isTrue();
        }

        @Test
        void noOwnerOrAFormerMemberIsNotOwned() {
            assertThat(resolver.isOwned(REVIVAL_ID, Owner.none())).isFalse();
            assertThat(resolver.isOwned(REVIVAL_ID, Owner.member(REMOVED))).isFalse();
        }
    }
}
