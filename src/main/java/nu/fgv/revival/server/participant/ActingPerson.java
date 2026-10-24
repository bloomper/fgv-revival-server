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

package nu.fgv.revival.server.participant;

import nu.fgv.revival.server.person.Caller;

import static org.springframework.util.StringUtils.hasText;

/**
 * Whom the application acts for in Spexregister ({@code X-Acting-Person}): the caller's display name, or for a
 * participant changing their own data, only the revival, so their name is not sent along with it.
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
final class ActingPerson {

    private ActingPerson() {
    }

    static String of(final Caller caller) {
        final String name = caller.person().getDisplayName();

        return "%s (via Nyuppsättning)".formatted(hasText(name) ? name.strip() : "person " + caller.person().getId());
    }

    static String participantOf(final Long revivalId) {
        return "participant via revival %d (Nyuppsättning)".formatted(revivalId);
    }
}
