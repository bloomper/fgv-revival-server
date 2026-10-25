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

import nu.fgv.revival.server.revival.RevivalDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * The people who can own rows and checklist items in a revival, for the owner picker. A revival is only
 * returned to callers who can see it, so this needs no check of its own.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class OwnerGraphqlApi {

    private final OwnerResolver ownerResolver;

    public OwnerGraphqlApi(final OwnerResolver ownerResolver) {
        this.ownerResolver = ownerResolver;
    }

    /**
     * Empty for callers who do not read the revival's work, as its permissions say. With {@code includeParticipants},
     * the participants who have logged in as well, for checklist items.
     */
    @SchemaMapping(typeName = "Revival", field = "ownerCandidates")
    public List<PersonRefDto> ownerCandidates(final RevivalDto revival, @Argument final @Nullable Boolean includeParticipants) {
        if (!revival.permissions().canReadWork()) {
            return List.of();
        }
        return (Boolean.TRUE.equals(includeParticipants) ? ownerResolver.checklistOwners(revival.id()) : ownerResolver.activeMembers(revival.id()))
                .entrySet().stream()
                .map(entry -> new PersonRefDto(entry.getKey(), entry.getValue()))
                .toList();
    }
}
