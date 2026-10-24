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

import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.revival.RevivalDto;
import nu.fgv.revival.server.spex.SpexareDirectory;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The GraphQL types of participants (participant.graphqls).
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
public final class ParticipantDtos {

    private ParticipantDtos() {
    }

    /**
     * A participant as the caller may see them: contact details only for readers of participants, dietary needs only
     * with {@code CATERING} or for themselves.
     */
    public record ParticipantDto(Long id,
                                 Long revivalId,
                                 ParticipantKind kind,
                                 @Nullable Long spexareId,
                                 String firstName,
                                 String lastName,
                                 @Nullable String nickName,
                                 @Nullable String email,
                                 @Nullable String phone,
                                 boolean pending,
                                 SpexareDirectory.@Nullable Position position,
                                 @Nullable String roleName,
                                 SpexareDirectory.@Nullable VocalPart vocalPart,
                                 SpexareDirectory.@Nullable Position preferredPosition,
                                 ParticipantResponse response,
                                 @Nullable Instant respondedAt,
                                 @Nullable String comment,
                                 @Nullable Instant termsAcceptedAt,
                                 @Nullable Boolean registerConsent,
                                 InvitationState invitation,
                                 @Nullable Instant invitedAt,
                                 @Nullable String dietary,
                                 boolean dietaryReadable,
                                 @Nullable BigDecimal fee,
                                 @Nullable BigDecimal feeOverride,
                                 boolean feePaid,
                                 @Nullable Instant feePaidAt,
                                 @Nullable BigDecimal compensationPerShift,
                                 boolean membershipPaid,
                                 boolean removed,
                                 Long version) {
    }

    public record SpexareCandidate(Long spexareId, String firstName, String lastName, @Nullable String nickName, @Nullable String graduation,
                                   List<String> history, boolean added) {
    }

    public record ParticipantSkip(ParticipantDto participant, String reason) {
    }

    public record InviteResult(List<ParticipantDto> invited, List<ParticipantSkip> skipped) {
    }

    public record Settings(@Nullable String swishNumber, @Nullable String whatsappLink, @Nullable String terms, Long version) {

        static Settings of(final @Nullable ParticipationSettings settings) {
            return settings == null ? new Settings(null, null, null, 0L)
                    : new Settings(settings.getSwishNumber(), settings.getWhatsappLink(), settings.getTerms(),
                    settings.getVersion() == null ? 0L : settings.getVersion());
        }
    }

    public record ResponseCount(ParticipantResponse response, int count) {
    }

    public record PositionCount(SpexareDirectory.@Nullable Position position, int yes, int maybe, int pending) {
    }

    public record Counts(int total, List<ResponseCount> byResponse, List<PositionCount> byPosition, int notInvited, int waitingForAccess,
                         int feesPaid, int feesUnpaid, int helpers) {
    }

    public record ReferralDto(Long id, String name, @Nullable String contact, @Nullable String comment, @Nullable String referredBy,
                              Instant createdAt) {
    }

    public record Contact(@Nullable String email, @Nullable String phone, @Nullable String streetAddress, @Nullable String postalCode,
                          @Nullable String city) {
    }

    public record SwishPayment(String number, BigDecimal amount, String message, boolean qrAvailable) {
    }

    public record Participation(ParticipantDto participant, RevivalDto revival, Settings settings, List<String> terms, @Nullable Contact contact,
                                @Nullable String dietary, @Nullable SwishPayment swish, @Nullable String whatsappLink,
                                List<PersonRefDto> steeringGroup) {
    }
}
