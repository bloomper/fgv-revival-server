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

import nu.fgv.revival.server.person.PersonNames;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owners as shown: members by display name with whether they are still active, free-text names as written. Builds many
 * at once, so a list of rows costs a few queries.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class OwnerViews {

    private final OwnerResolver ownerResolver;
    private final PersonNames personNames;

    public OwnerViews(final OwnerResolver ownerResolver, final PersonNames personNames) {
        this.ownerResolver = ownerResolver;
        this.personNames = personNames;
    }

    @Transactional(readOnly = true)
    public Map<Ref, @Nullable OwnerDto> toDtos(final Collection<Ref> refs, final Locale locale) {
        final Map<Long, Map<Long, @Nullable String>> active = new HashMap<>();
        final Set<Long> personIds = refs.stream()
                .map(ref -> ref.owner().getPersonId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        final Map<Long, String> names = personIds.isEmpty() ? Map.of() : personNames.byIds(personIds, locale);
        final Map<Ref, @Nullable OwnerDto> result = new HashMap<>();

        for (final Ref ref : refs) {
            final Owner owner = ref.owner();

            if (owner.getPersonId() != null) {
                final Map<Long, @Nullable String> members = active.computeIfAbsent(ref.revivalId(), ownerResolver::activeMembers);

                result.put(ref, new OwnerDto(new OwnerDto.OwnerPersonDto(owner.getPersonId(), names.get(owner.getPersonId()),
                        members.containsKey(owner.getPersonId())), null));
            } else if (owner.getName() != null) {
                result.put(ref, new OwnerDto(null, owner.getName()));
            } else {
                result.put(ref, null);
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public @Nullable OwnerDto toDto(final Long revivalId, final Owner owner, final Locale locale) {
        final Ref ref = new Ref(revivalId, owner);

        return toDtos(List.of(ref), locale).get(ref);
    }

    /**
     * An owner within a revival.
     */
    public record Ref(Long revivalId, Owner owner) {
    }
}
