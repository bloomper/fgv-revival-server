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
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalService;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;

import java.math.BigDecimal;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A participant's own page: the response with terms and consent, what consenting writes to
 * Spexregister, contact details, dietary needs, referrals and the Swish payment.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParticipationServiceTest {

    private static final Locale SV = Locale.of("sv");
    private static final String ACTING = "participant via revival 1 (Nyuppsättning)";

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
    private BudgetSettingsRepository budgetSettingsRepository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private RevivalMemberRepository memberRepository;
    @Mock
    private RevivalService revivalService;
    @Mock
    private RevivalTitles titles;
    @Mock
    private OwnerResolver ownerResolver;
    @Mock
    private CallerService callerService;
    @Mock
    private ParticipantService participantService;
    @Mock
    private ParticipantMailer mailer;
    @Mock
    private SpexareDirectory directory;
    @Mock
    private SwishQrClient swish;

    private ParticipationService service;
    private Revival revival;
    private Participant participant;
    private PendingPerson person;

    private static ParticipationService.Response answer(final ParticipantResponse response, final Boolean terms, final Boolean consent) {
        return new ParticipationService.Response(REVIVAL_ID, response, null, null, terms, consent);
    }

    @BeforeEach
    void setUp() {
        final AccessPolicy accessPolicy = new AccessPolicy();
        final StaticMessageSource messages = new StaticMessageSource();
        final ParticipantAccess access = new ParticipantAccess(revivalRepository, memberRepository, repository, accessPolicy);
        final ParticipantViews views = new ParticipantViews(pendingRepository, dietaryRepository, memberRepository, budgetSettingsRepository, directory,
                messages);
        final RevivalMember member = new RevivalMember();
        final Caller caller = new Caller(TestCallers.steeringGroup().person(), Set.of(),
                Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.PARTICIPANT, Set.of())));

        messages.addMessage("participant.terms.fee", SV, "Deltagaravgiften är {0} kr.");
        messages.addMessage("participant.terms.loan", SV, "Lånet är {0} kr.");
        messages.addMessage("participant.terms.loanWithoutAmount", SV, "Lånet.");
        service = new ParticipationService(repository, pendingRepository, dietaryRepository, settingsRepository, referralRepository,
                budgetSettingsRepository, revivalRepository, revivalService, titles, ownerResolver, callerService, accessPolicy, access, views,
                participantService, mailer, directory, swish, messages, TestClocks.at(LocalDate.of(2028, 1, 10)));
        revival = TestCallers.revival(RevivalStatus.OPEN);
        member.setId(30L);
        member.setRevivalId(REVIVAL_ID);
        member.setRole(RevivalRole.PARTICIPANT);
        member.setPersonId(TestCallers.PERSON_ID);
        participant = new Participant();
        participant.setId(1L);
        participant.setRevivalId(REVIVAL_ID);
        participant.setKind(ParticipantKind.PARTICIPANT);
        participant.setMemberId(30L);
        participant.setVersion(0L);
        person = new PendingPerson(1L);
        person.setFirstName("Pia");
        person.setLastName("Persson");
        person.setEmail("pia@example.org");
        when(callerService.current()).thenReturn(caller);
        when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        when(memberRepository.findByRevivalIdAndPersonIdAndRemovedAtIsNull(REVIVAL_ID, TestCallers.PERSON_ID)).thenReturn(Optional.of(member));
        when(repository.findByMemberIdAndRemovedAtIsNull(30L)).thenReturn(Optional.of(participant));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pendingRepository.findById(1L)).thenReturn(Optional.of(person));
        when(pendingRepository.findByParticipantIdIn(any())).thenAnswer(_ -> participant.isPending() ? List.of(person) : List.of());
        when(titles.ofCached(any(), any())).thenReturn("Ali Baba 2028");
        when(ownerResolver.activeMembers(REVIVAL_ID)).thenReturn(Map.of(20L, "Stina Styrgrupp"));
        when(directory.positions()).thenReturn(List.of(new SpexareDirectory.Position("ROLE", "Roll", "Ensemble", true)));
        when(mailer.confirmation(any(), any(), anyString(), any())).thenReturn(new org.springframework.mail.SimpleMailMessage());
    }

    private SpexregisterClient.Spexare spexare(final SpexregisterClient.Consent consent) {
        return new SpexregisterClient.Spexare("7", "Anna", "Andersson", null, null, false,
                List.of(new SpexregisterClient.Address("70", new SpexregisterClient.TypeNode("HOME", null), "anna@example.org", null, "0701", null, null,
                        null)),
                consent == null ? List.of() : List.of(consent), List.of());
    }

    @Test
    void onlyTheParticipantOfAnOpenRevivalHasAPage() {
        when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        assertThatThrownBy(() -> service.mine(REVIVAL_ID, SV)).isInstanceOf(ResourceNotFoundException.class);

        when(callerService.current()).thenReturn(new Caller(TestCallers.steeringGroup().person(), Set.of(),
                Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.PARTICIPANT, Set.of()))));
        revival.setStatus(RevivalStatus.CLOSING);
        assertThatThrownBy(() -> service.mine(REVIVAL_ID, SV)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void yesNeedsTheTermsAndYesOrMaybeTheConsentTheFirstTime() {
        assertThatThrownBy(() -> service.respond(answer(ParticipantResponse.YES, false, true), SV))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.respond.terms");
        assertThatThrownBy(() -> service.respond(answer(ParticipantResponse.MAYBE, null, null), SV))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.respond.consent");
        assertThatThrownBy(() -> service.respond(answer(ParticipantResponse.PENDING, null, null), SV)).isInstanceOf(BadRequestException.class);

        participant.setRegisterConsent(false);
        assertThat(service.respond(answer(ParticipantResponse.MAYBE, null, null), SV).participant().response()).isEqualTo(ParticipantResponse.MAYBE);
        verify(directory, never()).create(anyString(), anyString(), anyString());
    }

    @Test
    void aPendingPersonWhoConsentsBecomesAnUnpublishedSpexare() {
        when(directory.create("Pia", "Persson", ACTING)).thenReturn(99L);

        service.respond(answer(ParticipantResponse.YES, true, true), SV);

        verify(directory).saveHomeAddress(eq(99L), isNull(), eq(new SpexregisterClient.AddressInput("pia@example.org", null, null, null, null)), eq(ACTING));
        verify(directory).saveRegisterConsent(99L, null, true, ACTING);
        verify(pendingRepository).delete(person);
        assertThat(participant.getSpexareId()).isEqualTo(99L);
        assertThat(participant.getRegisterConsent()).isTrue();
        assertThat(participant.getTermsAcceptedAt()).isNotNull();
        verify(mailer).sendAfterCommit(anyList());
    }

    @Test
    void whenSpexregisterFailsPartWayThePersonStaysPending() {
        when(directory.create("Pia", "Persson", ACTING)).thenReturn(99L);
        doThrow(new SpexregisterUnavailableException(new RuntimeException("down"))).when(directory)
                .saveHomeAddress(anyLong(), any(), any(), anyString());

        assertThatThrownBy(() -> service.respond(answer(ParticipantResponse.YES, true, true), SV)).isInstanceOf(SpexregisterUnavailableException.class);
        verify(pendingRepository, never()).delete(any());
        assertThat(participant.getSpexareId()).isNull();
    }

    @Test
    void aSpexareWhoDeclinesConsentIsRecordedInSpexregisterOnlyAfterAnEarlierConsent() {
        participant.setSpexareId(7L);
        when(directory.byId(7L, ACTING)).thenReturn(spexare(null));

        service.respond(answer(ParticipantResponse.MAYBE, null, false), SV);
        verify(directory, never()).saveRegisterConsent(anyLong(), any(), anyBoolean(), anyString());

        when(directory.byId(7L, ACTING)).thenReturn(spexare(new SpexregisterClient.Consent("71", true, new SpexregisterClient.TypeNode("REGISTER", null))));
        service.respond(answer(ParticipantResponse.MAYBE, null, false), SV);
        verify(directory).saveRegisterConsent(7L, "71", false, ACTING);

        service.respond(answer(ParticipantResponse.MAYBE, null, true), SV);
        verify(directory).saveRegisterConsent(7L, "71", true, ACTING);
    }

    @Test
    void noFromAPendingPersonClosesThemAndNoFromASpexareDoesNot() {
        service.respond(answer(ParticipantResponse.NO, null, null), SV);
        verify(participantService).close(participant, TestCallers.PERSON_ID);

        participant.setSpexareId(7L);
        when(directory.byIds(any(), anyString())).thenReturn(Map.of(7L, spexare(null)));
        service.respond(answer(ParticipantResponse.NO, null, null), SV);
        verify(participantService, org.mockito.Mockito.times(1)).close(any(), any());
    }

    @Test
    void aSpexaresContactDetailsReachSpexregisterOnlyWithConsent() {
        final ParticipationService.ContactChange change = new ParticipationService.ContactChange(REVIVAL_ID, "anna@example.org", "0702 12 34 56", "Gatan 2",
                "41102", "Göteborg");

        participant.setSpexareId(7L);
        assertThatThrownBy(() -> service.updateContact(change, SV)).hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.contact.noConsent");

        participant.setRegisterConsent(true);
        when(directory.byId(7L, ACTING)).thenReturn(spexare(null));
        service.updateContact(change, SV);
        verify(directory).saveHomeAddress(7L, "70", new SpexregisterClient.AddressInput("anna@example.org", "0702 12 34 56", "Gatan 2", "41102", "Göteborg"),
                ACTING);
    }

    @Test
    void blankDietaryNeedsAreDeleted() {
        final DietaryNeed need = new DietaryNeed();

        when(dietaryRepository.findById(1L)).thenReturn(Optional.of(need));
        service.setDietary(REVIVAL_ID, "  ", SV);
        verify(dietaryRepository).delete(need);

        assertThatThrownBy(() -> service.setDietary(REVIVAL_ID, "x".repeat(1001), SV)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void referralsAreLimitedPerParticipant() {
        when(referralRepository.countByReferredByParticipantId(1L)).thenReturn((long) ParticipationService.MAX_OPEN_REFERRALS);

        assertThatThrownBy(() -> service.createReferral(new ParticipationService.NewReferral(REVIVAL_ID, "Kalle", null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.referral.tooMany");
        assertThatThrownBy(() -> service.createReferral(new ParticipationService.NewReferral(REVIVAL_ID, " ", null, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.participant.referral.name");
    }

    @Test
    void theFeeIsPaidBySwishWithAMessageThatNamesThem() {
        final ParticipationSettings settings = new ParticipationSettings(REVIVAL_ID);
        final BudgetSettings budget = new BudgetSettings(REVIVAL_ID);

        settings.setSwishNumber("1231234567");
        settings.setWhatsappLink("https://chat.whatsapp.com/Abc");
        budget.setParticipationFee(new BigDecimal("1200.00"));
        budget.setLoanAmount(new BigDecimal("50000"));
        when(settingsRepository.findByRevivalId(REVIVAL_ID)).thenReturn(Optional.of(settings));
        when(budgetSettingsRepository.findByRevivalId(REVIVAL_ID)).thenReturn(Optional.of(budget));

        final ParticipantDtos.Participation participation = service.mine(REVIVAL_ID, SV);

        assertThat(participation.swish()).isEqualTo(new ParticipantDtos.SwishPayment("1231234567", new BigDecimal("1200.00"),
                "Ali Baba 2028 Pia Persson", true));
        assertThat(participation.terms()).containsExactly("Deltagaravgiften är 1200 kr.", "Lånet är 50000 kr.");
        assertThat(participation.whatsappLink()).isNull();
        assertThat(participation.contact()).isEqualTo(new ParticipantDtos.Contact("pia@example.org", null, null, null, null));
        assertThat(participation.steeringGroup()).extracting(ref -> ref.displayName()).containsExactly("Stina Styrgrupp");

        participant.setResponse(ParticipantResponse.YES);
        participant.setFeePaidAt(java.time.Instant.now());
        assertThat(service.mine(REVIVAL_ID, SV).swish()).isNull();
        assertThat(service.mine(REVIVAL_ID, SV).whatsappLink()).isEqualTo("https://chat.whatsapp.com/Abc");
        assertThatThrownBy(() -> service.swishQr(REVIVAL_ID, SV)).isInstanceOf(ResourceNotFoundException.class);
    }
}
