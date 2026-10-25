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

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guide's MCP tools.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class GuideMcpToolsIntegrationTest extends AbstractIntegrationTest {

    private long version;
    private long revival;

    private static @org.jspecify.annotations.Nullable String text(final JsonNode node, final String field) {
        return node.get(field).isNull() ? null : node.get(field).asString();
    }

    private static boolean refused(final JsonNode response) {
        return response.has("error") || response.path("result").path("isError").asBoolean(false);
    }

    @BeforeEach
    void publishedGuideAndARevival() {
        version = publishedGuide();
        revival = createRevival(ALI_BABA, 2028);
        setStatus(revival, "APPROVED");
        login(DIRECTEUR);
        login(STEERING_GROUP);
        addMember(revival, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(revival, STEERING_GROUP, "STEERING_GROUP", List.of());
        setStatus(revival, "ORGANISING");
    }

    private JsonNode call(final String user, final String tool, final Map<String, Object> arguments) {
        return mcpRpc(mcpToken(user), "tools/call", Map.of("name", tool, "arguments", arguments));
    }

    private JsonNode result(final JsonNode response) {
        final JsonNode result = response.get("result");

        if (result == null || result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool error: " + response);
        }
        return objectMapper.readTree(result.get("content").get(0).get("text").asString());
    }

    @Test
    void searchGuideMatchesTheWebSearch() {
        for (final Map<String, Object> scope : List.<Map<String, Object>>of(Map.of(), Map.of("revivalId", revival))) {
            final Map<String, Object> arguments = new java.util.HashMap<>(scope);

            arguments.put("query", "ti");

            final JsonNode found = result(call(STEERING_GROUP, "search_guide", arguments));
            final List<String> mcp = new ArrayList<>();
            final List<String> web = graphQl(STEERING_GROUP)
                    .document("query($query: String!, $versionId: ID) { guideSearch(query: $query, versionId: $versionId, first: 20) { kind chapterKey itemKey sectionKey } }")
                    .variable("query", "ti").variable("versionId", version)
                    .execute()
                    .path("guideSearch[*]").entityList(Map.class).get().stream()
                    .map(hit -> hit.get("kind") + "/" + hit.get("chapterKey") + "/" + hit.get("itemKey") + "/" + hit.get("sectionKey"))
                    .toList();

            assertThat(found.at("/version/id").asLong()).isEqualTo(version);
            found.get("items").forEach(hit -> mcp.add(hit.get("kind").asString() + "/" + text(hit, "chapterKey") + "/" + text(hit, "itemKey") + "/" + text(hit, "sectionKey")));
            assertThat(mcp).isNotEmpty().isEqualTo(web);
        }
    }

    @Test
    void getGuideSectionReturnsAChapterOrOneSectionInBothLanguages() {
        final JsonNode chapter = result(call(DIRECTEUR, "get_guide_section", Map.of("chapter", "2", "revivalId", revival)));

        assertThat(chapter.get("chapterKey").asString()).isEqualTo("B");
        assertThat(chapter.at("/chapterTitle/sv").asString()).isEqualTo("Boka lokaler och lägg fast er tidslinje");
        assertThat(chapter.get("sections")).hasSize(2);
        assertThat(chapter.at("/sections/0/items/0/text").has("en")).isTrue();

        final String key = chapter.at("/sections/1/key").asString();
        final JsonNode section = result(call(DIRECTEUR, "get_guide_section", Map.of("chapter", "B", "sectionKey", key)));

        assertThat(section.get("sections")).hasSize(1);
        assertThat(section.at("/sections/0/title/sv").asString()).isEqualTo("Tidslinjen");
        assertThat(section.get("items")).isEmpty();

        assertThat(refused(call(DIRECTEUR, "get_guide_section", Map.of("chapter", "K")))).isTrue();
        assertThat(refused(call(DIRECTEUR, "get_guide_section", Map.of("chapter", "B", "sectionKey", "nope")))).isTrue();
    }

    @Test
    void theToolsNeverReadTheDraft() {
        graphQl(SUPER_ADMIN).document("mutation { guideDraftStart { draft { id } } }").execute();

        assertThat(result(call(SUPER_ADMIN, "search_guide", Map.of("query", "sprit"))).at("/version/id").asLong()).isEqualTo(version);
        assertThat(result(call(SUPER_ADMIN, "get_guide_section", Map.of("chapter", "A"))).at("/version/id").asLong()).isEqualTo(version);
    }

    @Test
    void suggestGuideChangeRecordsASuggestionAndChangesNothing() {
        final String itemKey = jdbcClient.sql("SELECT `key` FROM guide_item WHERE version_id = ? AND type = 'TIP' ORDER BY id LIMIT 1")
                .param(version).query(String.class).single();
        final String before = jdbcClient.sql("SELECT text_sv FROM guide_item WHERE version_id = ? AND `key` = ?").param(version).param(itemKey).query(String.class).single();
        final JsonNode created = result(call(STEERING_GROUP, "suggest_guide_change", Map.of(
                "revivalId", revival, "kind", "CHANGE", "itemKey", itemKey, "textSv", "RunAn behöver en tekniker på söndagen.", "reason", "Saknades förra gången")));

        assertThat(created.get("status").asString()).isEqualTo("OPEN");
        assertThat(created.at("/version/id").asLong()).isEqualTo(version);
        graphQl(SUPER_ADMIN).document("{ guideSuggestions { id status proposedText { sv } } }").execute()
                .path("guideSuggestions[0].proposedText.sv").entity(String.class).isEqualTo("RunAn behöver en tekniker på söndagen.");
        assertThat(jdbcClient.sql("SELECT text_sv FROM guide_item WHERE version_id = ? AND `key` = ?").param(version).param(itemKey).query(String.class).single())
                .isEqualTo(before);
        assertThat(jdbcClient.sql("SELECT r.source FROM guide_suggestion_audit a JOIN revinfo r ON r.id = a.rev").query(String.class).list())
                .containsExactly("MCP");

        assertThat(refused(call(OBSERVER, "suggest_guide_change", Map.of("revivalId", revival, "kind", "REMOVE", "itemKey", itemKey, "reason", "x")))).isTrue();
        assertThat(call(STEERING_GROUP, "suggest_guide_change", Map.of("revivalId", revival, "kind", "ADD", "chapter", "K", "type", "TIP",
                "textSv", "x")).toString()).contains("BAD_REQUEST").contains("There is no chapter");
    }

    @Test
    void outputNamesNobody() {
        final String itemKey = jdbcClient.sql("SELECT `key` FROM guide_item WHERE version_id = ? ORDER BY id LIMIT 1").param(version).query(String.class).single();

        for (final JsonNode response : List.of(
                call(DIRECTEUR, "search_guide", Map.of("query", "en")),
                call(DIRECTEUR, "get_guide_section", Map.of("chapter", "A")),
                call(DIRECTEUR, "suggest_guide_change", Map.of("revivalId", revival, "kind", "REMOVE", "itemKey", itemKey, "reason", "Onödig")))) {
            assertThat(response.toString()).doesNotContain("@").doesNotContain("Dana").doesNotContain("Sara");
        }
    }

    @Test
    void othersAreRefused() {
        assertThat(refused(call(PLAIN_USER, "search_guide", Map.of("query", "sprit")))).isTrue();
        assertThat(refused(call(PLAIN_USER, "get_guide_section", Map.of("chapter", "A")))).isTrue();

        restTestClient().post().uri(MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, freshToken(DIRECTEUR, CLIENT_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", "search_guide", "arguments", Map.of("query", "sprit"))))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
