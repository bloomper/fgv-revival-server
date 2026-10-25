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
package nu.fgv.revival.server.news;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * News over GraphQL: the super admin writes it, everyone with access reads what is published today.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class NewsGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String FIELDS = "id subject text visibleFrom visibleTo published version";
    private static final String CREATE = "mutation($input: NewsInput!) { newsCreate(input: $input) { " + FIELDS + " } }";
    private static final String UPDATE = "mutation($id: ID!, $version: Int!, $input: NewsInput!) { newsUpdate(id: $id, version: $version, input: $input) { "
            + FIELDS + " } }";
    private static final String DELETE = "mutation($id: ID!, $version: Int!) { newsDelete(id: $id, version: $version) }";
    private static final String LIST = "query($all: Boolean) { news(all: $all) { subject published } }";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Stockholm"));

    private static Map<String, Object> input(final String subject, final LocalDate from, final LocalDate to) {
        final Map<String, Object> input = new HashMap<>();

        input.put("subject", subject);
        input.put("text", "Text om " + subject);
        input.put("visibleFrom", from == null ? null : from.toString());
        input.put("visibleTo", to == null ? null : to.toString());
        return input;
    }

    private long create(final Map<String, Object> input) {
        return Long.parseLong(execute(SUPER_ADMIN, CREATE, Map.of("input", input)).path("newsCreate.id").entity(String.class).get());
    }

    private long newsVersion(final long id) {
        return jdbcClient.sql("SELECT version FROM news WHERE id = ?").param(id).query(Long.class).single();
    }

    @Test
    void everyoneReadsWhatIsPublishedTodayAndTheSuperAdminEverything() {
        create(input("Gammal", TODAY.minusDays(30), TODAY.minusDays(1)));
        create(input("Aktuell", TODAY.minusDays(3), null));
        create(input("Nyast", TODAY, TODAY));
        create(input("Utkast", null, null));
        create(input("Kommande", TODAY.plusDays(2), null));

        for (final String user : new String[]{STEERING_GROUP, PARTICIPANT, OBSERVER}) {
            execute(user, LIST, Map.of())
                    .path("news[*].subject").entityList(String.class).containsExactly("Nyast", "Aktuell");
        }
        execute(SUPER_ADMIN, LIST, Map.of("all", true))
                .path("news[*].subject").entityList(String.class).containsExactly("Utkast", "Kommande", "Nyast", "Aktuell", "Gammal")
                .path("news[*].published").entityList(Boolean.class).containsExactly(false, false, true, true, false);
        expectError(execute(OBSERVER, LIST, Map.of("all", true)), "FORBIDDEN");
        execute(SUPER_ADMIN, "{ news(limit: 1) { subject } }", Map.of())
                .path("news[*].subject").entityList(String.class).containsExactly("Nyast");
    }

    @Test
    void onlyTheSuperAdminWritesAndAStaleVersionIsAConflict() {
        expectError(execute(DIRECTEUR, CREATE, Map.of("input", input("Nej", TODAY, null))), "FORBIDDEN");

        final long news = create(input("Utkast", null, null));
        final long stale = newsVersion(news);

        execute(SUPER_ADMIN, UPDATE, Map.of("id", news, "version", stale, "input", input("Publicerad", TODAY, null)))
                .path("newsUpdate.subject").entity(String.class).isEqualTo("Publicerad")
                .path("newsUpdate.published").entity(Boolean.class).isEqualTo(true);
        expectError(execute(SUPER_ADMIN, UPDATE, Map.of("id", news, "version", stale, "input", input("Igen", TODAY, null))), "CONFLICT");
        expectError(execute(SUPER_ADMIN, DELETE, Map.of("id", news, "version", stale)), "CONFLICT");
        execute(SUPER_ADMIN, DELETE, Map.of("id", news, "version", newsVersion(news))).errors().verify();

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM news").query(Integer.class).single()).isZero();
    }

    @Test
    void subjectTextAndDatesAreChecked() {
        expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", input(" ", TODAY, null))), "BAD_REQUEST");
        expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", input("x".repeat(256), TODAY, null))), "BAD_REQUEST");
        expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", input("Slut före start", TODAY, TODAY.minusDays(1)))), "BAD_REQUEST");
        expectError(execute(SUPER_ADMIN, CREATE, Map.of("input", input("Bara slut", null, TODAY))), "BAD_REQUEST");
    }
}
