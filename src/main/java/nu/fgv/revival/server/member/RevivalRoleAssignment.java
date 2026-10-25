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

import java.util.Set;

/**
 * An active revival role held by the caller: the role and the capability ids held explicitly. A directeur holds every
 * capability implicitly, which {@link #holdsAll()} tells.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record RevivalRoleAssignment(RevivalRole role, Set<String> capabilities) {

    public RevivalRoleAssignment {
        capabilities = role == RevivalRole.DIRECTEUR ? Set.of() : Set.copyOf(capabilities);
    }

    public boolean holdsAll() {
        return role == RevivalRole.DIRECTEUR;
    }
}
