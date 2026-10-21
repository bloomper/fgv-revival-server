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

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @author Anders Jacobsson
 * @since 0.1
 */
class MeGraphqlApiIT extends AbstractIntegrationTest {

    @Test
    void meListsGlobalRoles() {
        graphQl(SUPER_ADMIN)
                .document("{ me { email displayName globalRoles } }")
                .execute()
                .path("me.email").entity(String.class).isEqualTo(SUPER_ADMIN)
                .path("me.displayName").entity(String.class).isEqualTo("Sara Admin")
                .path("me.globalRoles").entityList(String.class).containsExactly("SUPER_ADMIN");
    }

    @Test
    void meListsRevivalRolesOnlyForVisibleRevivals() {
        final long approved = createRevival(ALI_BABA, 2028, "Ali Baba 2028");
        final long proposed = createRevival(NOBEL, 2030, "Nobel 2030");

        setStatus(approved, "APPROVED");
        addMember(approved, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(proposed, DIRECTEUR, "DIRECTEUR", List.of());

        graphQl(DIRECTEUR)
                .document("{ me { revivalRoles { role capabilities revival { id workingTitle } } } }")
                .execute()
                .path("me.revivalRoles").entityList(Object.class).hasSize(1)
                .path("me.revivalRoles[0].role").entity(String.class).isEqualTo("DIRECTEUR")
                .path("me.revivalRoles[0].capabilities").entityList(String.class).containsExactly("FINANCE", "CATERING", "STAFFING", "COMMS")
                .path("me.revivalRoles[0].revival.workingTitle").entity(String.class).isEqualTo("Ali Baba 2028");
    }
}
