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

package nu.fgv.revival.server.participant;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.budget.BudgetSettings;
import nu.fgv.revival.server.budget.BudgetSettingsRepository;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalMemberService;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The steering group's side of participants (M6-FR-2–4, M6-FR-16–18).
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParticipantServiceTest {

    private static final Locale EN = Locale.ENGLISH;

    @Mock
    private ParticipantRepository repository;
    @Mock
    private PendingPersonRepository pendingRepository;
    @Mock
    private DietaryNeedRepository dietaryRepository;
    @Mock
    private ParticipationSettingsRepository settingsRepository;
    @Mock
    private ReferralRepository referralRepository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private RevivalMemberRepository memberRepository;
    @Mock
    private RevivalMemberService memberService;
    @Mock
    private BudgetSettingsRepository budgetSettingsRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private SpexareDirectory directory;

    private ParticipantService service;
    private Revival revival;

    private static ParticipantService.NewParticipant pending(final ParticipantKind kind, final String email, final String phone) {
        return new ParticipantService.NewParticipant(REVIVAL_ID, kind, null, "Pia", "Persson", email, phone, null, null, null, null);
    }

    private static Participant participant(final long id, final ParticipantKind kind, final Long spexareId) {
        final Participant participant = new Participant();

        participant.setId(id);
        participant.setRevivalId(REVIVAL_ID);
        participant.setKind(kind);
        participant.setSpexareId(spexareId);
        participant.setVersion(2L);
        return participant;
    }

    private static Caller with(final RevivalRole role, final String... capabilities) {
        return new Caller(TestCallers.steeringGroup().person(), Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(role, Set.of(capabilities))));
    }

    @BeforeEach
    void setUp() {
        final AccessPolicy accessPolicy = new AccessPolicy();
        final Clock clock = TestClocks.at(LocalDate.of(2028, 1, 10));
        final StaticMessageSource messages = new StaticMessageSource();
        final ParticipantAccess access = new ParticipantAccess(revivalRepository, memberRepository, repository, accessPolicy);
        final ParticipantViews views = new ParticipantViews(pendingRepository, dietaryRepository, memberRepository, budgetSettingsRepository, directory,
                messages);

        messages.addMessage("participant.removedPerson", EN, "Removed person");
        service = new ParticipantService(repository, pendingRepository, dietaryRepository, settingsRepository, referralRepository, revivalRepository,
                memberRepository, memberService, callerService, accessPolicy, access, views, directory, clock);
        revival = TestCallers.revival(RevivalStatus.OPEN);
        when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            final Participant participant = invocation.getArgument(0);

            if (participant.getId() == null) {
                participant.setId(50L);
                participant.setVersion(0L);
            }
            return participant;
        });
        when(directory.positions()).thenReturn(List.of(new SpexareDirectory.Position("ROLE", "Roll", "Ensemble", true),
                new SpexareDirectory.Position("TRUMPET", "Trumpet", "Orkester", false)));
        when(directory.vocalParts()).thenReturn(List.of(new SpexareDirectory.VocalPart("B1", "Bas 1")));
        when(directory.positionsOrEmpty()).thenReturn(Map.of());
        when(directory.vocalPartsOrEmpty()).thenReturn(Map.of());
    }

    @Test
    void aPendingParticipantNeedsAnEmailAndAHelperEmailOrPhone() {
        assertThatThrownBy(() -> service.add(pending(ParticipantKind.PARTICIPANT, null, "0701234567"), EN))
                .isInstanceOf(BadRequestException.class).hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.emailRequired");
        assertThatThrownBy(() -> service.add(pending(ParticipantKind.HELPER, null, null), EN))
                .isInstanceOf(BadRequestException.class).hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.contactRequired");

        final ParticipantDtos.ParticipantDto helper = service.add(pending(ParticipantKind.HELPER, null, "0701234567"), EN);
        final ArgumentCaptor<PendingPerson> saved = ArgumentCaptor.forClass(PendingPerson.class);

        verify(pendingRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getParticipantId()).isEqualTo(50L);
        assertThat(saved.getValue().getPhone()).isEqualTo("0701234567");
        assertThat(helper.kind()).isEqualTo(ParticipantKind.HELPER);
        assertThat(helper.pending()).isTrue();
        assertThat(helper.fee()).isNull();
    }

    @Test
    void aSpexareMustExistNotBeDeceasedNorBeAddedTwice() {
        final ParticipantService.NewParticipant input = new ParticipantService.NewParticipant(REVIVAL_ID, ParticipantKind.PARTICIPANT, 7L,
                null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.add(input, EN)).hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.spexare.notFound");

        when(directory.byId(eq(7L), anyString())).thenReturn(new SpexregisterClient.Spexare("7", "Anna", "Andersson", null, null, true, null, null, null));
        assertThatThrownBy(() -> service.add(input, EN)).hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.spexare.deceased");

        when(directory.byId(eq(7L), anyString())).thenReturn(new SpexregisterClient.Spexare("7", "Anna", "Andersson", null, null, false, null, null, null));
        when(repository.existsByRevivalIdAndSpexareIdAndRemovedAtIsNull(REVIVAL_ID, 7L)).thenReturn(true);
        assertThatThrownBy(() -> service.add(input, EN)).hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.duplicate");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void onlyAnEnsemblePositionHasARoleAndVocalPart() {
        final ParticipantService.NewParticipant trumpet = new ParticipantService.NewParticipant(REVIVAL_ID, ParticipantKind.PARTICIPANT, null,
                "Pia", "Persson", "pia@example.org", null, "TRUMPET", "Kalif", null, null);
        final ParticipantService.NewParticipant unknown = new ParticipantService.NewParticipant(REVIVAL_ID, ParticipantKind.PARTICIPANT, null,
                "Pia", "Persson", "pia@example.org", null, "NOPE", null, null, null);
        final ParticipantService.NewParticipant role = new ParticipantService.NewParticipant(REVIVAL_ID, ParticipantKind.PARTICIPANT, null,
                "Pia", "Persson", "pia@example.org", null, "ROLE", "Kalif", "B1", null);

        assertThatThrownBy(() -> service.add(trumpet, EN)).hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.role.notEnsemble");
        assertThatThrownBy(() -> service.add(unknown, EN)).hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.position.unknown");
        assertThat(service.add(role, EN).roleName()).isEqualTo("Kalif");
    }

    @Test
    void compensationIsForHelpersOnly() {
        final ParticipantService.NewParticipant input = new ParticipantService.NewParticipant(REVIVAL_ID, ParticipantKind.PARTICIPANT, null,
                "Pia", "Persson", "pia@example.org", null, null, null, null, new BigDecimal("300"));

        assertThatThrownBy(() -> service.add(input, EN)).hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.compensation.helperOnly");
    }

    @Test
    void observersParticipantsAndHelpersDoNotReadParticipants() {
        when(callerService.current()).thenReturn(TestCallers.observer());
        assertThatThrownBy(() -> service.list(REVIVAL_ID, false, EN)).isInstanceOf(AccessDeniedException.class);

        when(callerService.current()).thenReturn(with(RevivalRole.PARTICIPANT));
        assertThatThrownBy(() -> service.list(REVIVAL_ID, false, EN)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.add(pending(ParticipantKind.PARTICIPANT, "a@example.org", null), EN)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.counts(REVIVAL_ID)).isNull();
        assertThat(service.settings(REVIVAL_ID)).isNull();
        assertThat(service.referrals(REVIVAL_ID, EN)).isEmpty();

        when(callerService.current()).thenReturn(with(RevivalRole.HELPER));
        assertThatThrownBy(() -> service.list(REVIVAL_ID, false, EN)).isInstanceOf(AccessDeniedException.class);

        when(callerService.current()).thenReturn(TestCallers.nobody());
        assertThatThrownBy(() -> service.list(REVIVAL_ID, false, EN)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void contactDetailsForReadersAndDietaryNeedsOnlyWithCatering() {
        final Participant pending = participant(1L, ParticipantKind.PARTICIPANT, null);
        final PendingPerson person = new PendingPerson(1L);
        final DietaryNeed need = new DietaryNeed();

        person.setFirstName("Pia");
        person.setLastName("Persson");
        person.setEmail("pia@example.org");
        need.setParticipantId(1L);
        need.setText("Nötter");
        when(repository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(REVIVAL_ID)).thenReturn(List.of(pending));
        when(pendingRepository.findByParticipantIdIn(anyCollection())).thenReturn(List.of(person));
        when(dietaryRepository.findByParticipantIdIn(anyCollection())).thenReturn(List.of(need));

        when(callerService.current()).thenReturn(with(RevivalRole.STEERING_GROUP, AccessPolicy.FINANCE));
        assertThat(service.list(REVIVAL_ID, false, EN)).singleElement().satisfies(dto -> {
            assertThat(dto.email()).isEqualTo("pia@example.org");
            assertThat(dto.dietary()).isNull();
            assertThat(dto.dietaryReadable()).isFalse();
        });

        when(callerService.current()).thenReturn(with(RevivalRole.STEERING_GROUP, AccessPolicy.CATERING));
        assertThat(service.list(REVIVAL_ID, false, EN)).singleElement().satisfies(dto -> assertThat(dto.dietary()).isEqualTo("Nötter"));

        when(callerService.current()).thenReturn(TestCallers.superAdmin());
        assertThat(service.list(REVIVAL_ID, false, EN)).singleElement().satisfies(dto -> {
            assertThat(dto.email()).isEqualTo("pia@example.org");
            assertThat(dto.dietary()).isNull();
        });
    }

    @Test
    void removingAPendingPersonDeletesTheirDataAndClosesTheirRole() {
        final Participant pending = participant(1L, ParticipantKind.PARTICIPANT, null);
        final RevivalMember member = new RevivalMember();
        final PendingPerson person = new PendingPerson(1L);
        final DietaryNeed need = new DietaryNeed();

        member.setId(30L);
        member.setRole(RevivalRole.PARTICIPANT);
        member.setPendingEmail("pia@example.org");
        pending.setMemberId(30L);
        when(repository.findById(1L)).thenReturn(Optional.of(pending));
        when(memberRepository.findById(30L)).thenReturn(Optional.of(member));
        when(pendingRepository.findById(1L)).thenReturn(Optional.of(person));
        when(dietaryRepository.findById(1L)).thenReturn(Optional.of(need));

        assertThatThrownBy(() -> service.remove(1L, 1L)).isInstanceOf(ConflictException.class);
        service.remove(1L, 2L);

        assertThat(pending.isRemoved()).isTrue();
        assertThat(pending.getRemovedBy()).isEqualTo(TestCallers.PERSON_ID);
        verify(pendingRepository).delete(person);
        verify(dietaryRepository).delete(need);
        verify(memberService).close(member, TestCallers.PERSON_ID);
    }

    @Test
    void feesAreMarkedPaidByWritersOfTheBudget() {
        final Participant participant = participant(1L, ParticipantKind.PARTICIPANT, null);
        final BudgetSettings budget = new BudgetSettings(REVIVAL_ID);

        budget.setParticipationFee(new BigDecimal("1500.00"));
        when(repository.findById(1L)).thenReturn(Optional.of(participant));
        when(budgetSettingsRepository.findByRevivalId(REVIVAL_ID)).thenReturn(Optional.of(budget));

        when(callerService.current()).thenReturn(with(RevivalRole.STEERING_GROUP, AccessPolicy.CATERING));
        assertThatThrownBy(() -> service.setFeePaid(1L, 2L, true, EN)).isInstanceOf(AccessDeniedException.class);

        when(callerService.current()).thenReturn(with(RevivalRole.STEERING_GROUP, AccessPolicy.FINANCE));

        final ParticipantDtos.ParticipantDto paid = service.setFeePaid(1L, 2L, true, EN);

        assertThat(paid.feePaid()).isTrue();
        assertThat(paid.fee()).isEqualByComparingTo("1500");
        assertThat(participant.getFeePaidBy()).isEqualTo(TestCallers.PERSON_ID);
    }

    @Test
    void settingsAreValidatedAndVersioned() {
        when(settingsRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.updateSettings(new ParticipantService.SettingsChange(REVIVAL_ID, 1L, null, null, null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.updateSettings(new ParticipantService.SettingsChange(REVIVAL_ID, 0L, "123", null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.settings.swish");
        assertThatThrownBy(() -> service.updateSettings(new ParticipantService.SettingsChange(REVIVAL_ID, 0L, null, "https://example.org", null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.settings.whatsapp");

        final ParticipantDtos.Settings settings = service.updateSettings(new ParticipantService.SettingsChange(REVIVAL_ID, 0L, "123 456 78 90",
                "https://chat.whatsapp.com/Abc", " Ta med glatt humör. "));

        assertThat(settings.swishNumber()).isEqualTo("1234567890");
        assertThat(settings.terms()).isEqualTo("Ta med glatt humör.");
    }

    @Test
    void anAddedReferralBecomesAPendingParticipantAndIsDeleted() {
        final Referral referral = new Referral();

        referral.setId(5L);
        referral.setRevivalId(REVIVAL_ID);
        referral.setName("Anna Maria Andersson");
        referral.setContact("Anna@Example.org");
        when(referralRepository.findById(5L)).thenReturn(Optional.of(referral));

        service.handleReferral(5L, ReferralOutcome.ADDED, EN);

        final ArgumentCaptor<PendingPerson> person = ArgumentCaptor.forClass(PendingPerson.class);

        verify(pendingRepository).saveAndFlush(person.capture());
        assertThat(person.getValue().getFirstName()).isEqualTo("Anna Maria");
        assertThat(person.getValue().getLastName()).isEqualTo("Andersson");
        assertThat(person.getValue().getEmail()).isEqualTo("anna@example.org");
        verify(referralRepository).delete(referral);
    }

    @Test
    void countsCoverResponsesInvitationsAndFees() {
        final Participant yes = participant(1L, ParticipantKind.PARTICIPANT, 7L);
        final Participant paid = participant(2L, ParticipantKind.PARTICIPANT, 8L);
        final Participant helper = participant(3L, ParticipantKind.HELPER, null);
        final RevivalMember waiting = new RevivalMember();
        final BudgetSettings budget = new BudgetSettings(REVIVAL_ID);

        yes.setResponse(ParticipantResponse.YES);
        paid.setResponse(ParticipantResponse.YES);
        paid.setFeePaidAt(java.time.Instant.now());
        waiting.setId(30L);
        waiting.setRole(RevivalRole.PARTICIPANT);
        waiting.setPendingEmail("x@example.org");
        yes.setMemberId(30L);
        budget.setParticipationFee(new BigDecimal("1000"));
        when(repository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(REVIVAL_ID)).thenReturn(List.of(yes, paid, helper));
        when(memberRepository.findAllById(any())).thenReturn(List.of(waiting));
        when(budgetSettingsRepository.findByRevivalId(REVIVAL_ID)).thenReturn(Optional.of(budget));

        final ParticipantDtos.Counts counts = service.counts(REVIVAL_ID);

        assertThat(counts).isNotNull();
        assertThat(counts.total()).isEqualTo(3);
        assertThat(counts.helpers()).isEqualTo(1);
        assertThat(counts.notInvited()).isEqualTo(2);
        assertThat(counts.waitingForAccess()).isEqualTo(1);
        assertThat(counts.feesPaid()).isEqualTo(1);
        assertThat(counts.feesUnpaid()).isEqualTo(1);
        assertThat(counts.byResponse()).contains(new ParticipantDtos.ResponseCount(ParticipantResponse.YES, 2),
                new ParticipantDtos.ResponseCount(ParticipantResponse.PENDING, 1));
    }
}
