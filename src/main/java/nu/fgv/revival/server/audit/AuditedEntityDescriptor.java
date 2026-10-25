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
package nu.fgv.revival.server.audit;

import nu.fgv.revival.server.person.Caller;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a record's history needs: its entity, who may read it, and how its fields are shown.
 *
 * @param access     refuses a caller who may not read the record now; given its latest snapshot
 * @param hidden     fields left out: internal ones, and those another field already shows
 * @param formatters fields shown otherwise than their Java type says, e.g. an id as the name it refers to
 * @author Anders Jacobsson
 * @since 1.0
 */
public record AuditedEntityDescriptor(
        AuditedType type,
        Class<?> entityClass,
        Access access,
        Set<String> hidden,
        Map<String, Formatter> formatters
) {

    @FunctionalInterface
    public interface Access {

        void require(Caller caller, Object snapshot);
    }

    /**
     * Writes a value that is not null.
     */
    public record Formatter(FieldKind kind, Format format) {
    }

    @FunctionalInterface
    public interface Format {

        @Nullable String apply(Object value, Locale locale);
    }
}
