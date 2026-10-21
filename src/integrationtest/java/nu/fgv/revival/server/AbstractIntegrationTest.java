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

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JacksonJsonParser;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * MySQL and Keycloak in Testcontainers, with the committed copy of {@code fgv-keycloak}'s realm plus fictitious users,
 * and WireMock standing in for Spexregister's GraphQL API (research R7, R14). Every test starts from empty tables.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integrationtest")
public abstract class AbstractIntegrationTest {

    protected static final String DOMAIN = "@revival.test";
    protected static final String SUPER_ADMIN = "superadmin" + DOMAIN;
    protected static final String OBSERVER = "observer" + DOMAIN;
    protected static final String DIRECTEUR = "directeur" + DOMAIN;
    protected static final String DIRECTEUR_2 = "directeur2" + DOMAIN;
    protected static final String STEERING_GROUP = "styrgrupp" + DOMAIN;
    protected static final String PLAIN_USER = "user" + DOMAIN;
    protected static final String SPEXREGISTER_ONLY = "spexare" + DOMAIN;
    protected static final String PASSWORD = "s3cr3t-s3cr3t";
    protected static final String CLIENT_ID = "fgv-revival";
    protected static final String MCP_CLIENT_ID = "fgv-revival-mcp";
    protected static final String UNBOUND_MCP_CLIENT_ID = "fgv-revival-mcp-unbound";
    protected static final String SPEXREGISTER_MCP_CLIENT_ID = "spexregister-mcp";
    protected static final long ALI_BABA = 1L;
    protected static final long NOBEL = 2L;
    protected static final long SNORRE = 3L;
    protected static final long SPEX_REVIVAL = 99L;
    protected static final String MCP_PATH = "/api/mcp";

    private static final String REALM = "fgv";
    // The secret of fgv-revival in the committed realm copy, not a real secret
    private static final String CLIENT_SECRET = "qxIP941iHaRoiDkyyTFkwmelDstNixz3";
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(2);
    private static final Map<String, CachedToken> TOKENS = new ConcurrentHashMap<>();
    private static final JacksonJsonParser JSON = new JacksonJsonParser();

    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("mysqld", "--innodb-flush-log-at-trx-commit=0", "--innodb-doublewrite=0", "--skip-log-bin", "--performance-schema=OFF");
    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.8").withRealmImportFile("/realm/fgv.json");
    protected static final WireMockServer SPEXREGISTER = new WireMockServer(options().dynamicPort());

    static {
        Startables.deepStart(MYSQL, KEYCLOAK).join();
        SPEXREGISTER.start();
        prepareKeycloak();
    }

    private final AtomicInteger requestId = new AtomicInteger();

    @LocalServerPort
    protected int localPort;
    @Autowired
    protected JdbcClient jdbcClient;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    private CacheManager cacheManager;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);
        registry.add("fgv-revival.keycloak.url", KEYCLOAK::getAuthServerUrl);
        registry.add("fgv-revival.spexregister.graphql-url", () -> SPEXREGISTER.baseUrl() + "/api/graphql");
    }

    private static void prepareKeycloak() {
        try (final Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final ClientRepresentation web = realm.clients().findByClientId(CLIENT_ID).getFirst();
            final ClientRepresentation mcp = realm.clients().findByClientId(MCP_CLIENT_ID).getFirst();
            final ClientRepresentation spexregisterMcp = realm.clients().findByClientId(SPEXREGISTER_MCP_CLIENT_ID).getFirst();

            web.setDirectAccessGrantsEnabled(true);
            realm.clients().get(web.getId()).update(web);
            mcp.setDirectAccessGrantsEnabled(true);
            mcp.setConsentRequired(false);
            realm.clients().get(mcp.getId()).update(mcp);
            spexregisterMcp.setDirectAccessGrantsEnabled(true);
            spexregisterMcp.setConsentRequired(false);
            realm.clients().get(spexregisterMcp.getId()).update(spexregisterMcp);

            final ClientRepresentation unbound = new ClientRepresentation();

            unbound.setClientId(UNBOUND_MCP_CLIENT_ID);
            unbound.setPublicClient(true);
            unbound.setDirectAccessGrantsEnabled(true);
            realm.clients().create(unbound).close();

            createUser(realm, SUPER_ADMIN, "Sara", "Admin", CLIENT_ID, List.of("USER", "SUPER_ADMIN"));
            createUser(realm, OBSERVER, "Olle", "Observatör", CLIENT_ID, List.of("USER", "OBSERVER"));
            createUser(realm, DIRECTEUR, "Dana", "Directeur", CLIENT_ID, List.of("USER"));
            createUser(realm, DIRECTEUR_2, "David", "Directeur", CLIENT_ID, List.of("USER"));
            createUser(realm, STEERING_GROUP, "Stina", "Styrgrupp", CLIENT_ID, List.of("USER"));
            createUser(realm, PLAIN_USER, "Ulla", "Användare", CLIENT_ID, List.of("USER"));
            createUser(realm, SPEXREGISTER_ONLY, "Sven", "Spexare", "spexregister", List.of("USER"));
        }
    }

    protected static String createUser(final RealmResource realm, final String email, final String firstName, final String lastName,
                                       final String clientId, final List<String> roles) {
        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setUsername(email);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (final Response response = realm.users().create(user)) {
            final String path = response.getLocation().getPath();
            final String userId = path.substring(path.lastIndexOf('/') + 1);

            grantClientRoles(realm, userId, clientId, roles);
            return userId;
        }
    }

    protected static void grantClientRoles(final RealmResource realm, final String userId, final String clientId, final List<String> roles) {
        final ClientResource client = realm.clients().get(realm.clients().findByClientId(clientId).getFirst().getId());
        final List<RoleRepresentation> representations = roles.stream().map(role -> client.roles().get(role).toRepresentation()).toList();

        realm.users().get(userId).roles().clientLevel(client.toRepresentation().getId()).add(representations);
    }

    protected static Keycloak keycloakAdmin() {
        return KEYCLOAK.getKeycloakAdminClient();
    }

    protected static RealmResource realm(final Keycloak admin) {
        return admin.realm(REALM);
    }

    @BeforeEach
    void resetState() {
        jdbcClient.sql("DELETE FROM revival_member_capability").update();
        jdbcClient.sql("DELETE FROM revival_member").update();
        jdbcClient.sql("DELETE FROM revival_status_change").update();
        jdbcClient.sql("DELETE FROM revival_show_date").update();
        jdbcClient.sql("DELETE FROM revival_venue").update();
        jdbcClient.sql("DELETE FROM revival").update();
        for (final String table : List.of("revival_member_capability_audit", "revival_member_audit", "revival_status_change_audit",
                "revival_show_date_audit", "revival_venue_audit", "revival_audit", "revchanges", "revinfo", "person")) {
            jdbcClient.sql("DELETE FROM " + table).update();
        }
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
        spexregisterUp();
    }

    /**
     * Spexregister answering with the canned original spex, the revival spex {@value #SPEX_REVIVAL}, and not found for
     * anything else.
     */
    protected static void spexregisterUp() {
        SPEXREGISTER.resetAll();
        SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("OriginalSpex"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(resource("originalSpex.json"))));
        SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("SpexById"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(resource("spexNotFound.json"))));
        SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("SpexById"))
                .withRequestBody(matchingJsonPath("$.variables.id", equalTo(String.valueOf(SPEX_REVIVAL))))
                .atPriority(1)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(resource("spexRevival.json"))));
    }

    /**
     * Spexregister failing, as when it is down or slow.
     */
    protected void spexregisterDown(final boolean timeout) {
        SPEXREGISTER.resetAll();
        SPEXREGISTER.stubFor(post(urlEqualTo("/api/graphql"))
                .willReturn(timeout ? aResponse().withFixedDelay(5_000).withStatus(200) : aResponse().withStatus(503)));
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
    }

    protected static int spexregisterCalls() {
        return SPEXREGISTER.countRequestsMatching(WireMock.postRequestedFor(urlEqualTo("/api/graphql")).build()).getCount();
    }

    private static String resource(final String name) {
        try (var stream = AbstractIntegrationTest.class.getResourceAsStream("/spexregister/" + name)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Tokens -----------------------------------------------------------------------------------------------------

    protected static String token(final String user) {
        return token(user, CLIENT_ID);
    }

    protected static String mcpToken(final String user) {
        return token(user, MCP_CLIENT_ID);
    }

    protected static String token(final String user, final String clientId) {
        final String key = clientId + ":" + user;
        final CachedToken cached = TOKENS.get(key);

        if (cached != null && cached.expires().isAfter(Instant.now())) {
            return cached.value();
        }

        final String value = freshToken(user, clientId);

        TOKENS.put(key, new CachedToken(value, Instant.now().plus(TOKEN_LIFETIME)));
        return value;
    }

    /**
     * A token by password grant, bypassing the cache.
     */
    protected static String freshToken(final String user, final String clientId) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

        form.add("grant_type", "password");
        form.add("client_id", clientId);
        if (CLIENT_ID.equals(clientId)) {
            form.add("client_secret", CLIENT_SECRET);
        }
        form.add("username", user);
        form.add("password", PASSWORD);
        form.add("scope", "openid");

        final String response = WebClient.create()
                .post()
                .uri(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return "Bearer " + JSON.parseMap(response).get("access_token");
    }

    protected static void forgetTokens() {
        TOKENS.clear();
    }

    // --- GraphQL ----------------------------------------------------------------------------------------------------

    protected HttpGraphQlTester graphQl(final @Nullable String user) {
        return graphQlWithToken(user == null ? null : token(user));
    }

    protected HttpGraphQlTester graphQlWithToken(final @Nullable String token) {
        return HttpGraphQlTester.create(WebTestClient.bindToServer()
                .baseUrl("http://localhost:%d/api/graphql".formatted(localPort))
                .responseTimeout(Duration.ofSeconds(30))
                .defaultHeaders(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                    headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
                })
                .build());
    }

    /**
     * Asserts that the response has an error with the given {@code extensions.classification}, e.g. {@code FORBIDDEN}.
     */
    protected static void expectError(final GraphQlTester.Response response, final String code) {
        response.errors().satisfy(errors -> assertThat(errors)
                .as("errors")
                .anySatisfy(error -> assertThat(String.valueOf(error.getErrorType())).isEqualTo(code)));
    }

    protected GraphQlTester.Response execute(final String user, final String document, final Map<String, Object> variables) {
        GraphQlTester.Request<?> request = graphQl(user).document(document);

        for (final Map.Entry<String, Object> variable : variables.entrySet()) {
            request = request.variable(variable.getKey(), variable.getValue());
        }
        return request.execute();
    }

    protected long createRevival(final long spexId, final int year, final String workingTitle) {
        final String id = graphQl(SUPER_ADMIN)
                .document("mutation($input: RevivalCreateInput!) { revivalCreate(input: $input) { id } }")
                .variable("input", Map.of("spexId", spexId, "year", year, "workingTitle", workingTitle))
                .execute()
                .path("revivalCreate.id").entity(String.class).get();

        return Long.parseLong(id);
    }

    protected String status(final long revivalId) {
        return jdbcClient.sql("SELECT status FROM revival WHERE id = ?").param(revivalId).query(String.class).single();
    }

    protected long version(final long revivalId) {
        return jdbcClient.sql("SELECT version FROM revival WHERE id = ?").param(revivalId).query(Long.class).single();
    }

    /**
     * Moves a revival to a status as super admin.
     */
    protected void setStatus(final long revivalId, final String to) {
        final String from = status(revivalId);

        if (from.equals(to)) {
            return;
        }
        graphQl(SUPER_ADMIN)
                .document("mutation($input: RevivalStatusChangeInput!) { revivalStatusChange(input: $input) { status } }")
                .variable("input", Map.of("id", revivalId, "from", from, "to", to))
                .execute()
                .path("revivalStatusChange.status").entity(String.class).isEqualTo(to);
    }

    /**
     * Adds a member as super admin and returns its id.
     */
    protected long addMember(final long revivalId, final String email, final String role, final List<String> capabilities) {
        final String id = graphQl(SUPER_ADMIN)
                .document("mutation($input: RevivalMemberAddInput!) { revivalMemberAdd(input: $input) { id } }")
                .variable("input", Map.of("revivalId", revivalId, "email", email, "role", role, "capabilities", capabilities))
                .execute()
                .path("revivalMemberAdd.id").entity(String.class).get();

        return Long.parseLong(id);
    }

    /**
     * Makes sure the user has logged in once, so a role added by their email is active at once.
     */
    protected void login(final String user) {
        graphQl(user).document("{ me { id } }").execute().path("me.id").hasValue();
    }

    // --- MCP --------------------------------------------------------------------------------------------------------

    protected RestTestClient restTestClient() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:%d".formatted(localPort)).build();
    }

    protected JsonNode mcpRpc(final String token, final String method, final Map<String, Object> params) {
        final Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", requestId.incrementAndGet(), "method", method, "params", params);
        final byte[] body = Objects.requireNonNull(restTestClient()
                .post()
                .uri(MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody());

        return objectMapper.readTree(body);
    }

    /**
     * Calls {@code list_revivals} and returns its {@code items}.
     */
    protected JsonNode listRevivals(final String token, final Map<String, Object> arguments) {
        final JsonNode result = mcpRpc(token, "tools/call", Map.of("name", "list_revivals", "arguments", arguments)).get("result");

        if (result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool error: " + result);
        }
        return objectMapper.readTree(result.get("content").get(0).get("text").asString()).get("items");
    }

    private record CachedToken(String value, Instant expires) {
    }
}
