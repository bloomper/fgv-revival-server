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

package nu.fgv.revival.server.person;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.util.security.SecurityUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasText;

/**
 * Resolves the caller once per request: checks the {@code USER} role, records the person, claims revival
 * roles waiting on their verified email, and loads their active revival roles from the database so changes apply on
 * the next request.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class CallerService {

    static final String REQUEST_ATTRIBUTE = CallerService.class.getName() + ".caller";

    private final PersonRepository personRepository;
    private final RevivalMemberRepository memberRepository;
    private final TransactionTemplate transactionTemplate;

    public CallerService(final PersonRepository personRepository,
                         final RevivalMemberRepository memberRepository,
                         final PlatformTransactionManager transactionManager) {
        this.personRepository = personRepository;
        this.memberRepository = memberRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static Set<GlobalRole> globalRoles(final Authentication authentication) {
        final Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        final Set<GlobalRole> roles = EnumSet.noneOf(GlobalRole.class);

        if (authorities.contains(SecurityUtil.ROLE_SUPER_ADMIN)) {
            roles.add(GlobalRole.SUPER_ADMIN);
        }
        if (authorities.contains(SecurityUtil.ROLE_OBSERVER)) {
            roles.add(GlobalRole.OBSERVER);
        }
        return roles;
    }

    private static boolean hasAccess(final Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SecurityUtil.ROLE_USER::equals);
    }

    public static String normalizedEmail(final String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The current caller. Refuses a caller without a token, or without the {@code USER} role on {@code fgv-revival}.
     */
    public Caller current() {
        final Authentication authentication = SecurityUtil.currentAuthentication()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No access token"));
        final Jwt jwt = SecurityUtil.currentJwt()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No access token"));

        if (!hasAccess(authentication)) {
            throw new AccessDeniedException("No access to Nyuppsättning");
        }

        final Caller propagated = CallerContext.current();

        if (propagated != null) {
            return propagated;
        }

        final RequestAttributes attributes = RequestContextHolder.getRequestAttributes();

        if (attributes != null && attributes.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof final Caller cached) {
            return cached;
        }

        final Caller caller = resolve(jwt, globalRoles(authentication));

        if (attributes != null) {
            attributes.setAttribute(REQUEST_ATTRIBUTE, caller, RequestAttributes.SCOPE_REQUEST);
        }
        return caller;
    }

    /**
     * The caller, for {@link CallerFilter}: empty without a token or access, and when resolving fails, in which case
     * {@link #current()} resolves it again and reports the error.
     */
    Optional<Caller> resolveEarly() {
        final Optional<Authentication> authentication = SecurityUtil.currentAuthentication();
        final Optional<Jwt> jwt = SecurityUtil.currentJwt();

        if (authentication.isEmpty() || jwt.isEmpty() || !hasAccess(authentication.get())) {
            return Optional.empty();
        }

        try {
            return Optional.of(resolve(jwt.get(), globalRoles(authentication.get())));
        } catch (final RuntimeException e) {
            log.warn("Could not resolve the caller before the request", e);
            return Optional.empty();
        }
    }

    private Caller resolve(final Jwt jwt, final Set<GlobalRole> globalRoles) {
        try {
            return Objects.requireNonNull(transactionTemplate.execute(_ -> resolveInTransaction(jwt, globalRoles)));
        } catch (final DataIntegrityViolationException e) {
            // Two first requests from the same person raced to insert them; the second sees the first's row
            log.debug("Retrying caller resolution after a concurrent insert");
            return Objects.requireNonNull(transactionTemplate.execute(_ -> resolveInTransaction(jwt, globalRoles)));
        }
    }

    private Caller resolveInTransaction(final Jwt jwt, final Set<GlobalRole> globalRoles) {
        final Person person = upsertPerson(jwt);

        if (person.isEmailVerified() && hasText(person.getEmail())) {
            claimWaitingRoles(person);
        }

        return new Caller(person, globalRoles, revivalRoles(person));
    }

    /**
     * The person as a caller without a token, from their revival roles alone and without global roles: for a secret
     * calendar link, which must stop working once they lose their role. Runs in the caller's transaction.
     */
    public Caller forPerson(final Person person) {
        return new Caller(person, Set.of(), revivalRoles(person));
    }

    private Map<Long, RevivalRoleAssignment> revivalRoles(final Person person) {
        final Map<Long, RevivalRoleAssignment> revivalRoles = new HashMap<>();

        memberRepository.findByPersonIdAndRemovedAtIsNull(person.getId())
                .forEach(member -> revivalRoles.put(member.getRevivalId(), new RevivalRoleAssignment(member.getRole(), member.getCapabilities())));
        return revivalRoles;
    }

    private Person upsertPerson(final Jwt jwt) {
        final String email = normalizedEmail(jwt.getClaimAsString("email"));
        final boolean emailVerified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
        final String name = jwt.getClaimAsString("name");
        final Person person = personRepository.findByExternalId(jwt.getSubject()).orElseGet(() -> {
            final Person created = new Person();

            created.setExternalId(jwt.getSubject());
            return created;
        });

        final boolean changed = person.getId() == null
                || !email.equals(person.getEmail())
                || emailVerified != person.isEmailVerified()
                || !Objects.equals(name, person.getDisplayName());

        if (!changed) {
            return person;
        }

        person.setEmail(email);
        person.setEmailVerified(emailVerified);
        person.setDisplayName(name);
        return personRepository.saveAndFlush(person);
    }

    private void claimWaitingRoles(final Person person) {
        for (final RevivalMember waiting : memberRepository.findByPendingEmailAndRemovedAtIsNull(person.getEmail())) {
            if (memberRepository.existsByRevivalIdAndPersonIdAndRemovedAtIsNull(waiting.getRevivalId(), person.getId())) {
                // One role per revival: the waiting entry stays until a directeur or super admin removes one of the two
                continue;
            }
            waiting.setPersonId(person.getId());
            waiting.setPendingEmail(null);
            memberRepository.saveAndFlush(waiting);
        }
    }
}
