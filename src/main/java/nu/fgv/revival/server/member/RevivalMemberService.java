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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.person.UserDirectory;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.DirectoryUnavailableException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static nu.fgv.revival.server.member.RevivalMemberMapper.REVIVAL_MEMBER_MAPPER;
import static nu.fgv.revival.server.person.CallerService.normalizedEmail;
import static org.springframework.util.StringUtils.hasText;

/**
 * Directeurs and steering group members of a revival (FR-019–FR-019c). Members are added by email address and wait
 * for access until a person with that verified email logs in. Removing closes the row (constitution IV).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Service
@Slf4j
public class RevivalMemberService {

    static final int CANDIDATE_QUERY_MIN_LENGTH = 3;
    static final int MAX_CANDIDATES = 10;
    static final int NAME_MAX_LENGTH = 100;

    private final RevivalMemberRepository repository;
    private final RevivalRepository revivalRepository;
    private final PersonRepository personRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final CapabilityService capabilityService;
    private final UserDirectory directory;
    private final ApplicationEventPublisher events;

    public RevivalMemberService(final RevivalMemberRepository repository,
                                final RevivalRepository revivalRepository,
                                final PersonRepository personRepository,
                                final CallerService callerService,
                                final AccessPolicy accessPolicy,
                                final CapabilityService capabilityService,
                                final UserDirectory directory,
                                final ApplicationEventPublisher events) {
        this.repository = repository;
        this.revivalRepository = revivalRepository;
        this.personRepository = personRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.capabilityService = capabilityService;
        this.directory = directory;
        this.events = events;
    }

    private Revival visibleRevival(final Caller caller, final Long revivalId) {
        return accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);
    }

    private RevivalMember activeMember(final Long id) {
        return repository.findById(id)
                .filter(member -> !member.isRemoved())
                .orElseThrow(() -> new ResourceNotFoundException(RevivalMember.class, id));
    }

    private static void requireAssignable(final RevivalRole role) {
        if (role != RevivalRole.DIRECTEUR && role != RevivalRole.STEERING_GROUP) {
            throw new BadRequestException("error.member.roleNotAssignable", role);
        }
    }

    /**
     * The members of a revival the caller can see, without removed members. Everyone who can see the revival sees names,
     * roles and capabilities; only super admins and its directeurs see email addresses (FR-019c).
     */
    @Transactional(readOnly = true)
    public List<RevivalMemberDto> findByRevival(final Long revivalId) {
        final Caller caller = callerService.current();
        final Revival revival = visibleRevival(caller, revivalId);
        final boolean showEmails = accessPolicy.canSeeMemberEmails(caller, revival);
        final List<RevivalMember> members = repository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revivalId);
        final Map<Long, Person> people = personRepository.findAllById(members.stream().map(RevivalMember::getPersonId).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));

        return members.stream()
                .map(member -> toDto(member, member.getPersonId() == null ? null : people.get(member.getPersonId()), showEmails))
                .toList();
    }

    private static RevivalMemberDto toDto(final RevivalMember member, final Person person, final boolean showEmail) {
        final String email = !showEmail ? null : person != null ? person.getEmail() : member.getPendingEmail();

        return REVIVAL_MEMBER_MAPPER.toDto(member, person != null ? person.getDisplayName() : null, email);
    }

    /**
     * Users of the FGV realm matching the query, to pick a member from: at least {@value #CANDIDATE_QUERY_MIN_LENGTH}
     * characters after trimming, at most {@value #MAX_CANDIDATES} enabled users. Whoever may manage the steering group.
     */
    @Transactional(readOnly = true)
    public List<MemberCandidateDto> candidates(final Long revivalId, final String query) {
        final Caller caller = callerService.current();
        final Revival revival = visibleRevival(caller, revivalId);

        accessPolicy.requireSearchMemberCandidates(caller, revival);

        final String trimmed = query.trim();

        if (trimmed.length() < CANDIDATE_QUERY_MIN_LENGTH) {
            throw new BadRequestException("error.member.candidates.tooShort", CANDIDATE_QUERY_MIN_LENGTH);
        }
        return directory.search(trimmed, MAX_CANDIDATES).stream()
                .map(user -> new MemberCandidateDto(user.email(), user.displayName(), user.hasAccess()))
                .toList();
    }

    /**
     * Adds a directeur or steering group member by email address. A person who has logged in with that verified address
     * becomes the member at once; anyone else waits until they do (FR-019b). For a waiting member the FGV realm is
     * asked as well: an address without an account gets one, without any role, and Keycloak emails them an
     * invitation ({@code invitedAt}); an account without {@code fgv-revival}'s {@code USER} makes the super admins
     * hear about it. While Keycloak cannot be reached the member is added all the same.
     */
    @Transactional
    public RevivalMemberDto add(final RevivalMemberAddDto dto) {
        final Caller caller = callerService.current();
        final Revival revival = visibleRevival(caller, dto.revivalId());

        requireAssignable(dto.role());
        accessPolicy.requireManageMembers(caller, revival, dto.role());
        AuditContext.operation("revivalMemberAdd");

        final Set<String> capabilities = dto.capabilities() == null ? Set.of() : Set.copyOf(dto.capabilities());

        if (dto.role() == RevivalRole.DIRECTEUR && !capabilities.isEmpty()) {
            throw new BadRequestException("error.member.capabilitiesOnDirecteur");
        }

        final String email = normalizedEmail(dto.email());

        if (!hasText(email) || !email.contains("@")) {
            throw new BadRequestException("error.member.email");
        }

        final List<Person> matches = personRepository.findByEmailAndEmailVerifiedTrue(email);
        final RevivalMember member = new RevivalMember();

        member.setRevivalId(revival.getId());
        member.setRole(dto.role());
        capabilityService.requireAssignable(capabilities, Set.of());
        member.setCapabilities(new HashSet<>(capabilities));

        if (matches.size() == 1) {
            final Long personId = matches.getFirst().getId();

            if (repository.existsByRevivalIdAndPersonIdAndRemovedAtIsNull(revival.getId(), personId)) {
                throw new BadRequestException("error.member.duplicate");
            }
            member.setPersonId(personId);
        } else {
            if (repository.existsByRevivalIdAndPendingEmailAndRemovedAtIsNull(revival.getId(), email)) {
                throw new BadRequestException("error.member.duplicate");
            }
            member.setPendingEmail(email);
        }

        final UserDirectory.@Nullable Invitation account = matches.size() == 1 ? null : account(email, dto);

        if (account != null && account.emailed()) {
            member.setInvitedAt(Instant.now());
        }

        final RevivalMember saved;

        try {
            saved = repository.saveAndFlush(member);
        } catch (final DataIntegrityViolationException _) {
            throw new BadRequestException("error.member.duplicate");
        }
        if (account != null && !account.user().hasAccess()) {
            events.publishEvent(new MemberAwaitingAccess(revival.getId(), email, account.user().displayName(), account.user().id(), dto.role(),
                    account.emailed(), caller.person().getDisplayName()));
        }
        return toDto(saved, matches.size() == 1 ? matches.getFirst() : null, accessPolicy.canSeeMemberEmails(caller, revival));
    }

    /**
     * The waiting member's Keycloak user, created and invited when there is none ({@code emailed} then says whether the
     * invitation went out), or null while Keycloak cannot be reached.
     */
    private UserDirectory.@Nullable Invitation account(final String email, final RevivalMemberAddDto dto) {
        try {
            final Optional<UserDirectory.DirectoryUser> existing = directory.findByEmail(email);

            if (existing.isPresent()) {
                return new UserDirectory.Invitation(existing.get(), false);
            }

            final UserDirectory.Invitation invitation = directory.invite(email, name(dto.firstName()), name(dto.lastName()));

            if (!invitation.emailed()) {
                log.warn("Created a Keycloak user for a waiting member, but Keycloak could not email the invitation");
            }
            return invitation;
        } catch (final DirectoryUnavailableException e) {
            log.warn("Keycloak could not be reached or refused the admin client, so a waiting member was added without an account or invitation: {}",
                    e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
            return null;
        }
    }

    private static @Nullable String name(final @Nullable String name) {
        if (!hasText(name)) {
            return null;
        }

        final String trimmed = name.trim();

        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new BadRequestException("error.member.name.tooLong", NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    @Transactional
    public RevivalMemberDto setCapabilities(final RevivalMemberCapabilitiesDto dto) {
        final Caller caller = callerService.current();
        final RevivalMember member = activeMember(dto.id());
        final Revival revival = visibleRevival(caller, member.getRevivalId());

        accessPolicy.requireManageMembers(caller, revival, RevivalRole.STEERING_GROUP);
        if (member.getRole() != RevivalRole.STEERING_GROUP) {
            throw new BadRequestException("error.member.capabilitiesNotSteeringGroup");
        }
        if (!Objects.equals(dto.version(), member.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        capabilityService.requireAssignable(dto.capabilities(), member.getCapabilities());
        AuditContext.operation("revivalMemberCapabilitiesSet");

        member.getCapabilities().clear();
        member.getCapabilities().addAll(dto.capabilities());

        final RevivalMember saved = repository.saveAndFlush(member);
        final Person person = saved.getPersonId() == null ? null : personRepository.findById(saved.getPersonId()).orElse(null);

        return toDto(saved, person, accessPolicy.canSeeMemberEmails(caller, revival));
    }

    /**
     * Closes the member: sets who removed it and when, and clears its waiting email. Nothing is deleted (FR-026, FR-028).
     */
    @Transactional
    public void remove(final Long id) {
        final Caller caller = callerService.current();
        final RevivalMember member = activeMember(id);
        final Revival revival = visibleRevival(caller, member.getRevivalId());

        accessPolicy.requireManageMembers(caller, revival, member.getRole());
        AuditContext.operation("revivalMemberRemove");

        member.setRemovedAt(Instant.now());
        member.setRemovedBy(caller.person().getId());
        member.setPendingEmail(null);
        repository.saveAndFlush(member);
    }
}
