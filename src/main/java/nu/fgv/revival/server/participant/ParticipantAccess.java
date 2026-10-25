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

import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.springframework.stereotype.Component;

/**
 * The checks every participant operation starts with. A revival the caller cannot see, and a participation
 * that is not the caller's, are reported as missing. Staffing uses {@link #own} for the caller's availability and
 * shifts.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ParticipantAccess {

    private final RevivalRepository revivalRepository;
    private final RevivalMemberRepository memberRepository;
    private final ParticipantRepository participantRepository;
    private final AccessPolicy accessPolicy;

    ParticipantAccess(final RevivalRepository revivalRepository, final RevivalMemberRepository memberRepository,
                      final ParticipantRepository participantRepository, final AccessPolicy accessPolicy) {
        this.revivalRepository = revivalRepository;
        this.memberRepository = memberRepository;
        this.participantRepository = participantRepository;
        this.accessPolicy = accessPolicy;
    }

    public Revival visible(final Caller caller, final Long revivalId) {
        return accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);
    }

    Revival readable(final Caller caller, final Long revivalId) {
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireReadParticipants(caller, revival);
        return revival;
    }

    Revival writable(final Caller caller, final Long revivalId) {
        final Revival revival = readable(caller, revivalId);

        accessPolicy.requireWriteParticipants(caller, revival);
        if (revival.isAnonymized()) {
            // Nothing personal is added once the people have been removed
            throw new BadRequestException("error.revival.anonymized");
        }
        return revival;
    }

    /**
     * A participant of a revival the caller reads participants of; missing otherwise.
     */
    Participant readableParticipant(final Caller caller, final Long id) {
        final Participant participant = participantRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(Participant.class, id));
        final Revival revival = revivalRepository.findById(participant.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canSee(caller, revival) || !accessPolicy.canReadParticipants(caller, revival)) {
            throw new ResourceNotFoundException(Participant.class, id);
        }
        return participant;
    }

    /**
     * The caller's own participation in a revival they can see as its participant or helper, i.e. while it is
     * {@code OPEN}: through their active role, which is their participant row's login.
     */
    public Own own(final Caller caller, final Long revivalId) {
        final Revival revival = visible(caller, revivalId);

        if (!accessPolicy.canParticipate(caller, revival)) {
            throw new ResourceNotFoundException(Participant.class, revivalId);
        }
        return memberRepository.findByRevivalIdAndPersonIdAndRemovedAtIsNull(revivalId, caller.person().getId())
                .filter(member -> member.getRole() == RevivalRole.PARTICIPANT || member.getRole() == RevivalRole.HELPER)
                .flatMap(member -> participantRepository.findByMemberIdAndRemovedAtIsNull(member.getId()).map(found -> new Own(revival, member, found)))
                .orElseThrow(() -> new ResourceNotFoundException(Participant.class, revivalId));
    }

    public record Own(Revival revival, RevivalMember member, Participant participant) {
    }
}
