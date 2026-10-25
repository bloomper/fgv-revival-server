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

import nu.fgv.revival.server.budget.BudgetSettings;
import nu.fgv.revival.server.budget.BudgetSettingsRepository;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.participant.ParticipantDtos.ParticipantDto;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasText;

/**
 * Turns participants into what the caller sees, reading names and contact details from Spexregister in one call per
 * 500 spexare and nothing from the database that the caller may not see.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
class ParticipantViews {

    private final PendingPersonRepository pendingRepository;
    private final DietaryNeedRepository dietaryRepository;
    private final RevivalMemberRepository memberRepository;
    private final BudgetSettingsRepository budgetSettingsRepository;
    private final SpexareDirectory directory;
    private final MessageSource messageSource;

    ParticipantViews(final PendingPersonRepository pendingRepository, final DietaryNeedRepository dietaryRepository,
                     final RevivalMemberRepository memberRepository, final BudgetSettingsRepository budgetSettingsRepository,
                     final SpexareDirectory directory, final MessageSource messageSource) {
        this.pendingRepository = pendingRepository;
        this.dietaryRepository = dietaryRepository;
        this.memberRepository = memberRepository;
        this.budgetSettingsRepository = budgetSettingsRepository;
        this.directory = directory;
        this.messageSource = messageSource;
    }

    /**
     * How the participant's invitation stands, from their participant role.
     */
    static InvitationState invitation(final @Nullable RevivalMember member) {
        if (member == null || member.isRemoved()) {
            return InvitationState.NOT_INVITED;
        }
        return member.getPersonId() == null ? InvitationState.INVITED : InvitationState.ACTIVE;
    }

    /**
     * The fee a participant owes: their own amount, or the revival's participation fee; null for helpers.
     */
    static @Nullable BigDecimal fee(final Participant participant, final @Nullable BigDecimal revivalFee) {
        if (participant.getKind() == ParticipantKind.HELPER) {
            return null;
        }
        return participant.getFee() != null ? participant.getFee() : revivalFee;
    }

    static SpexareDirectory.@Nullable Position position(final Map<String, SpexareDirectory.Position> positions, final @Nullable String id) {
        return id == null ? null : positions.getOrDefault(id, new SpexareDirectory.Position(id, id, null, false));
    }

    /**
     * Email and phone: a spexare's from their {@code HOME} address, mobile first; a pending person's own.
     */
    static @Nullable String[] contact(final SpexregisterClient.@Nullable Spexare spexare, final @Nullable PendingPerson person) {
        if (spexare != null) {
            return spexare.home()
                    .map(home -> new String[]{blankToNull(home.emailAddress()), hasText(home.phoneMobile()) ? home.phoneMobile() : blankToNull(home.phone())})
                    .orElseGet(() -> new String[]{null, null});
        }
        if (person != null) {
            return new String[]{person.getEmail(), person.getPhone()};
        }
        return new String[]{null, null};
    }

    static @Nullable String blankToNull(final @Nullable String value) {
        return hasText(value) ? value.strip() : null;
    }

    Map<Long, InvitationState> invitations(final Collection<Participant> participants) {
        final Map<Long, RevivalMember> members = members(participants);
        final Map<Long, InvitationState> states = new HashMap<>();

        participants.forEach(participant -> states.put(participant.getId(),
                invitation(participant.getMemberId() == null ? null : members.get(participant.getMemberId()))));
        return states;
    }

    private Map<Long, RevivalMember> members(final Collection<Participant> participants) {
        final List<Long> ids = participants.stream().map(Participant::getMemberId).filter(Objects::nonNull).distinct().toList();

        return ids.isEmpty() ? Map.of() : memberRepository.findAllById(ids).stream().collect(Collectors.toMap(RevivalMember::getId, Function.identity()));
    }

    @Nullable BigDecimal revivalFee(final Long revivalId) {
        return budgetSettingsRepository.findByRevivalId(revivalId).map(BudgetSettings::getParticipationFee).orElse(null);
    }

    /**
     * The spexare among the participants, or none when Spexregister is unavailable and {@code require} is false.
     */
    Map<Long, SpexregisterClient.Spexare> people(final Collection<Participant> participants, final String actingPerson, final boolean require) {
        final List<Long> ids = participants.stream().map(Participant::getSpexareId).filter(Objects::nonNull).distinct().toList();

        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            return directory.byIds(ids, actingPerson);
        } catch (final SpexregisterUnavailableException e) {
            if (require) {
                throw e;
            }
            return Map.of();
        }
    }

    ParticipantDto toDto(final Participant participant, final Options options) {
        return toDtos(List.of(participant), options).getFirst();
    }

    List<ParticipantDto> toDtos(final List<Participant> participants, final Options options) {
        if (participants.isEmpty()) {
            return List.of();
        }

        final List<Long> ids = participants.stream().map(Participant::getId).toList();
        final Map<Long, SpexregisterClient.Spexare> people = people(participants, options.actingPerson(), options.requirePeople());
        final Map<Long, PendingPerson> pending = pendingRepository.findByParticipantIdIn(ids).stream()
                .collect(Collectors.toMap(PendingPerson::getParticipantId, Function.identity()));
        final Map<Long, DietaryNeed> dietary = !options.dietary() ? Map.of() : dietaryRepository.findByParticipantIdIn(ids).stream()
                .collect(Collectors.toMap(DietaryNeed::getParticipantId, Function.identity()));
        final Map<Long, RevivalMember> members = members(participants);
        final Map<Long, @Nullable BigDecimal> fees = new HashMap<>();
        final boolean needsTasks = participants.stream().anyMatch(participant -> participant.getPositionTaskId() != null
                || participant.getPreferredTaskId() != null);
        final Map<String, SpexareDirectory.Position> positions = needsTasks ? directory.positionsOrEmpty() : Map.of();
        final Map<String, SpexareDirectory.VocalPart> vocalParts = participants.stream().anyMatch(participant -> participant.getVocalPartId() != null)
                ? directory.vocalPartsOrEmpty() : Map.of();

        return participants.stream().map(participant -> {
            final SpexregisterClient.Spexare spexare = participant.getSpexareId() == null ? null : people.get(participant.getSpexareId());
            final PendingPerson person = pending.get(participant.getId());
            final String[] name = name(participant, spexare, person, options.locale());
            final String[] contact = options.contacts() ? contact(spexare, person) : new String[]{null, null};
            final DietaryNeed need = dietary.get(participant.getId());
            final BigDecimal revivalFee = fees.computeIfAbsent(participant.getRevivalId(), this::revivalFee);

            return new ParticipantDto(
                    participant.getId(),
                    participant.getRevivalId(),
                    participant.getKind(),
                    participant.getSpexareId(),
                    Objects.requireNonNull(name[0]),
                    Objects.requireNonNull(name[1]),
                    name[2],
                    contact[0],
                    contact[1],
                    participant.isPending(),
                    position(positions, participant.getPositionTaskId()),
                    participant.getRoleName(),
                    participant.getVocalPartId() == null ? null
                            : vocalParts.getOrDefault(participant.getVocalPartId(), new SpexareDirectory.VocalPart(participant.getVocalPartId(),
                            participant.getVocalPartId())),
                    position(positions, participant.getPreferredTaskId()),
                    participant.getResponse(),
                    participant.getRespondedAt(),
                    participant.getComment(),
                    participant.getTermsAcceptedAt(),
                    participant.getRegisterConsent(),
                    invitation(participant.getMemberId() == null ? null : members.get(participant.getMemberId())),
                    participant.getInvitedAt(),
                    need == null ? null : need.getText(),
                    options.dietary(),
                    fee(participant, revivalFee),
                    participant.getKind() == ParticipantKind.HELPER ? null : participant.getFee(),
                    participant.isFeePaid(),
                    participant.getFeePaidAt(),
                    participant.getCompensationPerShift(),
                    participant.isMembershipPaid(),
                    participant.isRemoved(),
                    participant.getVersion() == null ? 0L : participant.getVersion());
        }).toList();
    }

    /**
     * First, last and nick name: from Spexregister, the pending person, or a placeholder once a pending person's name
     * is deleted or while the spexare cannot be read.
     */
    @Nullable String[] name(final Participant participant, final SpexregisterClient.@Nullable Spexare spexare, final @Nullable PendingPerson person,
                            final Locale locale) {
        if (spexare != null) {
            return new String[]{spexare.firstName(), spexare.lastName(), hasText(spexare.nickName()) ? spexare.nickName() : null};
        }
        if (person != null) {
            return new String[]{person.getFirstName(), person.getLastName(), null};
        }

        final String key = participant.isPending() ? "participant.removedPerson" : "participant.unknownPerson";

        return new String[]{messageSource.getMessage(key, null, key, locale), "", null};
    }

    /**
     * What to show.
     *
     * @param contacts      email and phone (readers of participants, and the person themselves)
     * @param dietary       dietary needs ({@code CATERING}, and the person themselves)
     * @param requirePeople fail with SPEXREGISTER_UNAVAILABLE when spexare cannot be read, rather than show them unnamed
     */
    record Options(boolean contacts, boolean dietary, boolean requirePeople, String actingPerson, Locale locale) {
    }
}
