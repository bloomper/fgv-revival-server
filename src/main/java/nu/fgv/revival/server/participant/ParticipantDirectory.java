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

import nu.fgv.revival.server.participant.ParticipantDtos.ParticipantDto;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.spex.SpexregisterClient;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Participants by name, and their email addresses at send time, for staffing and mailings. Names come from
 * Spexregister or the pending person and are never stored elsewhere; addresses are read when a mailing is sent and
 * never stored or logged.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ParticipantDirectory {

    private final ParticipantViews views;
    private final PendingPersonRepository pendingRepository;

    ParticipantDirectory(final ParticipantViews views, final PendingPersonRepository pendingRepository) {
        this.views = views;
        this.pendingRepository = pendingRepository;
    }

    /**
     * Whom the application acts for in Spexregister when the caller reads participants.
     */
    public static String actingPerson(final Caller caller) {
        return ActingPerson.of(caller);
    }

    /**
     * Names, kinds and positions by participant id. A spexare is shown as an unknown person while Spexregister cannot
     * be read, rather than failing the whole list.
     */
    public Map<Long, Named> named(final Collection<Participant> participants, final String actingPerson, final Locale locale) {
        if (participants.isEmpty()) {
            return Map.of();
        }

        final List<ParticipantDto> dtos = views.toDtos(participants.stream().distinct().toList(),
                new ParticipantViews.Options(false, false, false, actingPerson, locale));

        return dtos.stream().collect(Collectors.toMap(ParticipantDto::id,
                dto -> new Named(dto.id(), (dto.firstName() + " " + dto.lastName()).strip(), dto.kind(), dto.position()),
                (first, _) -> first));
    }

    /**
     * Email addresses by participant id, read now: a spexare's {@code HOME} address from Spexregister in one call, a
     * pending person's own. Participants without an address are left out. Fails with SPEXREGISTER_UNAVAILABLE when
     * any participant is a spexare and Spexregister cannot be read.
     */
    public Map<Long, String> emails(final Collection<Participant> participants, final String actingPerson) {
        if (participants.isEmpty()) {
            return Map.of();
        }

        final Map<Long, SpexregisterClient.Spexare> people = views.people(participants, actingPerson, true);
        final Map<Long, PendingPerson> pending = pendingRepository.findByParticipantIdIn(participants.stream().map(Participant::getId).toList())
                .stream().collect(Collectors.toMap(PendingPerson::getParticipantId, Function.identity()));
        final Map<Long, String> emails = new HashMap<>();

        for (final Participant participant : participants) {
            final SpexregisterClient.Spexare spexare = participant.getSpexareId() == null ? null : people.get(participant.getSpexareId());
            final String email = ParticipantViews.contact(spexare, pending.get(participant.getId()))[0];

            if (email != null) {
                emails.put(participant.getId(), email);
            }
        }
        return emails;
    }

    /**
     * A participant by name and position only.
     */
    public record Named(Long id, String displayName, ParticipantKind kind,
                        SpexareDirectory.@Nullable Position position) {
    }
}
