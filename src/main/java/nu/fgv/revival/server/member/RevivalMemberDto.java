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

import java.time.Instant;
import java.util.Set;

/**
 * A member as GraphQL's {@code RevivalMember}. {@code email} is filled only for callers who may see it (FR-019c).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
public record RevivalMemberDto(Long id,
                               RevivalRole role,
                               Set<String> capabilities,
                               MemberState state,
                               @Nullable String displayName,
                               @Nullable String email,
                               @Nullable Instant invitedAt,
                               Long version) {
}
