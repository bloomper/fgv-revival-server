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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User story 5: {@code list_revivals} through MCP, with the MCP client's own tokens (FR-023–FR-025).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
class RevivalMcpToolsIntegrationTest extends AbstractIntegrationTest {

    private long organising;
    private long approved;

    private void createRevivals() {
        organising = createRevival(ALI_BABA, 2028, "Ali Baba 2028");
        approved = createRevival(NOBEL, 2029, "Nobel 2029");
        setStatus(organising, "ORGANISING");
        setStatus(approved, "APPROVED");
        addMember(organising, DIRECTEUR, "DIRECTEUR", List.of());
        graphQl(SUPER_ADMIN)
                .document("mutation($input: RevivalUpdateInput!) { revivalUpdate(input: $input) { id } }")
                .variable("input", Map.of("id", organising, "version", version(organising), "premiereDate", "2028-02-10"))
                .execute()
                .path("revivalUpdate.id").hasValue();
    }

    @Test
    void listRevivalsReturnsTheContractFieldsAndNothingAboutPeople() {
        createRevivals();

        final JsonNode items = listRevivals(mcpToken(DIRECTEUR), Map.of());

        assertThat(items).hasSize(1);

        final JsonNode item = items.get(0);

        assertThat(item.get("id").asLong()).isEqualTo(organising);
        assertThat(item.get("spexTitle").asString()).isEqualTo("Ali Baba");
        assertThat(item.get("spexAvailable").asBoolean()).isTrue();
        assertThat(item.get("year").asInt()).isEqualTo(2028);
        assertThat(item.get("workingTitle").asString()).isEqualTo("Ali Baba 2028");
        assertThat(item.get("status").asString()).isEqualTo("ORGANISING");
        assertThat(item.get("premiereDate").asString()).isEqualTo("2028-02-10");
        assertThat(item.get("role").asString()).isEqualTo("DIRECTEUR");
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("id", "spexTitle", "spexAvailable", "year", "workingTitle", "status", "premiereDate", "role");
        assertThat(item.toString()).doesNotContain("@");
    }

    @Test
    void superAdminsAndObserversGetEveryRevivalAndTheStatusFilterWorks() {
        createRevivals();

        assertThat(listRevivals(mcpToken(SUPER_ADMIN), Map.of())).hasSize(2);
        assertThat(listRevivals(mcpToken(OBSERVER), Map.of())).hasSize(2);

        final JsonNode filtered = listRevivals(mcpToken(OBSERVER), Map.of("status", List.of("APPROVED")));

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("id").asLong()).isEqualTo(approved);
        assertThat(filtered.get(0).get("role").isNull()).isTrue();
    }

    @Test
    void spexIsUnavailableWhileSpexregisterIsDown() {
        createRevivals();
        spexregisterDown(false);

        final JsonNode items = listRevivals(mcpToken(SUPER_ADMIN), Map.of());

        assertThat(items).allSatisfy(item -> {
            assertThat(item.get("spexAvailable").asBoolean()).isFalse();
            assertThat(item.get("spexTitle").isNull()).isTrue();
        });
    }

    @Test
    void tokensFromOtherClientsAreRefused() {
        for (final String token : List.of(
                freshToken(SUPER_ADMIN, CLIENT_ID),
                freshToken(SUPER_ADMIN, SPEXREGISTER_MCP_CLIENT_ID),
                freshToken(SUPER_ADMIN, UNBOUND_MCP_CLIENT_ID))) {
            restTestClient()
                    .post()
                    .uri(MCP_PATH)
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Test
    void noTokenIsRefusedWithResourceMetadata() {
        restTestClient()
                .post()
                .uri(MCP_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, value -> assertThat(value).contains("resource_metadata"));
    }

    @Test
    void aUserWithoutAccessIsRefused() {
        final JsonNode response = mcpRpc(freshToken(SPEXREGISTER_ONLY, MCP_CLIENT_ID), "tools/call", Map.of("name", "list_revivals", "arguments", Map.of()));
        final JsonNode result = response.path("result");

        assertThat(response.has("error") || result.path("isError").asBoolean(false)).as("refused: %s", response).isTrue();
        assertThat(response.toString()).doesNotContain("workingTitle");
    }

    @Test
    void theProtectedResourceMetadataIsServed() {
        restTestClient()
                .get()
                .uri("/.well-known/oauth-protected-resource/api/mcp")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> assertThat(body).contains("/api/mcp").contains("authorization_servers"));
    }

    @Test
    void theToolIsListedAsReadOnly() {
        final JsonNode tools = mcpRpc(mcpToken(PLAIN_USER), "tools/list", Map.of()).path("result").path("tools");
        JsonNode listRevivals = null;

        for (final JsonNode tool : tools) {
            if ("list_revivals".equals(tool.get("name").asString())) {
                listRevivals = tool;
            }
        }
        assertThat(listRevivals).isNotNull();
        assertThat(listRevivals.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
        assertThat(listRevivals.path("annotations").path("destructiveHint").asBoolean(true)).isFalse();
    }
}
