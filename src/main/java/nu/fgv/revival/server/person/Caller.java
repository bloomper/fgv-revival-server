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

import nu.fgv.revival.server.member.RevivalRoleAssignment;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * The person making the current request, their global roles and their active revival roles.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record Caller(Person person, Set<GlobalRole> globalRoles, Map<Long, RevivalRoleAssignment> revivalRoles) {

    public Caller {
        globalRoles = Set.copyOf(globalRoles);
        revivalRoles = Map.copyOf(revivalRoles);
    }

    public boolean isSuperAdmin() {
        return globalRoles.contains(GlobalRole.SUPER_ADMIN);
    }

    public boolean isObserver() {
        return globalRoles.contains(GlobalRole.OBSERVER);
    }

    public @Nullable RevivalRoleAssignment roleIn(final Long revivalId) {
        return revivalRoles.get(revivalId);
    }
}
