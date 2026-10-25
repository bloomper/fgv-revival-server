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


package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.member.OwnerDto;
import org.jspecify.annotations.Nullable;

/**
 * An owner as the tools return it: a display name and whether it is a member, never an email address.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record McpOwner(@Nullable String name, boolean member, @Nullable Boolean active) {

    static @Nullable McpOwner of(final @Nullable OwnerDto owner) {
        if (owner == null) {
            return null;
        }
        return owner.person() != null
                ? new McpOwner(owner.person().displayName(), true, owner.person().active())
                : new McpOwner(owner.name(), false, null);
    }
}
