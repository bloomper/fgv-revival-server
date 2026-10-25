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

import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.participant.ParticipantDtos.Counts;
import nu.fgv.revival.server.participant.ParticipantDtos.InviteResult;
import nu.fgv.revival.server.participant.ParticipantDtos.ParticipantDto;
import nu.fgv.revival.server.participant.ParticipantDtos.Participation;
import nu.fgv.revival.server.participant.ParticipantDtos.ReferralDto;
import nu.fgv.revival.server.participant.ParticipantDtos.Settings;
import nu.fgv.revival.server.participant.ParticipantDtos.SpexareCandidate;
import nu.fgv.revival.server.revival.RevivalDto;
import nu.fgv.revival.server.spex.SpexareDirectory;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Participants over GraphQL (participant.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ParticipantGraphqlApi {

    private final ParticipantService service;
    private final ParticipantInvitations invitations;
    private final ParticipationService participation;

    public ParticipantGraphqlApi(final ParticipantService service, final ParticipantInvitations invitations, final ParticipationService participation) {
        this.service = service;
        this.invitations = invitations;
        this.participation = participation;
    }

    @QueryMapping
    public List<ParticipantDto> participants(@Argument final Long revivalId, @Argument final @Nullable Boolean includeRemoved, final Locale locale) {
        return service.list(revivalId, Boolean.TRUE.equals(includeRemoved), locale);
    }

    @QueryMapping
    public ParticipantDto participant(@Argument final Long id, final Locale locale) {
        return service.get(id, locale);
    }

    @QueryMapping
    public List<SpexareCandidate> participantCandidates(@Argument final Long revivalId) {
        return service.candidates(revivalId);
    }

    @QueryMapping
    public List<SpexareCandidate> spexareSearch(@Argument final Long revivalId, @Argument final String query) {
        return service.search(revivalId, query);
    }

    @QueryMapping
    public List<SpexareDirectory.Position> positions() {
        return service.positions();
    }

    @QueryMapping
    public List<SpexareDirectory.VocalPart> vocalParts() {
        return service.vocalParts();
    }

    @MutationMapping
    public ParticipantDto participantAdd(@Argument final ParticipantAddInput input, final Locale locale) {
        return service.add(new ParticipantService.NewParticipant(input.revivalId(), input.kind(), input.spexareId(), input.firstName(),
                input.lastName(), input.email(), input.phone(), input.positionId(), input.roleName(), input.vocalPartId(),
                input.compensationPerShift()), locale);
    }

    @MutationMapping
    public List<ParticipantDto> participantsAddSpexare(@Argument final Long revivalId, @Argument final List<Long> spexareIds, final Locale locale) {
        return service.addSpexare(revivalId, spexareIds, locale);
    }

    @MutationMapping
    public ParticipantDto participantUpdate(@Argument final ParticipantUpdateInput input, final Locale locale) {
        return service.update(new ParticipantService.Change(input.id(), input.version(), input.positionId(), Boolean.TRUE.equals(input.clearPosition()),
                input.roleName(), Boolean.TRUE.equals(input.clearRoleName()), input.vocalPartId(), Boolean.TRUE.equals(input.clearVocalPart()),
                input.fee(), Boolean.TRUE.equals(input.clearFee()), input.compensationPerShift(), input.firstName(), input.lastName(), input.email(),
                Boolean.TRUE.equals(input.clearEmail()), input.phone(), Boolean.TRUE.equals(input.clearPhone())), locale);
    }

    @MutationMapping
    public void participantRemove(@Argument final Long id, @Argument final Long version) {
        service.remove(id, version);
    }

    @MutationMapping
    public InviteResult participantsInvite(@Argument final ParticipantsInviteInput input, final Locale locale) {
        return invitations.invite(input.revivalId(), input.participantIds(), input.email(), input.language(), locale);
    }

    @MutationMapping
    public ParticipantDto participantFeeSetPaid(@Argument final Long id, @Argument final Long version, @Argument final boolean paid, final Locale locale) {
        return service.setFeePaid(id, version, paid, locale);
    }

    @MutationMapping
    public List<ParticipantDto> membershipSet(@Argument final MembershipSetInput input, final Locale locale) {
        return service.setMembership(input.revivalId(), input.year(), input.participantIds(), locale);
    }

    @MutationMapping
    public Settings participationSettingsUpdate(@Argument final ParticipationSettingsInput input) {
        return service.updateSettings(new ParticipantService.SettingsChange(input.revivalId(), input.version(), input.swishNumber(),
                input.whatsappLink(), input.terms()));
    }

    @MutationMapping
    public @Nullable ParticipantDto referralHandle(@Argument final Long id, @Argument final ReferralOutcome outcome, final Locale locale) {
        return service.handleReferral(id, outcome, locale);
    }

    @SchemaMapping(typeName = "Revival", field = "participantCounts")
    public @Nullable Counts participantCounts(final RevivalDto revival) {
        return service.counts(revival.id());
    }

    @SchemaMapping(typeName = "Revival", field = "participationSettings")
    public @Nullable Settings participationSettings(final RevivalDto revival) {
        return service.settings(revival.id());
    }

    @SchemaMapping(typeName = "Revival", field = "referrals")
    public List<ReferralDto> referrals(final RevivalDto revival, final Locale locale) {
        return service.referrals(revival.id(), locale);
    }

    @SchemaMapping(typeName = "Revival", field = "myRole")
    public @Nullable RevivalRole myRole(final RevivalDto revival) {
        return participation.myRole(revival.id());
    }

    @QueryMapping
    public Participation myParticipation(@Argument final Long revivalId, final Locale locale) {
        return participation.mine(revivalId, locale);
    }

    @MutationMapping
    public Participation participationRespond(@Argument final ParticipationRespondInput input, final Locale locale) {
        return participation.respond(new ParticipationService.Response(input.revivalId(), input.response(), input.preferredPositionId(),
                input.comment(), input.termsAccepted(), input.registerConsent()), locale);
    }

    @MutationMapping
    public Participation participationContactUpdate(@Argument final ContactInput input, final Locale locale) {
        return participation.updateContact(new ParticipationService.ContactChange(input.revivalId(), input.email(), input.phone(),
                input.streetAddress(), input.postalCode(), input.city()), locale);
    }

    @MutationMapping
    public Participation participationDietarySet(@Argument final Long revivalId, @Argument final @Nullable String text, final Locale locale) {
        return participation.setDietary(revivalId, text, locale);
    }

    @MutationMapping
    public void referralCreate(@Argument final ReferralInput input) {
        participation.createReferral(new ParticipationService.NewReferral(input.revivalId(), input.name(), input.contact(), input.comment()));
    }

    public record ParticipantAddInput(Long revivalId, ParticipantKind kind, @Nullable Long spexareId,
                                      @Nullable String firstName,
                                      @Nullable String lastName, @Nullable String email, @Nullable String phone,
                                      @Nullable String positionId,
                                      @Nullable String roleName, @Nullable String vocalPartId,
                                      @Nullable BigDecimal compensationPerShift) {
    }

    public record ParticipantUpdateInput(Long id, Long version, @Nullable String positionId,
                                         @Nullable Boolean clearPosition,
                                         @Nullable String roleName, @Nullable Boolean clearRoleName,
                                         @Nullable String vocalPartId,
                                         @Nullable Boolean clearVocalPart, @Nullable BigDecimal fee,
                                         @Nullable Boolean clearFee,
                                         @Nullable BigDecimal compensationPerShift, @Nullable String firstName,
                                         @Nullable String lastName,
                                         @Nullable String email, @Nullable Boolean clearEmail, @Nullable String phone,
                                         @Nullable Boolean clearPhone) {
    }

    public record ParticipantsInviteInput(Long revivalId, List<Long> participantIds, @Nullable String email,
                                          String language) {
    }

    public record ParticipationSettingsInput(Long revivalId, Long version, @Nullable String swishNumber,
                                             @Nullable String whatsappLink,
                                             @Nullable String terms) {
    }

    public record ReferralInput(Long revivalId, String name, @Nullable String contact, @Nullable String comment) {
    }

    public record ContactInput(Long revivalId, @Nullable String email, @Nullable String phone,
                               @Nullable String streetAddress,
                               @Nullable String postalCode, @Nullable String city) {
    }

    public record ParticipationRespondInput(Long revivalId, ParticipantResponse response,
                                            @Nullable String preferredPositionId,
                                            @Nullable String comment, @Nullable Boolean termsAccepted,
                                            @Nullable Boolean registerConsent) {
    }

    public record MembershipSetInput(Long revivalId, Integer year, List<Long> participantIds) {
    }
}
