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
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
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
import org.testcontainers.Testcontainers;
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
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL and Keycloak in Testcontainers, with the committed copy of {@code fgv-keycloak}'s realm plus fictitious users,
 * and WireMock standing in for Spexregister's GraphQL API. Every test starts from empty tables.
 *
 * @author Anders Jacobsson
 * @since 1.0
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
    protected static final String PARTICIPANT = "deltagare" + DOMAIN;
    protected static final String HELPER = "springare" + DOMAIN;
    protected static final String PASSWORD = "s3cr3t-s3cr3t";
    /**
     * Leaves out the catalogs' baseline revision, which {@link #resetState()} records before every test.
     */
    protected static final String NOT_BASELINE = "operation <> 'catalogBaseline'";
    protected static final String CLIENT_ID = "fgv-revival";
    protected static final String MCP_CLIENT_ID = "fgv-revival-mcp";
    protected static final String UNBOUND_MCP_CLIENT_ID = "fgv-revival-mcp-unbound";
    protected static final String SPEXREGISTER_MCP_CLIENT_ID = "spexregister-mcp";
    protected static final long ALI_BABA = 1L;
    protected static final long NOBEL = 2L;
    protected static final long SNORRE = 3L;
    protected static final long SPEX_REVIVAL = 99L;
    protected static final String MCP_PATH = "/api/mcp";
    protected static final WireMockServer SPEXREGISTER = new WireMockServer(options().dynamicPort());
    private static final String SEEDED_BUDGET_CATEGORIES = "'SHOW_TICKETS', 'PARTY_TICKETS', 'FEES', 'SPONSORSHIP', 'OTHER_INCOME', 'VENUE', 'FOOD', "
            + "'PARTY', 'TECHNICAL', 'COSTUMES', 'SCENERY', 'MARKETING', 'PRINTING', 'HELPERS', 'ADMIN', 'OTHER_COST'";
    private static final String SEEDED_BUDGET_CATEGORY_NAMES = "'Biljetter föreställning', 'Biljetter kalas', 'Spexpengar', 'Sponsring', "
            + "'Övriga intäkter', 'Lokaler', 'Mat och dryck', 'Kalas', 'Teknik', 'Kostym och smink', 'Rekvisita och kulisser', 'Marknadsföring', "
            + "'Tryck', 'Springare', 'Administration', 'Övriga kostnader'";
    private static final String SEEDED_LINK_SERVICES = "'SPEXFLIX', 'MANUSARKIV', 'SPEXIPEDIA', 'SPEXIFY', 'LATARKIV', 'GOOGLE_DRIVE', 'DROPBOX', "
            + "'YOUTUBE', 'ONEDRIVE'";
    private static final String SEEDED_LINK_SERVICE_NAMES = "'Spexflix', 'Manusarkiv', 'Spexipedia', 'Spexify', 'Låtarkiv', 'Google Drive', "
            + "'Dropbox', 'YouTube', 'OneDrive'";
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
    // An SMTP server in the test JVM for the minutes email, also the realm's SMTP server for invitations
    // A fixed free port, so a test can stop and restart it and the application still finds it
    private static final int SMTP_PORT = freePort();
    protected static final GreenMail MAIL = new GreenMail(new ServerSetup(SMTP_PORT, null, ServerSetup.PROTOCOL_SMTP))
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());
    /**
     * Ten fictitious chapters with keys A–J in v5's layout: an introduction, items before the first section, and four
     * sections (B ×2, D ×1, J ×1) with 6 TIP, 3 REQUIREMENT, 1 QUESTION and 11 TEXT items. Only Swedish text.
     */
    private static final List<SeedChapter> V5_LAYOUT = List.of(
            new SeedChapter("A", "Först: godkännande och tajmning", List.of(
                    text("Innan något annat görs behöver ni klartecken från JK och FGV."),
                    tip("Kontakt sker via respektive förenings ordförande."),
                    tip("Förankra idén **brett** och *tidigt*, se [fgv.nu](https://fgv.example.org)."),
                    requirement("Nyuppsättningen ska godkännas av JK och FGV innan arbetet startar.")), List.of()),
            new SeedChapter("B", "Boka lokaler och lägg fast er tidslinje", List.of(), List.of(
                    new SeedSection("Kalaslokal", List.of(
                            text("Om kalaset"),
                            text("250 personer är ett stort kalas."),
                            text("Ha en plan för vem som ska få gå."),
                            tip("Kontrollera sprittillstånd i vald lokal.\n\n- Gäller även internkalas."))),
                    new SeedSection("Tidslinjen", List.of(
                            text("Exempel på tidplan för nyuppsättning"),
                            new SeedItem("QUESTION", "Vilka datum har sittande spex?"))))),
            new SeedChapter("C", "Sätt budgeten", List.of(
                    tip("Sätt av en buffert för oväntade kostnader."),
                    requirement("Budget ska upprättas innan ni drar kostnader.")), List.of()),
            new SeedChapter("D", "Bygg organisationen", List.of(), List.of(
                    new SeedSection("Andra direktionsmötet", List.of(
                            text("Checklista för mötet"),
                            text("Organisation: roller och ansvar."),
                            tip("Starta en gemensam Drive."))))),
            new SeedChapter("E", "Ordna tillgångar inför repetitionerna", List.of(tip("Undersök accesser i god tid.")), List.of()),
            new SeedChapter("F", "Repetitioner", List.of(text("Dokumentera processen löpande, både för minnen och för lärdomar.")), List.of()),
            new SeedChapter("G", "Marknadsföring och biljetter", List.of(text("Starta marknadsföringen tidigt och var kreativa.")), List.of()),
            new SeedChapter("H", "Föreställnings- och kalashelgen", List.of(text("Kalaset är en lika stor apparat som föreställningen.")), List.of()),
            new SeedChapter("I", "Eventuell turné (frivilligt)", List.of(text("Frågan om turné dyker ofta upp under kalaset.")), List.of()),
            new SeedChapter("J", "Stänga nyuppsättningen", List.of(), List.of(
                    new SeedSection("Ekonomi", List.of(requirement("Ett eventuellt lån ska återbetalas."))))));

    static {
        Testcontainers.exposeHostPorts(SMTP_PORT);
        Startables.deepStart(MYSQL, KEYCLOAK).join();
        SPEXREGISTER.start();
        MAIL.start();
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
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> MAIL.getSmtp().getPort());
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void prepareKeycloak() {
        try (final Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final RealmRepresentation representation = realm.toRepresentation();

            // Keycloak in its container reaches the test JVM's GreenMail through Testcontainers' host gateway
            representation.setSmtpServer(Map.of("host", "host.testcontainers.internal", "port", String.valueOf(SMTP_PORT),
                    "from", "keycloak@revival.test", "auth", "false", "starttls", "false", "ssl", "false"));
            realm.update(representation);

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
            createUser(realm, PARTICIPANT, "Pia", "Deltagare", CLIENT_ID, List.of("USER"));
            createUser(realm, HELPER, "Hugo", "Springare", CLIENT_ID, List.of("USER"));
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

    /**
     * Asserts that the response has an error with the given {@code extensions.classification}, e.g. {@code FORBIDDEN}.
     */
    protected static void expectError(final GraphQlTester.Response response, final String code) {
        response.errors().satisfy(errors -> assertThat(errors)
                .as("errors")
                .anySatisfy(error -> assertThat(String.valueOf(error.getErrorType())).isEqualTo(code)));
    }

    private static SeedItem tip(final String text) {
        return new SeedItem("TIP", text);
    }

    private static SeedItem requirement(final String text) {
        return new SeedItem("REQUIREMENT", text);
    }

    private static SeedItem text(final String text) {
        return new SeedItem("TEXT", text);
    }

    private static long insert(final JdbcClient.StatementSpec statement) {
        final org.springframework.jdbc.support.KeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();

        statement.update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    @BeforeEach
    void resetState() {
        jdbcClient.sql("UPDATE log_entry SET answers_entry_id = NULL").update();
        jdbcClient.sql("UPDATE minutes_email SET retry_of = NULL").update();
        jdbcClient.sql("UPDATE event_type SET checklist_template_id = NULL").update();
        for (final String table : List.of("sync_step", "sync_run", "inventory_item", "material_position", "material", "mailing_reminder", "mailing_recipient", "mailing", "mailing_template", "shift_assignment", "shift_slot",
                "availability", "receipt_content", "budget_transaction", "budget_line", "budget_scenario", "budget_settings",
                "reference_figure", "reference_revival", "event_type_budget_line", "spex_calendar_date", "calendar_feed", "minutes_email_recipient",
                "minutes_email", "meeting_entry_change", "log_entry_tag",
                "log_entry", "log_entry_counter", "meeting_agenda_point", "meeting_attendee", "meeting",
                "checklist_item", "checklist", "event", "checklist_template_item", "checklist_template", "guide_suggestion", "referral", "dietary_need",
                "pending_person", "participant", "participation_settings", "revival_member_capability", "revival_member", "revival_status_change",
                "revival_guide_version_change", "revival_show", "revival",
                "guide_item", "guide_section", "guide_chapter", "guide_version")) {
            jdbcClient.sql("DELETE FROM " + table).update();
        }
        // Back to the lists the migration seeds
        jdbcClient.sql("DELETE FROM capability WHERE id NOT IN ('FINANCE', 'CATERING', 'STAFFING', 'COMMS')").update();
        jdbcClient.sql("UPDATE capability SET retired_at = NULL, position = FIELD(id, 'FINANCE', 'CATERING', 'STAFFING', 'COMMS') - 1").update();
        jdbcClient.sql("DELETE FROM guide_item_type WHERE id NOT IN ('TIP', 'REQUIREMENT', 'QUESTION', 'TEXT')").update();
        jdbcClient.sql("""
                UPDATE guide_item_type SET retired_at = NULL, position = FIELD(id, 'TIP', 'REQUIREMENT', 'QUESTION', 'TEXT') - 1,
                    name_sv = ELT(FIELD(id, 'TIP', 'REQUIREMENT', 'QUESTION', 'TEXT'), 'Praktiska tips', 'Krav', 'Att ta reda på', 'Text')""").update();
        jdbcClient.sql("DELETE FROM link_service_host").update();
        jdbcClient.sql("DELETE FROM link_service WHERE created_by <> 'system'").update();
        jdbcClient.sql("UPDATE link_service SET version = 0, retired_at = NULL, position = FIELD(id, " + SEEDED_LINK_SERVICES + ") - 1, "
                + "name_sv = ELT(FIELD(id, " + SEEDED_LINK_SERVICES + "), " + SEEDED_LINK_SERVICE_NAMES + "), "
                + "name_en = IF(id = 'LATARKIV', 'Song archive', NULL), "
                + "home_url = ELT(FIELD(id, " + SEEDED_LINK_SERVICES + "), NULL, NULL, NULL, NULL, NULL, 'https://drive.google.com/', "
                + "'https://www.dropbox.com/', 'https://www.youtube.com/', 'https://onedrive.live.com/')").update();
        jdbcClient.sql("""
                INSERT INTO link_service_host (link_service_id, host)
                VALUES ('GOOGLE_DRIVE', 'drive.google.com'), ('GOOGLE_DRIVE', 'docs.google.com'), ('DROPBOX', 'dropbox.com'),
                       ('YOUTUBE', 'youtube.com'), ('YOUTUBE', 'youtu.be'), ('ONEDRIVE', 'onedrive.live.com'), ('ONEDRIVE', '1drv.ms'),
                       ('ONEDRIVE', 'sharepoint.com')""").update();
        jdbcClient.sql("DELETE FROM event_type WHERE created_by <> 'system'").update();
        jdbcClient.sql("DELETE FROM budget_category WHERE created_by <> 'system'").update();
        jdbcClient.sql("UPDATE budget_category SET retired_at = NULL, position = FIELD(id, " + SEEDED_BUDGET_CATEGORIES + ") - 1, "
                + "name_sv = ELT(FIELD(id, " + SEEDED_BUDGET_CATEGORIES + "), " + SEEDED_BUDGET_CATEGORY_NAMES + "), "
                + "role = IF(id IN ('SHOW_TICKETS', 'PARTY_TICKETS', 'FEES', 'VENUE', 'PARTY', 'HELPERS'), id, NULL)").update();
        jdbcClient.sql("""
                UPDATE event_type SET retired_at = NULL,
                    position = FIELD(name_sv, 'AW', 'Kickoff', 'Rephelg 1', 'Rephelg 2', 'Rephelg 3', 'Föreställningshelg', 'Kortafton') - 1""").update();
        for (final String table : List.of("revival_member_capability_audit", "revival_member_audit", "revival_status_change_audit",
                "revival_guide_version_change_audit", "revival_show_audit", "revival_audit",
                "log_entry_tag_audit", "log_entry_audit", "meeting_agenda_point_audit", "meeting_attendee_audit",
                "meeting_audit", "checklist_item_audit", "checklist_audit", "event_audit", "event_type_audit", "checklist_template_item_audit", "checklist_template_audit",
                "guide_suggestion_audit", "guide_item_audit", "guide_section_audit", "guide_chapter_audit", "guide_version_audit",
                "capability_audit", "guide_item_type_audit", "budget_transaction_audit", "budget_line_audit", "budget_scenario_audit",
                "budget_settings_audit", "reference_figure_audit", "reference_revival_audit", "event_type_budget_line_audit", "budget_category_audit",
                "spex_calendar_date_audit", "participant_audit", "participation_settings_audit", "availability_audit", "shift_slot_audit",
                "shift_assignment_audit", "mailing_audit", "mailing_recipient_audit", "mailing_template_audit", "inventory_item_audit",
                "material_position_audit", "material_audit", "sync_step_audit", "sync_run_audit", "link_service_host_audit", "link_service_audit", "revchanges", "revinfo", "person")) {
            jdbcClient.sql("DELETE FROM " + table).update();
        }
        // The validity audit strategy needs a previous revision before the seeded entries can be changed
        jdbcClient.sql("INSERT INTO revinfo (modified_at, modified_by, source, operation) VALUES (0, 'system', 'SYSTEM', 'catalogBaseline')").update();
        jdbcClient.sql("""
                INSERT INTO capability_audit (id, name_sv, name_en, position, retired_at, version, rev, revtype)
                SELECT id, name_sv, name_en, position, retired_at, version, (SELECT MAX(id) FROM revinfo), 0 FROM capability""").update();
        jdbcClient.sql("""
                INSERT INTO guide_item_type_audit (id, kind, name_sv, name_en, position, retired_at, version, rev, revtype)
                SELECT id, kind, name_sv, name_en, position, retired_at, version, (SELECT MAX(id) FROM revinfo), 0 FROM guide_item_type""").update();
        jdbcClient.sql("""
                INSERT INTO event_type_audit (id, name_sv, name_en, description_sv, description_en, rooms_sv, rooms_en, catering_sv, catering_en,
                    offset_min_days, offset_max_days, days, revival_pays, checklist_template_id, position, retired_at, version, rev, revtype)
                SELECT id, name_sv, name_en, description_sv, description_en, rooms_sv, rooms_en, catering_sv, catering_en,
                    offset_min_days, offset_max_days, days, revival_pays, checklist_template_id, position, retired_at, version,
                    (SELECT MAX(id) FROM revinfo), 0 FROM event_type""").update();
        jdbcClient.sql("""
                INSERT INTO budget_category_audit (id, kind, role, name_sv, name_en, position, retired_at, version, rev, revtype)
                SELECT id, kind, role, name_sv, name_en, position, retired_at, version, (SELECT MAX(id) FROM revinfo), 0 FROM budget_category""").update();
        jdbcClient.sql("""
                INSERT INTO link_service_audit (id, name_sv, name_en, home_url, position, retired_at, version, rev, revtype)
                SELECT id, name_sv, name_en, home_url, position, retired_at, version, (SELECT MAX(id) FROM revinfo), 0 FROM link_service""").update();
        jdbcClient.sql("""
                INSERT INTO link_service_host_audit (link_service_id, host, rev, revtype)
                SELECT link_service_id, host, (SELECT MAX(id) FROM revinfo), 0 FROM link_service_host""").update();
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
        spexregisterUp();
        MAIL.reset();
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

    protected GraphQlTester.Response execute(final String user, final String document, final Map<String, Object> variables) {
        GraphQlTester.Request<?> request = graphQl(user).document(document);

        for (final Map.Entry<String, Object> variable : variables.entrySet()) {
            request = request.variable(variable.getKey(), variable.getValue());
        }
        return request.execute();
    }

    protected long createRevival(final long spexId, final int year) {
        final String id = graphQl(SUPER_ADMIN)
                .document("mutation($input: RevivalCreateInput!) { revivalCreate(input: $input) { id } }")
                .variable("input", Map.of("spexId", spexId, "year", year))
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

    /**
     * The person id of a user who has logged in.
     */
    protected long personId(final String email) {
        return jdbcClient.sql("SELECT id FROM person WHERE email = ?").param(email).query(Long.class).single();
    }

    /**
     * A revival in {@code ORGANISING} with {@link #DIRECTEUR} as directeur and {@link #STEERING_GROUP} in the steering
     * group, both logged in.
     */
    protected long organisingRevival() {
        final long revival = createRevival(ALI_BABA, 2028);

        setStatus(revival, "APPROVED");
        login(DIRECTEUR);
        login(STEERING_GROUP);
        addMember(revival, DIRECTEUR, "DIRECTEUR", List.of());
        addMember(revival, STEERING_GROUP, "STEERING_GROUP", List.of());
        setStatus(revival, "ORGANISING");
        return revival;
    }

    /**
     * Writes the fictitious v5-layout guide as the draft, straight to the database, and returns the draft's id. Chapter
     * keys are A–J; section and item keys are new UUIDs.
     */
    protected long draftV5Layout() {
        final java.time.LocalDateTime now = java.time.LocalDateTime.now();
        final long versionId = insert(jdbcClient.sql("""
                        INSERT INTO guide_version (status, intro_sv, created_by, created_at, version)
                        VALUES ('DRAFT', :intro, 'test', :now, 0)
                        """)
                .param("intro", "Kontinuitetsguide: Nyuppsättning av ett Chalmersspex\n\nNi funderar alltså över nyuppsättning av ert gamla spex? Grattis\\!")
                .param("now", now));

        for (int chapterPosition = 0; chapterPosition < V5_LAYOUT.size(); chapterPosition++) {
            final SeedChapter chapter = V5_LAYOUT.get(chapterPosition);
            final long chapterId = insert(jdbcClient.sql("""
                            INSERT INTO guide_chapter (version_id, `key`, position, title_sv, created_by, created_at, version)
                            VALUES (:versionId, :key, :position, :title, 'test', :now, 0)
                            """)
                    .param("versionId", versionId)
                    .param("key", chapter.key())
                    .param("position", chapterPosition)
                    .param("title", chapter.title())
                    .param("now", now));

            insertSeedItems(versionId, chapterId, null, chapter.items(), now);
            for (int sectionPosition = 0; sectionPosition < chapter.sections().size(); sectionPosition++) {
                final SeedSection section = chapter.sections().get(sectionPosition);
                final long sectionId = insert(jdbcClient.sql("""
                                INSERT INTO guide_section (version_id, chapter_id, `key`, position, title_sv, created_by, created_at, version)
                                VALUES (:versionId, :chapterId, :key, :position, :title, 'test', :now, 0)
                                """)
                        .param("versionId", versionId)
                        .param("chapterId", chapterId)
                        .param("key", java.util.UUID.randomUUID().toString())
                        .param("position", sectionPosition)
                        .param("title", section.title())
                        .param("now", now));

                insertSeedItems(versionId, chapterId, sectionId, section.items(), now);
            }
        }
        audit(versionId);
        return versionId;
    }

    /**
     * Audit rows for the seeded version, as if it had been created in one revision: Envers needs a row to end when the
     * version is later published or edited.
     */
    private void audit(final long versionId) {
        final long rev = insert(jdbcClient.sql("""
                        INSERT INTO revinfo (modified_at, modified_by, source, operation) VALUES (:at, 'test', 'SYSTEM', 'testSeed')
                        """)
                .param("at", System.currentTimeMillis()));

        for (final String sql : List.of(
                """
                        INSERT INTO guide_version_audit (id, status, label, note, intro_sv, intro_en, based_on_id, published_at, published_by, version, rev, revtype)
                        SELECT id, status, label, note, intro_sv, intro_en, based_on_id, published_at, published_by, version, :rev, 0 FROM guide_version
                        WHERE id = :versionId
                        """,
                """
                        INSERT INTO guide_chapter_audit (id, version_id, `key`, position, title_sv, title_en, version, rev, revtype)
                        SELECT id, version_id, `key`, position, title_sv, title_en, version, :rev, 0 FROM guide_chapter WHERE version_id = :versionId
                        """,
                """
                        INSERT INTO guide_section_audit (id, version_id, chapter_id, `key`, position, title_sv, title_en, version, rev, revtype)
                        SELECT id, version_id, chapter_id, `key`, position, title_sv, title_en, version, :rev, 0 FROM guide_section WHERE version_id = :versionId
                        """,
                """
                        INSERT INTO guide_item_audit (id, version_id, chapter_id, section_id, `key`, position, type, text_sv, text_en, version, rev, revtype)
                        SELECT id, version_id, chapter_id, section_id, `key`, position, type, text_sv, text_en, version, :rev, 0 FROM guide_item
                        WHERE version_id = :versionId
                        """)) {
            jdbcClient.sql(sql).param("rev", rev).param("versionId", versionId).update();
        }
    }

    private void insertSeedItems(final long versionId, final long chapterId, final @Nullable Long sectionId, final List<SeedItem> items,
                                 final java.time.LocalDateTime now) {
        for (int position = 0; position < items.size(); position++) {
            jdbcClient.sql("""
                            INSERT INTO guide_item (version_id, chapter_id, section_id, `key`, position, type, text_sv, created_by, created_at, version)
                            VALUES (:versionId, :chapterId, :sectionId, :key, :position, :type, :text, 'test', :now, 0)
                            """)
                    .param("versionId", versionId)
                    .param("chapterId", chapterId)
                    .param("sectionId", sectionId)
                    .param("key", java.util.UUID.randomUUID().toString())
                    .param("position", position)
                    .param("type", items.get(position).type())
                    .param("text", items.get(position).text())
                    .param("now", now)
                    .update();
        }
    }

    protected long guideVersion(final long versionId) {
        return jdbcClient.sql("SELECT version FROM guide_version WHERE id = ?").param(versionId).query(Long.class).single();
    }

    /**
     * Publishes the draft as super admin. The server numbers versions itself, so {@code label} is not sent.
     */
    protected void publishGuide(final long draftId, final String label) {
        graphQl(SUPER_ADMIN)
                .document("mutation($input: GuideDraftPublishInput!) { guideDraftPublish(input: $input) { id status label } }")
                .variable("input", Map.of("id", draftId, "version", guideVersion(draftId)))
                .execute()
                .path("guideDraftPublish.status").entity(String.class).isEqualTo("PUBLISHED");
    }

    /**
     * Writes and publishes the v5-layout guide as "v1", and returns the version's id.
     */
    protected long publishedGuide() {
        final long id = draftV5Layout();

        publishGuide(id, "v1");
        return id;
    }

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

    private record SeedItem(String type, String text) {
    }

    private record SeedSection(String title, List<SeedItem> items) {
    }

    private record SeedChapter(String key, String title, List<SeedItem> items, List<SeedSection> sections) {
    }

    private record CachedToken(String value, Instant expires) {
    }
}
