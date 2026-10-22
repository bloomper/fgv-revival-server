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

import nu.fgv.revival.server.util.Locales;
import nu.fgv.revival.server.util.security.SecurityUtil;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Display names for the people recorded in audit columns and status history, never their email (FR-028). Falls back
 * to the localised "System" and "Unknown person" (data-model.md, Revision).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Component
public class PersonNames {

    private final PersonRepository personRepository;
    private final MessageSource messageSource;

    public PersonNames(final PersonRepository personRepository, final MessageSource messageSource) {
        this.personRepository = personRepository;
        this.messageSource = messageSource;
    }

    private String unknown(final Locale locale) {
        return messageSource.getMessage("person.unknown", null, "Unknown person", Locales.supported(locale));
    }

    /**
     * The name for a Keycloak subject stored in {@code created_by}, {@code last_modified_by} or {@code revinfo}.
     */
    @Transactional(readOnly = true)
    public @Nullable String bySubject(final @Nullable String subject, final Locale locale) {
        if (subject == null) {
            return null;
        }
        if (SecurityUtil.SYSTEM.equals(subject)) {
            return messageSource.getMessage("person.system", null, "System", Locales.supported(locale));
        }
        return personRepository.findByExternalId(subject)
                .map(Person::getDisplayName)
                .filter(Objects::nonNull)
                .orElseGet(() -> unknown(locale));
    }

    /**
     * Names for many Keycloak subjects at once, e.g. the audit columns of a list of rows.
     */
    @Transactional(readOnly = true)
    public Map<String, String> bySubjects(final Collection<String> subjects, final Locale locale) {
        final Map<String, String> people = personRepository.findByExternalIdIn(subjects).stream()
                .filter(person -> person.getDisplayName() != null)
                .collect(Collectors.toMap(Person::getExternalId, Person::getDisplayName));

        return subjects.stream().distinct().collect(Collectors.toMap(
                Function.identity(),
                subject -> SecurityUtil.SYSTEM.equals(subject)
                        ? messageSource.getMessage("person.system", null, "System", Locales.supported(locale))
                        : people.getOrDefault(subject, unknown(locale))));
    }

    /**
     * Names by person id, e.g. for {@code changed_by}.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> byIds(final Collection<Long> ids, final Locale locale) {
        final Map<Long, Person> people = personRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));

        return ids.stream().distinct().collect(Collectors.toMap(
                Function.identity(),
                id -> {
                    final Person person = people.get(id);

                    return person != null && person.getDisplayName() != null ? person.getDisplayName() : unknown(locale);
                }));
    }
}
