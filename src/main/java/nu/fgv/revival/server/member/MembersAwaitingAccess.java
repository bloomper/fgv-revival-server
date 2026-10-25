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

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Several revival roles were given at once, e.g. in one round of participant invitations, to Keycloak users without
 * {@code fgv-revival}'s {@code USER}: the super admins are told once about all of them. Published
 * inside the transaction that gives the roles; handled after it commits.
 *
 * @param addedBy display name of the caller who gave the roles
 * @author Anders Jacobsson
 * @since 1.0
 */
public record MembersAwaitingAccess(Long revivalId, List<Waiting> members, @Nullable String addedBy) {

    public MembersAwaitingAccess {
        members = List.copyOf(members);
    }

    /**
     * One role waiting for its user's access.
     *
     * @param keycloakUserId the user in the FGV realm
     * @param invited        whether this application created the user and Keycloak emailed them
     */
    public record Waiting(String email, @Nullable String displayName, String keycloakUserId, RevivalRole role,
                          boolean invited) {

        public MemberAwaitingAccess event(final Long revivalId, final @Nullable String addedBy) {
            return new MemberAwaitingAccess(revivalId, email, displayName, keycloakUserId, role, invited, addedBy);
        }
    }
}
