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

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Who owns a decision log row or a checklist item: a directeur or steering group member of the revival, or a free-text
 * name for someone outside the steering group. At most one of the two is set; neither means no
 * owner.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Embeddable
public class Owner {

    public static final int NAME_MAX_LENGTH = 100;

    @Column(name = "owner_person_id")
    private @Nullable Long personId;

    @Column(name = "owner_name", length = NAME_MAX_LENGTH)
    private @Nullable String name;

    protected Owner() {
    }

    private Owner(final @Nullable Long personId, final @Nullable String name) {
        this.personId = personId;
        this.name = name;
    }

    public static Owner member(final Long personId) {
        return new Owner(personId, null);
    }

    public static Owner named(final String name) {
        return new Owner(null, name);
    }

    public static Owner none() {
        return new Owner(null, null);
    }

    public @Nullable Long getPersonId() {
        return personId;
    }

    public @Nullable String getName() {
        return name;
    }

    public boolean isSet() {
        return personId != null || name != null;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof Owner owner && Objects.equals(personId, owner.personId) && Objects.equals(name, owner.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(personId, name);
    }
}
