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
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalDto;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Participants over GraphQL (participant.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ParticipantGraphqlApi {

    private final ExternalIds ids;

    private final ParticipantService service;
    private final ParticipantInvitations invitations;
    private final ParticipationService participation;

    public ParticipantGraphqlApi(final ParticipantService service, final ParticipantInvitations invitations, final ParticipationService participation, final ExternalIds ids) {
        this.ids = ids;
        this.service = service;
        this.invitations = invitations;
        this.participation = participation;
    }

    @QueryMapping
    public List<ParticipantDto> participants(@Argument final UUID revivalId, @Argument final @Nullable Boolean includeRemoved, final Locale locale) {
        return service.list(ids.idOf(Revival.class, revivalId), Boolean.TRUE.equals(includeRemoved), locale);
    }

    @QueryMapping
    public ParticipantDto participant(@Argument final UUID id, final Locale locale) {
        return service.get(ids.idOf(Participant.class, id), locale);
    }

    @QueryMapping
    public List<SpexareCandidate> participantCandidates(@Argument final UUID revivalId) {
        return service.candidates(ids.idOf(Revival.class, revivalId));
    }

    @QueryMapping
    public List<SpexareCandidate> spexareSearch(@Argument final UUID revivalId, @Argument final String query) {
        return service.search(ids.idOf(Revival.class, revivalId), query);
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
        return service.add(new ParticipantService.NewParticipant(ids.idOf(Revival.class, input.revivalId()), input.kind(), input.spexareId(), input.firstName(),
                input.lastName(), input.email(), input.phone(), input.positionId(), input.roleName(), input.vocalPartId(),
                input.compensationPerShift()), locale);
    }

    @MutationMapping
    public List<ParticipantDto> participantsAddSpexare(@Argument final UUID revivalId, @Argument final List<String> spexareIds, final Locale locale) {
        return service.addSpexare(ids.idOf(Revival.class, revivalId), spexareIds, locale);
    }

    @MutationMapping
    public ParticipantDto participantUpdate(@Argument final ParticipantUpdateInput input, final Locale locale) {
        return service.update(new ParticipantService.Change(ids.idOf(Participant.class, input.id()), input.version(), input.positionId(), Boolean.TRUE.equals(input.clearPosition()),
                input.roleName(), Boolean.TRUE.equals(input.clearRoleName()), input.vocalPartId(), Boolean.TRUE.equals(input.clearVocalPart()),
                input.fee(), Boolean.TRUE.equals(input.clearFee()), input.compensationPerShift(), input.firstName(), input.lastName(), input.email(),
                Boolean.TRUE.equals(input.clearEmail()), input.phone(), Boolean.TRUE.equals(input.clearPhone())), locale);
    }

    @MutationMapping
    public void participantRemove(@Argument final UUID id, @Argument final Long version) {
        service.remove(ids.idOf(Participant.class, id), version);
    }

    @MutationMapping
    public InviteResult participantsInvite(@Argument final ParticipantsInviteInput input, final Locale locale) {
        return invitations.invite(ids.idOf(Revival.class, input.revivalId()), ids.idsOf(Participant.class, input.participantIds()), input.email(), input.language(), locale);
    }

    @MutationMapping
    public ParticipantDto participantFeeSetPaid(@Argument final UUID id, @Argument final Long version, @Argument final boolean paid, final Locale locale) {
        return service.setFeePaid(ids.idOf(Participant.class, id), version, paid, locale);
    }

    @MutationMapping
    public List<ParticipantDto> membershipSet(@Argument final MembershipSetInput input, final Locale locale) {
        return service.setMembership(ids.idOf(Revival.class, input.revivalId()), input.year(), ids.idsOf(Participant.class, input.participantIds()), locale);
    }

    @MutationMapping
    public Settings participationSettingsUpdate(@Argument final ParticipationSettingsInput input) {
        return service.updateSettings(new ParticipantService.SettingsChange(ids.idOf(Revival.class, input.revivalId()), input.version(), input.swishNumber(),
                input.whatsappLink(), input.terms()));
    }

    @MutationMapping
    public @Nullable ParticipantDto referralHandle(@Argument final UUID id, @Argument final ReferralOutcome outcome, final Locale locale) {
        return service.handleReferral(ids.idOf(Referral.class, id), outcome, locale);
    }

    @SchemaMapping(typeName = "Revival", field = "participantCounts")
    public @Nullable Counts participantCounts(final RevivalDto revival) {
        return service.counts(ids.idOf(Revival.class, revival.id()));
    }

    @SchemaMapping(typeName = "Revival", field = "participationSettings")
    public @Nullable Settings participationSettings(final RevivalDto revival) {
        return service.settings(ids.idOf(Revival.class, revival.id()));
    }

    @SchemaMapping(typeName = "Revival", field = "referrals")
    public List<ReferralDto> referrals(final RevivalDto revival, final Locale locale) {
        return service.referrals(ids.idOf(Revival.class, revival.id()), locale);
    }

    @SchemaMapping(typeName = "Revival", field = "myRole")
    public @Nullable RevivalRole myRole(final RevivalDto revival) {
        return participation.myRole(ids.idOf(Revival.class, revival.id()));
    }

    @QueryMapping
    public Participation myParticipation(@Argument final UUID revivalId, final Locale locale) {
        return participation.mine(ids.idOf(Revival.class, revivalId), locale);
    }

    @MutationMapping
    public Participation participationRespond(@Argument final ParticipationRespondInput input, final Locale locale) {
        return participation.respond(new ParticipationService.Response(ids.idOf(Revival.class, input.revivalId()), input.response(), input.preferredPositionId(),
                input.comment(), input.termsAccepted(), input.registerConsent()), locale);
    }

    @MutationMapping
    public Participation participationContactUpdate(@Argument final ContactInput input, final Locale locale) {
        return participation.updateContact(new ParticipationService.ContactChange(ids.idOf(Revival.class, input.revivalId()), input.email(), input.phone(),
                input.streetAddress(), input.postalCode(), input.city()), locale);
    }

    @MutationMapping
    public Participation participationDietarySet(@Argument final UUID revivalId, @Argument final @Nullable String text, final Locale locale) {
        return participation.setDietary(ids.idOf(Revival.class, revivalId), text, locale);
    }

    @MutationMapping
    public void referralCreate(@Argument final ReferralInput input) {
        participation.createReferral(new ParticipationService.NewReferral(ids.idOf(Revival.class, input.revivalId()), input.name(), input.contact(), input.comment()));
    }

    public record ParticipantAddInput(UUID revivalId, ParticipantKind kind, @Nullable String spexareId,
                                      @Nullable String firstName,
                                      @Nullable String lastName, @Nullable String email, @Nullable String phone,
                                      @Nullable String positionId,
                                      @Nullable String roleName, @Nullable String vocalPartId,
                                      @Nullable BigDecimal compensationPerShift) {
    }

    public record ParticipantUpdateInput(UUID id, Long version, @Nullable String positionId,
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

    public record ParticipantsInviteInput(UUID revivalId, List<UUID> participantIds, @Nullable String email,
                                          String language) {
    }

    public record ParticipationSettingsInput(UUID revivalId, Long version, @Nullable String swishNumber,
                                             @Nullable String whatsappLink,
                                             @Nullable String terms) {
    }

    public record ReferralInput(UUID revivalId, String name, @Nullable String contact, @Nullable String comment) {
    }

    public record ContactInput(UUID revivalId, @Nullable String email, @Nullable String phone,
                               @Nullable String streetAddress,
                               @Nullable String postalCode, @Nullable String city) {
    }

    public record ParticipationRespondInput(UUID revivalId, ParticipantResponse response,
                                            @Nullable String preferredPositionId,
                                            @Nullable String comment, @Nullable Boolean termsAccepted,
                                            @Nullable Boolean registerConsent) {
    }

    public record MembershipSetInput(UUID revivalId, Integer year, List<UUID> participantIds) {
    }
}
