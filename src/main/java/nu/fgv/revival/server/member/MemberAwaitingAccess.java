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

/**
 * A member was added who has a Keycloak user but not {@code fgv-revival}'s {@code USER}, so a super admin has to grant
 * it (product spec, section 3). Published inside the transaction that adds the member; handled after it commits.
 *
 * @param keycloakUserId the member's user in the FGV realm
 * @param invited        whether this application created the user and Keycloak emailed them
 * @param addedBy        display name of the caller who added the member
 * @author Anders Jacobsson
 * @since 0.4
 */
public record MemberAwaitingAccess(Long revivalId,
                                   String email,
                                   @Nullable String displayName,
                                   String keycloakUserId,
                                   RevivalRole role,
                                   boolean invited,
                                   @Nullable String addedBy) {
}
