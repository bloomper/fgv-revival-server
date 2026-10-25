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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.MembersAwaitingAccess;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalMemberService;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.participant.ParticipantDtos.InviteResult;
import nu.fgv.revival.server.participant.ParticipantDtos.ParticipantDto;
import nu.fgv.revival.server.participant.ParticipantDtos.ParticipantSkip;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasText;

/**
 * Invitations. Each invited participant gets the participant or helper role in the
 * revival, waiting on their email address exactly as directeurs and steering group members do, an FGV account when the
 * address has none (Keycloak emails them), and Nyuppsättning's own email with the link to their page. Inviting someone
 * again sends a reminder. The super admins hear once per call about everyone who lacks access. Addresses are never
 * logged.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ParticipantInvitations {

    static final String NO_EMAIL = "NO_EMAIL";
    static final String REMOVED = "REMOVED";
    static final String DECLINED = "DECLINED";
    static final String ALREADY_MEMBER = "ALREADY_MEMBER";

    private final ParticipantRepository repository;
    private final PendingPersonRepository pendingRepository;
    private final RevivalMemberRepository memberRepository;
    private final RevivalMemberService memberService;
    private final PersonRepository personRepository;
    private final CallerService callerService;
    private final ParticipantAccess access;
    private final ParticipantViews views;
    private final ParticipantMailer mailer;
    private final RevivalTitles titles;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ParticipantInvitations(final ParticipantRepository repository,
                                  final PendingPersonRepository pendingRepository,
                                  final RevivalMemberRepository memberRepository,
                                  final RevivalMemberService memberService,
                                  final PersonRepository personRepository,
                                  final CallerService callerService,
                                  final ParticipantAccess access,
                                  final ParticipantViews views,
                                  final ParticipantMailer mailer,
                                  final RevivalTitles titles,
                                  final AccessPolicy accessPolicy,
                                  final ApplicationEventPublisher events,
                                  final Clock clock) {
        this.repository = repository;
        this.pendingRepository = pendingRepository;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.personRepository = personRepository;
        this.callerService = callerService;
        this.access = access;
        this.views = views;
        this.mailer = mailer;
        this.titles = titles;
        this.accessPolicy = accessPolicy;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The address to invite: a pending person's own, a spexare's {@code HOME} email in Spexregister, or the one given.
     */
    static @Nullable String address(final SpexregisterClient.@Nullable Spexare spexare, final @Nullable PendingPerson person,
                                    final @Nullable String given) {
        if (person != null && hasText(person.getEmail())) {
            return person.getEmail();
        }
        if (spexare != null) {
            final String home = spexare.home().map(SpexregisterClient.Address::emailAddress).filter(email -> hasText(email)).orElse(null);

            if (home != null) {
                try {
                    return ParticipantRules.email(home);
                } catch (final BadRequestException _) {
                    // Not an address Nyuppsättning can invite; the steering group may give one instead
                }
            }
        }
        return given;
    }

    /**
     * Invites the participants, in the revival's {@code OPEN} status, by writers of participants.
     *
     * @param email    for a single participant without an address: the address to invite, kept only on the waiting role
     * @param language {@code sv} or {@code en}, the emails' language
     */
    @Transactional
    public InviteResult invite(final Long revivalId, final List<Long> participantIds, final @Nullable String email, final String language,
                               final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = access.writable(caller, revivalId);

        if (revival.getStatus() != RevivalStatus.OPEN) {
            throw new BadRequestException("error.participant.notOpen");
        }
        if (!"sv".equals(language) && !"en".equals(language)) {
            throw new BadRequestException("error.participant.invite.language");
        }

        final Set<Long> ids = new LinkedHashSet<>(participantIds);

        if (ids.size() > ParticipantService.MAX_BULK) {
            throw new BadRequestException("error.participant.tooMany", ParticipantService.MAX_BULK);
        }

        final String given = ParticipantRules.email(email);

        if (given != null && ids.size() != 1) {
            throw new BadRequestException("error.participant.invite.emailForOne");
        }

        final Map<Long, Participant> byId = repository.findAllById(ids).stream()
                .filter(participant -> participant.getRevivalId().equals(revivalId))
                .collect(Collectors.toMap(Participant::getId, Function.identity()));

        for (final Long id : ids) {
            if (!byId.containsKey(id)) {
                throw new ResourceNotFoundException(Participant.class, id);
            }
        }
        AuditContext.operation("participantsInvite");

        final List<Participant> participants = ids.stream().map(byId::get).toList();
        final Map<Long, SpexregisterClient.Spexare> people = views.people(participants.stream().filter(participant -> !participant.isRemoved()).toList(),
                ActingPerson.of(caller), true);
        final Map<Long, PendingPerson> pending = pendingRepository.findByParticipantIdIn(ids).stream()
                .collect(Collectors.toMap(PendingPerson::getParticipantId, Function.identity()));
        final Locale emailLocale = Locale.of(language);
        final ParticipantMailer.Invitation invitation = new ParticipantMailer.Invitation(titles.ofCached(revival, emailLocale),
                Objects.requireNonNullElse(caller.person().getDisplayName(), ""), false);
        final ParticipantMailer.Invitation reminder = new ParticipantMailer.Invitation(invitation.revivalTitle(), invitation.inviterName(), true);
        final List<Participant> invited = new ArrayList<>();
        final Map<Participant, String> skipped = new LinkedHashMap<>();
        final List<MembersAwaitingAccess.Waiting> waiting = new ArrayList<>();
        final List<SimpleMailMessage> messages = new ArrayList<>();
        final Instant now = Instant.now(clock);

        for (final Participant participant : participants) {
            if (participant.isRemoved()) {
                skipped.put(participant, REMOVED);
                continue;
            }
            if (participant.getResponse() == ParticipantResponse.NO) {
                skipped.put(participant, DECLINED);
                continue;
            }

            final RevivalMember member = participant.getMemberId() == null ? null
                    : memberRepository.findById(participant.getMemberId()).filter(found -> !found.isRemoved()).orElse(null);

            if (member != null) {
                final String to = member.getPendingEmail() != null ? member.getPendingEmail()
                        : member.getPersonId() == null ? null : personRepository.findById(member.getPersonId()).map(Person::getEmail).orElse(null);

                if (!hasText(to)) {
                    skipped.put(participant, NO_EMAIL);
                    continue;
                }
                messages.add(mailer.invitation(revival, reminder, to, emailLocale));
                participant.setRemindedAt(now);
                invited.add(repository.saveAndFlush(participant));
                continue;
            }

            final SpexregisterClient.Spexare spexare = participant.getSpexareId() == null ? null : people.get(participant.getSpexareId());
            final PendingPerson person = pending.get(participant.getId());
            final String address = address(spexare, person, given);

            if (address == null) {
                skipped.put(participant, NO_EMAIL);
                continue;
            }
            if (memberService.holdsRole(revivalId, address)) {
                skipped.put(participant, ALREADY_MEMBER);
                continue;
            }

            final RevivalMemberService.Assignment assignment = memberService.assign(revival,
                    participant.getKind() == ParticipantKind.HELPER ? RevivalRole.HELPER : RevivalRole.PARTICIPANT, address, Set.of(),
                    spexare != null ? spexare.firstName() : person != null ? person.getFirstName() : null,
                    spexare != null ? spexare.lastName() : person != null ? person.getLastName() : null);

            if (assignment.awaitingAccess() != null) {
                waiting.add(assignment.awaitingAccess());
            }
            participant.setMemberId(assignment.member().getId());
            participant.setInvitedAt(now);
            messages.add(mailer.invitation(revival, invitation, address, emailLocale));
            invited.add(repository.saveAndFlush(participant));
        }
        if (!waiting.isEmpty()) {
            events.publishEvent(new MembersAwaitingAccess(revivalId, waiting, caller.person().getDisplayName()));
        }
        mailer.sendAfterCommit(messages);

        final ParticipantViews.Options options = new ParticipantViews.Options(accessPolicy.canReadParticipants(caller, revival),
                accessPolicy.canReadDietary(caller, revival), false, ActingPerson.of(caller), locale);
        final Map<Long, ParticipantDto> dtos = views.toDtos(participants, options).stream()
                .collect(Collectors.toMap(ParticipantDto::id, Function.identity()));

        return new InviteResult(invited.stream().map(participant -> dtos.get(participant.getId())).toList(),
                skipped.entrySet().stream().map(entry -> new ParticipantSkip(dtos.get(entry.getKey().getId()), entry.getValue())).toList());
    }
}
