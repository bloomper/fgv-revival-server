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

package nu.fgv.revival.server.mailing;

import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantDirectory;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.participant.ParticipantResponse;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.staffing.Availability;
import nu.fgv.revival.server.staffing.AvailabilityAnswer;
import nu.fgv.revival.server.staffing.AvailabilityRepository;
import nu.fgv.revival.server.staffing.ShiftAssignment;
import nu.fgv.revival.server.staffing.ShiftAssignmentRepository;
import nu.fgv.revival.server.staffing.ShiftSlot;
import nu.fgv.revival.server.staffing.ShiftSlotRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasText;

/**
 * Whom an audience reaches now (M6B-FR-8), resolved when a mailing is previewed or sent, and their email addresses at
 * that moment: a spexare's from Spexregister in one call, a pending person's own, a steering group member's login
 * address. Addresses are only handed to the mailer, never stored or logged.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Component
class MailingAudiences {

    private static final Set<ParticipantResponse> COMING = EnumSet.of(ParticipantResponse.YES, ParticipantResponse.MAYBE);
    private static final Set<AvailabilityAnswer> AVAILABLE = EnumSet.of(AvailabilityAnswer.YES, AvailabilityAnswer.MAYBE);
    private static final Set<RevivalRole> STEERING = EnumSet.of(RevivalRole.DIRECTEUR, RevivalRole.STEERING_GROUP);

    private final ParticipantRepository participantRepository;
    private final ParticipantDirectory directory;
    private final RevivalMemberRepository memberRepository;
    private final PersonRepository personRepository;
    private final PersonNames personNames;
    private final AvailabilityRepository availabilityRepository;
    private final ShiftSlotRepository slotRepository;
    private final ShiftAssignmentRepository assignmentRepository;

    MailingAudiences(final ParticipantRepository participantRepository, final ParticipantDirectory directory,
                     final RevivalMemberRepository memberRepository, final PersonRepository personRepository, final PersonNames personNames,
                     final AvailabilityRepository availabilityRepository, final ShiftSlotRepository slotRepository,
                     final ShiftAssignmentRepository assignmentRepository) {
        this.participantRepository = participantRepository;
        this.directory = directory;
        this.memberRepository = memberRepository;
        this.personRepository = personRepository;
        this.personNames = personNames;
        this.availabilityRepository = availabilityRepository;
        this.slotRepository = slotRepository;
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * An audience: its kind and, for a position or an event, which.
     */
    record Audience(MailingAudienceKind kind, @Nullable String taskId, @Nullable Long eventId) {
    }

    /**
     * One recipient: a participant or a person, by name, with the address found now or null.
     */
    record Recipient(@Nullable Long participantId, @Nullable Long personId, String displayName, @Nullable String email) {
    }

    /**
     * The audience's recipients now, by name. Fails with SPEXREGISTER_UNAVAILABLE when spexare are among them and
     * Spexregister cannot be read, since their addresses are needed.
     */
    List<Recipient> resolve(final Long revivalId, final Audience audience, final String actingPerson, final Locale locale) {
        final List<Recipient> recipients = audience.kind() == MailingAudienceKind.STEERING_GROUP ? steeringGroup(revivalId, locale)
                : recipients(participants(revivalId, audience), actingPerson, locale);

        return recipients.stream()
                .sorted(Comparator.comparing((Recipient recipient) -> recipient.displayName().toLowerCase(Locale.ROOT))
                        .thenComparing(recipient -> recipient.participantId() == null ? 0L : recipient.participantId())
                        .thenComparing(recipient -> recipient.personId() == null ? 0L : recipient.personId()))
                .toList();
    }

    /**
     * The addresses of these participants and people, read now; missing ones are left out.
     */
    Addresses addresses(final Collection<Long> participantIds, final Collection<Long> personIds, final String actingPerson) {
        final List<Participant> participants = participantIds.isEmpty() ? List.of() : participantRepository.findAllById(participantIds);
        final Map<Long, String> people = personIds.isEmpty() ? Map.of() : personRepository.findAllById(personIds).stream()
                .filter(person -> hasText(person.getEmail()))
                .collect(Collectors.toMap(Person::getId, Person::getEmail));

        return new Addresses(directory.emails(participants, actingPerson), people);
    }

    record Addresses(Map<Long, String> participants, Map<Long, String> people) {
    }

    private List<Participant> participants(final Long revivalId, final Audience audience) {
        final List<Participant> active = participantRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revivalId);

        return switch (audience.kind()) {
            case PARTICIPANTS -> active.stream().filter(participant -> isParticipant(participant) && COMING.contains(participant.getResponse())).toList();
            case CONFIRMED -> active.stream().filter(participant -> isParticipant(participant) && participant.getResponse() == ParticipantResponse.YES)
                    .toList();
            case POSITION -> active.stream().filter(participant -> isParticipant(participant) && COMING.contains(participant.getResponse())
                    && Objects.equals(participant.getPositionTaskId(), audience.taskId())).toList();
            case HELPERS -> active.stream().filter(participant -> participant.getKind() == ParticipantKind.HELPER
                    && participant.getResponse() != ParticipantResponse.NO).toList();
            case NON_RESPONDERS -> nonResponders(active);
            case EVENT_ATTENDEES -> attendees(active, Objects.requireNonNull(audience.eventId()));
            case STEERING_GROUP -> List.of();
        };
    }

    private static boolean isParticipant(final Participant participant) {
        return participant.getKind() == ParticipantKind.PARTICIPANT;
    }

    /**
     * Invited, with their role still there, and not answered.
     */
    private List<Participant> nonResponders(final List<Participant> active) {
        final List<Participant> pending = active.stream()
                .filter(participant -> participant.getResponse() == ParticipantResponse.PENDING && participant.getMemberId() != null)
                .toList();
        final Set<Long> invited = memberRepository.findAllById(pending.stream().map(Participant::getMemberId).filter(Objects::nonNull).toList())
                .stream().filter(member -> !member.isRemoved()).map(RevivalMember::getId).collect(Collectors.toSet());

        return pending.stream().filter(participant -> invited.contains(participant.getMemberId())).toList();
    }

    /**
     * Those who did not say no and are available on a day of the event, or hold a shift in it.
     */
    private List<Participant> attendees(final List<Participant> active, final Long eventId) {
        final Set<Long> attending = new HashSet<>();

        availabilityRepository.findByEventIdIn(List.of(eventId)).stream()
                .filter(availability -> AVAILABLE.contains(availability.getAnswer()))
                .map(Availability::getParticipantId)
                .forEach(attending::add);

        final List<Long> slots = slotRepository.findByEventIdIn(List.of(eventId)).stream().map(ShiftSlot::getId).toList();

        if (!slots.isEmpty()) {
            assignmentRepository.findBySlotIdIn(slots).stream().map(ShiftAssignment::getParticipantId).forEach(attending::add);
        }
        return active.stream()
                .filter(participant -> participant.getResponse() != ParticipantResponse.NO && attending.contains(participant.getId()))
                .toList();
    }

    private List<Recipient> recipients(final List<Participant> participants, final String actingPerson, final Locale locale) {
        if (participants.isEmpty()) {
            return List.of();
        }

        final Map<Long, String> emails = directory.emails(participants, actingPerson);
        final Map<Long, ParticipantDirectory.Named> names = directory.named(participants, actingPerson, locale);

        return participants.stream()
                .map(participant -> new Recipient(participant.getId(), null, names.get(participant.getId()).displayName(), emails.get(participant.getId())))
                .toList();
    }

    /**
     * The active directeurs and steering group members who have logged in, by their login address.
     */
    private List<Recipient> steeringGroup(final Long revivalId, final Locale locale) {
        final List<Long> ids = memberRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revivalId).stream()
                .filter(member -> STEERING.contains(member.getRole()) && member.getPersonId() != null)
                .map(RevivalMember::getPersonId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (ids.isEmpty()) {
            return List.of();
        }

        final Map<Long, Person> people = personRepository.findAllById(ids).stream().collect(Collectors.toMap(Person::getId, Function.identity()));
        final Map<Long, String> names = personNames.byIds(ids, locale);
        final List<Recipient> recipients = new ArrayList<>();

        for (final Long id : ids) {
            final Person person = people.get(id);

            if (person != null) {
                recipients.add(new Recipient(null, id, Objects.requireNonNullElse(names.get(id), ""),
                        hasText(person.getEmail()) ? person.getEmail() : null));
            }
        }
        return recipients;
    }
}
