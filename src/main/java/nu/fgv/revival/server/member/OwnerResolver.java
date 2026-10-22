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

import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Owners of rows and checklist items (FR-009, research R4): validates an {@link OwnerInputDto}, tells whether an owner
 * still counts for FR-019, and matches a display name to a member for MCP and the import.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Component
public class OwnerResolver {

    private static final Set<RevivalRole> OWNER_ROLES = EnumSet.of(RevivalRole.DIRECTEUR, RevivalRole.STEERING_GROUP);
    private static final String ME = "me";

    private final RevivalMemberRepository memberRepository;
    private final PersonRepository personRepository;

    public OwnerResolver(final RevivalMemberRepository memberRepository, final PersonRepository personRepository) {
        this.memberRepository = memberRepository;
        this.personRepository = personRepository;
    }

    /**
     * The outcome of matching a name to the revival's members.
     */
    public sealed interface Resolution {

        record Member(Long personId, @Nullable String displayName) implements Resolution {
        }

        record Ambiguous(List<String> candidates) implements Resolution {
        }

        record FreeText(String name) implements Resolution {
        }
    }

    /**
     * The active directeurs and steering group members who can own things, by person id, with their display names.
     */
    @Transactional(readOnly = true)
    public Map<Long, @Nullable String> activeMembers(final Long revivalId) {
        final List<Long> ids = memberRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revivalId).stream()
                .filter(member -> OWNER_ROLES.contains(member.getRole()) && member.getPersonId() != null)
                .map(RevivalMember::getPersonId)
                .filter(Objects::nonNull)
                .toList();
        final Map<Long, Person> people = personRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        final Map<Long, @Nullable String> members = new LinkedHashMap<>();

        ids.forEach(id -> members.put(id, people.containsKey(id) ? people.get(id).getDisplayName() : null));
        return members;
    }

    @Transactional(readOnly = true)
    public Resolution resolve(final Long revivalId, final String text, final Caller caller) {
        final String trimmed = text.strip();
        final Map<Long, @Nullable String> members = activeMembers(revivalId);

        if (ME.equalsIgnoreCase(trimmed)) {
            final Long me = caller.person().getId();

            if (!members.containsKey(me)) {
                throw new BadRequestException("error.owner.notMember");
            }
            return new Resolution.Member(me, members.get(me));
        }

        final String wanted = normalize(trimmed);
        List<Map.Entry<Long, @Nullable String>> matches = members.entrySet().stream()
                .filter(entry -> entry.getValue() != null && normalize(entry.getValue()).equals(wanted))
                .toList();

        if (matches.isEmpty()) {
            matches = members.entrySet().stream()
                    .filter(entry -> entry.getValue() != null && firstName(normalize(entry.getValue())).equals(wanted))
                    .toList();
        }
        if (matches.size() == 1) {
            return new Resolution.Member(matches.getFirst().getKey(), matches.getFirst().getValue());
        }
        if (matches.size() > 1) {
            return new Resolution.Ambiguous(matches.stream()
                    .map(entry -> Objects.requireNonNull(entry.getValue()))
                    .sorted(Comparator.naturalOrder())
                    .toList());
        }
        return new Resolution.FreeText(trimmed);
    }

    /**
     * As {@link #validate}, but an input naming the current owner keeps it, even someone no longer in the steering group:
     * the row keeps them until a member changes the owner (M3 edge cases).
     */
    @Transactional(readOnly = true)
    public Owner change(final Long revivalId, final Owner current, final OwnerInputDto input) {
        final boolean same = input.personId() != null && input.name() == null
                ? input.personId().equals(current.getPersonId())
                : input.personId() == null && input.name() != null && input.name().strip().equals(current.getName());

        return same ? current : validate(revivalId, input);
    }

    /**
     * Turns the input into an owner: a member who is an active directeur or steering group member, a trimmed name of 1–100
     * characters, or none. Both set is refused.
     */
    @Transactional(readOnly = true)
    public Owner validate(final Long revivalId, final @Nullable OwnerInputDto input) {
        if (input == null || (input.personId() == null && input.name() == null)) {
            return Owner.none();
        }
        if (input.personId() != null && input.name() != null) {
            throw new BadRequestException("error.owner.both");
        }
        if (input.personId() != null) {
            if (!activeMembers(revivalId).containsKey(input.personId())) {
                throw new BadRequestException("error.owner.notMember");
            }
            return Owner.member(input.personId());
        }
        return Owner.named(validName(Objects.requireNonNull(input.name())));
    }

    /**
     * A trimmed free-text owner name of 1–100 characters.
     */
    public static String validName(final String name) {
        final String trimmed = name.strip();

        if (trimmed.isEmpty() || trimmed.length() > Owner.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.owner.name", Owner.NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * Owned for FR-019: a free-text name, or a person who is still an active member.
     */
    @Transactional(readOnly = true)
    public boolean isOwned(final Long revivalId, final Owner owner) {
        if (owner.getName() != null) {
            return true;
        }
        return owner.getPersonId() != null && activeMembers(revivalId).containsKey(owner.getPersonId());
    }

    static String normalize(final String text) {
        return Normalizer.normalize(text.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String firstName(final String normalized) {
        final int space = normalized.indexOf(' ');

        return space < 0 ? normalized : normalized.substring(0, space);
    }
}
