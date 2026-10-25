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

package nu.fgv.revival.server;

import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.member.RevivalRoleAssignment;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.GlobalRole;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Fictitious callers and revivals for unit tests.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class TestCallers {

    public static final long REVIVAL_ID = 1L;
    public static final long PERSON_ID = 10L;

    private TestCallers() {
    }

    private static Person person() {
        final Person person = new Person();

        person.setId(PERSON_ID);
        person.setExternalId("00000000-0000-0000-0000-000000000010");
        person.setEmail("kim@example.org");
        person.setEmailVerified(true);
        person.setDisplayName("Kim");
        return person;
    }

    public static Caller superAdmin() {
        return new Caller(person(), Set.of(GlobalRole.SUPER_ADMIN), Map.of());
    }

    public static Caller observer() {
        return new Caller(person(), Set.of(GlobalRole.OBSERVER), Map.of());
    }

    public static Caller directeur() {
        return new Caller(person(), Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.DIRECTEUR, Set.of())));
    }

    public static Caller steeringGroup() {
        return new Caller(person(), Set.of(), Map.of(REVIVAL_ID, new RevivalRoleAssignment(RevivalRole.STEERING_GROUP, Set.of("FINANCE"))));
    }

    public static Caller nobody() {
        return new Caller(person(), Set.of(), Map.of());
    }

    public static Revival revival(final RevivalStatus status) {
        final Revival revival = new Revival();

        revival.setId(REVIVAL_ID);
        revival.setSpexId(1L);
        revival.setYear(2028);
        revival.setStatus(status);
        revival.setVersion(3L);
        revival.setPremiereDate(LocalDate.of(2028, 2, 10));
        return revival;
    }
}
