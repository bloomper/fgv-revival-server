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

package nu.fgv.revival.server.util.data;

import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditSource;
import nu.fgv.revival.server.budget.BudgetCategory;
import nu.fgv.revival.server.checklist.template.ChecklistTemplate;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItem;
import nu.fgv.revival.server.event.EventType;
import nu.fgv.revival.server.guide.GuideChapter;
import nu.fgv.revival.server.guide.GuideItem;
import nu.fgv.revival.server.guide.GuideItemType;
import nu.fgv.revival.server.guide.GuideSection;
import nu.fgv.revival.server.guide.GuideVersion;
import nu.fgv.revival.server.material.LinkService;
import nu.fgv.revival.server.member.Capability;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalGuideVersionChange;
import nu.fgv.revival.server.revival.RevivalShow;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalStatusChange;
import nu.fgv.revival.server.spex.SpexDto;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.security.SecurityUtil;
import org.jspecify.annotations.Nullable;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.springframework.util.StringUtils.hasText;

/**
 * Replaces all data with sample revivals built on Spexregister's sample data. The spex come from Spexregister's API.
 * Spexregister's sample user {@value #ADMIN} becomes super admin and must exist, which keeps the import away from real
 * environments. The other users have one purpose each and are created in Keycloak when missing; Spexregister's import
 * deletes them along with all other users.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SampleDataImporter {

    static final String SAMPLE_USER_DOMAIN = "@fgv.nu";
    static final String ADMIN = "admin" + SAMPLE_USER_DOMAIN;
    static final String OBSERVER = "observer" + SAMPLE_USER_DOMAIN;
    static final String DIRECTEUR = "directeur" + SAMPLE_USER_DOMAIN;
    static final String DIRECTEUR_2 = "directeur2" + SAMPLE_USER_DOMAIN;
    static final String STEERING_GROUP = "steering-group" + SAMPLE_USER_DOMAIN;
    static final String NO_ROLE = "no-role" + SAMPLE_USER_DOMAIN;
    static final String INVITED = "invited" + SAMPLE_USER_DOMAIN;
    static final String PARTICIPANT = "participant" + SAMPLE_USER_DOMAIN;
    static final String HELPER = "helper" + SAMPLE_USER_DOMAIN;
    static final String OPERATION = "sampleDataImport";
    static final int NUMBER_OF_SAMPLES_REVIVALS = 2 * RevivalStatus.values().length;
    /**
     * How long ago the second archived revival was archived, long enough for the automatic anonymization to take it.
     */
    static final int ANONYMIZED_YEARS_AGO = 4;
    private static final int SPEXREGISTER_ATTEMPTS = 30;
    private static final Duration SPEXREGISTER_RETRY_INTERVAL = Duration.ofSeconds(10);
    private static final List<String> VENUES = List.of(
            "RunAn, Chalmers kårhus",
            "Gasquen, Chalmers kårhus",
            "Lindholmen Conference Centre",
            "Draken",
            "Pustervik",
            "Kulturhuset Kåken"
    );
    private static final List<RevivalStatus> DIRECTEUR_PATH = List.of(
            RevivalStatus.PROPOSED,
            RevivalStatus.APPROVED,
            RevivalStatus.ORGANISING,
            RevivalStatus.OPEN,
            RevivalStatus.CLOSING,
            RevivalStatus.SETTLING
    );
    private static final Set<RevivalStatus> SUPER_ADMIN_STEPS = EnumSet.of(RevivalStatus.APPROVED, RevivalStatus.REJECTED, RevivalStatus.ARCHIVED);
    private static final Set<RevivalStatus> PLAYED = EnumSet.of(RevivalStatus.CLOSING, RevivalStatus.SETTLING, RevivalStatus.ARCHIVED);

    private final SpexregisterClient spexregisterClient;
    private final Keycloak keycloak;
    private final String keycloakRealm;
    private final String sampleUserPassword;
    private final Random rnd = new SecureRandom();

    public SampleDataImporter(final SpexregisterClient spexregisterClient,
                              final Keycloak keycloak,
                              @Value("${fgv-revival.keycloak.realm}") final String keycloakRealm,
                              @Value("${fgv-revival.data.sample-user-password}") final String sampleUserPassword) {
        this.spexregisterClient = spexregisterClient;
        this.keycloak = keycloak;
        this.keycloakRealm = keycloakRealm;
        this.sampleUserPassword = sampleUserPassword;
    }

    static String steeringGroupWith(final SeededCapability capability) {
        return "steering-group-" + capability.name().toLowerCase(Locale.ROOT) + SAMPLE_USER_DOMAIN;
    }

    private static void sleep() {
        try {
            Thread.sleep(SPEXREGISTER_RETRY_INTERVAL);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Spexregister", e);
        }
    }

    private static void grantClientRoles(final RealmResource realm,
                                         final ClientResource client,
                                         final String clientUuid,
                                         final UserRepresentation user,
                                         final String... roles) {
        final List<RoleRepresentation> representations = Arrays.stream(roles)
                .map(role -> client.roles().get(role).toRepresentation())
                .toList();

        realm.users().get(user.getId()).roles().clientLevel(clientUuid).add(representations);
    }

    private static SamplePerson insertPerson(final JdbcClient jdbcClient, final UserRepresentation user) {
        final KeyHolder keyHolder = new GeneratedKeyHolder();
        final String displayName = Stream.of(user.getFirstName(), user.getLastName())
                .filter(part -> hasText(part))
                .collect(Collectors.joining(" "));

        jdbcClient
                .sql("""
                        INSERT INTO person
                            (external_id, email, email_verified, display_name)
                        VALUES
                            (:externalId, :email, :emailVerified, :displayName)
                        """)
                .param("externalId", user.getId())
                .param("email", CallerService.normalizedEmail(user.getEmail()))
                .param("emailVerified", Boolean.TRUE.equals(user.isEmailVerified()))
                .param("displayName", displayName.isEmpty() ? null : displayName)
                .update(keyHolder);

        return new SamplePerson(Objects.requireNonNull(keyHolder.getKey()).longValue(), user.getId());
    }

    /**
     * The latest version when the revival was created, as when a super admin creates one.
     */
    private static void pinGuideVersion(final JdbcClient jdbcClient,
                                        final long revivalId,
                                        final List<SampleGuide.Version> guide,
                                        final Instant createdAt,
                                        final SamplePerson admin) {
        final long versionId = guide.stream()
                .filter(version -> !version.publishedAt().isAfter(createdAt))
                .reduce((first, second) -> second)
                .orElseThrow()
                .id();

        jdbcClient
                .sql("UPDATE revival SET guide_version_id = :versionId WHERE id = :id")
                .param("versionId", versionId)
                .param("id", revivalId)
                .update();
        jdbcClient
                .sql("""
                        INSERT INTO revival_guide_version_change
                            (revival_id, from_version_id, to_version_id, to_label, changed_by, changed_at)
                        SELECT :revivalId, NULL, id, label, :changedBy, :changedAt FROM guide_version WHERE id = :versionId
                        """)
                .param("revivalId", revivalId)
                .param("versionId", versionId)
                .param("changedBy", admin.id())
                .param("changedAt", utc(createdAt))
                .update();
    }

    private static List<RevivalStatus> statusPath(final RevivalStatus status) {
        return switch (status) {
            case REJECTED -> List.of(RevivalStatus.PROPOSED, RevivalStatus.REJECTED);
            case ARCHIVED -> Stream.concat(DIRECTEUR_PATH.stream(), Stream.of(RevivalStatus.ARCHIVED)).toList();
            default -> DIRECTEUR_PATH.subList(0, DIRECTEUR_PATH.indexOf(status) + 1);
        };
    }

    private static long insertMember(final JdbcClient jdbcClient,
                                     final long revivalId,
                                     final @Nullable Long personId,
                                     final @Nullable String pendingEmail,
                                     final RevivalRole role,
                                     final Set<SeededCapability> capabilities,
                                     final Instant createdAt,
                                     final String createdBy) {
        final KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcClient
                .sql("""
                        INSERT INTO revival_member
                            (revival_id, person_id, pending_email, role, created_by, created_at, version)
                        VALUES
                            (:revivalId, :personId, :pendingEmail, :role, :createdBy, :createdAt, 0)
                        """)
                .param("revivalId", revivalId)
                .param("personId", personId)
                .param("pendingEmail", pendingEmail)
                .param("role", role.name())
                .param("createdBy", createdBy)
                .param("createdAt", utc(createdAt))
                .update(keyHolder);

        final long memberId = Objects.requireNonNull(keyHolder.getKey()).longValue();

        capabilities.forEach(capability -> jdbcClient
                .sql("INSERT INTO revival_member_capability (member_id, capability) VALUES (:memberId, :capability)")
                .param("memberId", memberId)
                .param("capability", capability.name())
                .update());

        return memberId;
    }

    /**
     * Gives every sample row one revision, so that the history starts from the sample data.
     */
    private static void seedBaselineRevision(final JdbcClient jdbcClient) {
        final KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcClient
                .sql("""
                        INSERT INTO revinfo
                            (modified_at, modified_by, source, operation)
                        VALUES
                            (:modifiedAt, :modifiedBy, :source, :operation)
                        """)
                .param("modifiedAt", System.currentTimeMillis())
                .param("modifiedBy", SecurityUtil.SYSTEM)
                .param("source", AuditSource.SYSTEM.name())
                .param("operation", OPERATION)
                .update(keyHolder);

        final long rev = Objects.requireNonNull(keyHolder.getKey()).longValue();

        List.of(
                """
                        INSERT INTO revival_audit (id, spex_id, year, premiere_date, status, guide_version_id, version, rev, revtype)
                        SELECT id, spex_id, year, premiere_date, status, guide_version_id, version, :rev, 0 FROM revival
                        """,
                """
                        INSERT INTO revival_show_audit (id, revival_id, show_date, start_time, venue, rev, revtype)
                        SELECT id, revival_id, show_date, start_time, venue, :rev, 0 FROM revival_show
                        """,
                """
                        INSERT INTO revival_status_change_audit (id, revival_id, from_status, to_status, changed_by, changed_at, rev, revtype)
                        SELECT id, revival_id, from_status, to_status, changed_by, changed_at, :rev, 0 FROM revival_status_change
                        """,
                """
                        INSERT INTO revival_member_audit (id, revival_id, person_id, role, removed_at, removed_by, version, rev, revtype)
                        SELECT id, revival_id, person_id, role, removed_at, removed_by, version, :rev, 0 FROM revival_member
                        """,
                """
                        INSERT INTO revival_member_capability_audit (member_id, capability, rev, revtype)
                        SELECT member_id, capability, :rev, 0 FROM revival_member_capability
                        """,
                """
                        INSERT INTO revival_guide_version_change_audit (id, revival_id, from_version_id, to_version_id, from_label, to_label, changed_by, changed_at, rev, revtype)
                        SELECT id, revival_id, from_version_id, to_version_id, from_label, to_label, changed_by, changed_at, :rev, 0 FROM revival_guide_version_change
                        """,
                """
                        INSERT INTO guide_version_audit (id, status, label, note, intro_sv, intro_en, based_on_id, published_at, published_by, version, rev, revtype)
                        SELECT id, status, label, note, intro_sv, intro_en, based_on_id, published_at, published_by, version, :rev, 0 FROM guide_version
                        """,
                """
                        INSERT INTO guide_chapter_audit (id, version_id, `key`, position, title_sv, title_en, version, rev, revtype)
                        SELECT id, version_id, `key`, position, title_sv, title_en, version, :rev, 0 FROM guide_chapter
                        """,
                """
                        INSERT INTO guide_section_audit (id, version_id, chapter_id, `key`, position, title_sv, title_en, version, rev, revtype)
                        SELECT id, version_id, chapter_id, `key`, position, title_sv, title_en, version, :rev, 0 FROM guide_section
                        """,
                """
                        INSERT INTO guide_item_audit (id, version_id, chapter_id, section_id, `key`, position, type, text_sv, text_en, version, rev, revtype)
                        SELECT id, version_id, chapter_id, section_id, `key`, position, type, text_sv, text_en, version, :rev, 0 FROM guide_item
                        """,
                """
                        INSERT INTO checklist_template_audit (id, kind, chapter_key, name_sv, name_en, retired_at, version, rev, revtype)
                        SELECT id, kind, chapter_key, name_sv, name_en, retired_at, version, :rev, 0 FROM checklist_template
                        """,
                """
                        INSERT INTO checklist_template_item_audit (id, template_id, position, text_sv, text_en, guide_item_key, default_capability,
                                                                   deadline_kind, deadline_amount, deadline_weekday, retired_at, version, rev, revtype)
                        SELECT id, template_id, position, text_sv, text_en, guide_item_key, default_capability,
                               deadline_kind, deadline_amount, deadline_weekday, retired_at, version, :rev, 0 FROM checklist_template_item
                        """,
                // The catalogs survive the purge, but their history does not; updating one needs a previous revision
                """
                        INSERT INTO capability_audit (id, name_sv, name_en, position, retired_at, version, rev, revtype)
                        SELECT id, name_sv, name_en, position, retired_at, version, :rev, 0 FROM capability
                        """,
                """
                        INSERT INTO guide_item_type_audit (id, kind, name_sv, name_en, position, retired_at, version, rev, revtype)
                        SELECT id, kind, name_sv, name_en, position, retired_at, version, :rev, 0 FROM guide_item_type
                        """,
                """
                        INSERT INTO budget_category_audit (id, kind, role, name_sv, name_en, position, retired_at, version, rev, revtype)
                        SELECT id, kind, role, name_sv, name_en, position, retired_at, version, :rev, 0 FROM budget_category
                        """,
                """
                        INSERT INTO link_service_audit (id, name_sv, name_en, home_url, position, retired_at, version, rev, revtype)
                        SELECT id, name_sv, name_en, home_url, position, retired_at, version, :rev, 0 FROM link_service
                        """,
                """
                        INSERT INTO link_service_host_audit (link_service_id, host, rev, revtype)
                        SELECT link_service_id, host, :rev, 0 FROM link_service_host
                        """,
                """
                        INSERT INTO event_type_audit (id, name_sv, name_en, description_sv, description_en, rooms_sv, rooms_en, catering_sv, catering_en,
                                                      offset_min_days, offset_max_days, days, revival_pays, checklist_template_id, position, retired_at,
                                                      version, rev, revtype)
                        SELECT id, name_sv, name_en, description_sv, description_en, rooms_sv, rooms_en, catering_sv, catering_en,
                               offset_min_days, offset_max_days, days, revival_pays, checklist_template_id, position, retired_at,
                               version, :rev, 0 FROM event_type
                        """
        ).forEach(sql -> jdbcClient.sql(sql).param("rev", rev).update());

        Stream.of(Revival.class, RevivalShow.class, RevivalStatusChange.class, RevivalMember.class, RevivalGuideVersionChange.class,
                GuideVersion.class, GuideChapter.class, GuideSection.class, GuideItem.class, ChecklistTemplate.class, ChecklistTemplateItem.class,
                Capability.class, GuideItemType.class, EventType.class, BudgetCategory.class, LinkService.class).forEach(entity -> jdbcClient
                .sql("INSERT INTO revchanges (rev, entityname) VALUES (:rev, :entityName)")
                .param("rev", rev)
                .param("entityName", entity.getName())
                .update());
    }

    private static OffsetDateTime utc(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public void importSampleData(final Connection connection) {
        final JdbcClient jdbcClient = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        final long started = System.nanoTime();

        log.info("Importing sample data");
        timed("Emptied the tables", () -> purgeAllRelevantTables(jdbcClient));

        final List<SpexDto> spex = timedResult("Read the spex from Spexregister", this::originalSpex);
        final RealmResource realm = keycloak.realm(keycloakRealm);
        final Map<String, SamplePerson> people = timedResult("Created the sample users", () -> NotificationSuspender.whileSuspended(realm, () -> createSampleUsers(jdbcClient, realm)));

        final List<SampleGuide.Version> guide = timedResult("Created the guide", () -> SampleGuide.insert(jdbcClient, people.get(ADMIN).id(), people.get(ADMIN).externalId()));

        timed("Created the checklist templates", () -> SampleChecklistTemplates.insert(jdbcClient, people.get(ADMIN).externalId()));
        timed("Created the revivals", () -> createSampleRevivals(jdbcClient, spex, people, guide));
        timed("Seeded the baseline revision", () -> seedBaselineRevision(jdbcClient));
        log.info("Imported sample data in {} s", Duration.ofNanos(System.nanoTime() - started).toSeconds());
    }

    private void timed(final String step, final Runnable action) {
        timedResult(step, () -> {
            action.run();
            return null;
        });
    }

    private <T> T timedResult(final String step, final Supplier<T> action) {
        final long started = System.nanoTime();
        final T result = action.get();

        log.info("{} in {} ms", step, Duration.ofNanos(System.nanoTime() - started).toMillis());
        return result;
    }

    private void purgeAllRelevantTables(final JdbcClient jdbcClient) {
        final List<String> tables = List.of(
                "inventory_item_audit",
                "inventory_item",
                "material_position_audit",
                "material_position",
                "material_audit",
                "material",
                "link_service_host_audit",
                "link_service_audit",
                "mailing_reminder",
                "mailing_recipient_audit",
                "mailing_recipient",
                "mailing_audit",
                "mailing",
                "mailing_template_audit",
                "mailing_template",
                "shift_assignment_audit",
                "shift_assignment",
                "shift_slot_audit",
                "shift_slot",
                "availability_audit",
                "availability",
                "referral",
                "dietary_need",
                "pending_person",
                "participant_audit",
                "participant",
                "participation_settings_audit",
                "participation_settings",
                "calendar_feed",
                "receipt_content",
                "budget_transaction_audit",
                "budget_line_audit",
                "budget_scenario_audit",
                "budget_settings_audit",
                "reference_figure_audit",
                "reference_revival_audit",
                "event_type_budget_line_audit",
                "budget_category_audit",
                "spex_calendar_date_audit",
                "minutes_email_recipient",
                "minutes_email",
                "meeting_entry_change",
                "log_entry_tag_audit",
                "log_entry_audit",
                "meeting_agenda_point_audit",
                "meeting_attendee_audit",
                "meeting_audit",
                "checklist_item_audit",
                "checklist_audit",
                "event_audit",
                "event_type_audit",
                "checklist_template_item_audit",
                "checklist_template_audit",
                "capability_audit",
                "guide_item_type_audit",
                "guide_suggestion_audit",
                "revival_guide_version_change_audit",
                "guide_item_audit",
                "guide_section_audit",
                "guide_chapter_audit",
                "guide_version_audit",
                "revival_member_capability_audit",
                "revival_member_audit",
                "revival_status_change_audit",
                "revival_show_audit",
                "revival_audit",
                "revchanges",
                "revinfo",
                "budget_transaction",
                "budget_line",
                "budget_scenario",
                "budget_settings",
                "reference_figure",
                "reference_revival",
                "event_type_budget_line",
                "spex_calendar_date",
                "log_entry_tag",
                "log_entry",
                "log_entry_counter",
                "meeting_agenda_point",
                "meeting_attendee",
                "meeting",
                "checklist_item",
                "checklist",
                "event",
                "checklist_template_item",
                "checklist_template",
                "guide_suggestion",
                "revival_guide_version_change",
                "revival_member_capability",
                "revival_member",
                "revival_status_change",
                "revival_show",
                "revival",
                "guide_item",
                "guide_section",
                "guide_chapter",
                "guide_version",
                "person"
        );

        jdbcClient.sql("UPDATE log_entry SET answers_entry_id = NULL").update();
        jdbcClient.sql("UPDATE minutes_email SET retry_of = NULL").update();
        // The event types survive the purge, as the catalogs do, but not the templates they point at
        jdbcClient.sql("UPDATE event_type SET checklist_template_id = NULL").update();
        tables.forEach(table ->
                jdbcClient
                        .sql("DELETE FROM %s".formatted(table))
                        .update()
        );
    }

    /**
     * Waits for Spexregister, which answers only once its own sample data import is done.
     */
    private List<SpexDto> originalSpex() {
        for (int attempt = 1; ; attempt++) {
            try {
                final List<SpexDto> spex = spexregisterClient.originalSpex();

                if (spex.isEmpty()) {
                    throw new IllegalStateException("Spexregister has no spex; import its sample data first");
                }
                return spex;
            } catch (final SpexregisterClient.SpexregisterCallException e) {
                if (attempt == SPEXREGISTER_ATTEMPTS) {
                    throw new IllegalStateException("Spexregister is unavailable, so sample data cannot be imported", e);
                }
                log.info("Waiting for Spexregister ({}/{})", attempt, SPEXREGISTER_ATTEMPTS);
                sleep();
            }
        }
    }

    private Map<String, SamplePerson> createSampleUsers(final JdbcClient jdbcClient, final RealmResource realm) {
        final ClientResource client = realm.clients().get(realm.clients().findByClientId(SecurityUtil.CLIENT_ID).getFirst().getId());
        final String clientUuid = client.toRepresentation().getId();
        final UserRepresentation admin = realm.users().searchByEmail(ADMIN, true).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Keycloak has no user " + ADMIN + "; import Spexregister's sample data first"));
        final Map<String, UserRepresentation> users = new LinkedHashMap<>();

        users.put(ADMIN, admin);
        users.put(OBSERVER, sampleUser(realm, OBSERVER, "Observer"));
        users.put(DIRECTEUR, sampleUser(realm, DIRECTEUR, "Directeur"));
        users.put(DIRECTEUR_2, sampleUser(realm, DIRECTEUR_2, "Directeur 2"));
        users.put(STEERING_GROUP, sampleUser(realm, STEERING_GROUP, "Steering group"));
        Arrays.stream(SeededCapability.values()).forEach(capability ->
                users.put(steeringGroupWith(capability), sampleUser(realm, steeringGroupWith(capability), "Steering group " + capability.name().toLowerCase(Locale.ROOT))));
        users.put(PARTICIPANT, sampleUser(realm, PARTICIPANT, "Participant"));
        users.put(HELPER, sampleUser(realm, HELPER, "Helper"));
        users.put(NO_ROLE, sampleUser(realm, NO_ROLE, "No role"));

        final Map<String, SamplePerson> people = new LinkedHashMap<>();

        users.forEach((email, user) -> {
            switch (email) {
                case ADMIN -> grantClientRoles(realm, client, clientUuid, user, "USER", "SUPER_ADMIN");
                case OBSERVER -> grantClientRoles(realm, client, clientUuid, user, "USER", "OBSERVER");
                default -> grantClientRoles(realm, client, clientUuid, user, "USER");
            }
            people.put(email, insertPerson(jdbcClient, user));
        });

        return people;
    }

    private UserRepresentation sampleUser(final RealmResource realm, final String email, final String firstName) {
        final List<UserRepresentation> existing = realm.users().searchByEmail(email, true);

        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(sampleUserPassword);
        credential.setTemporary(false);
        user.setUsername(email);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (final Response response = realm.users().create(user)) {
            if (response.getStatus() != HttpStatus.CREATED.value()) {
                throw new IllegalStateException("Could not create user " + email + " in Keycloak");
            }

            final String path = response.getLocation().getPath();

            return realm.users().get(path.substring(path.lastIndexOf('/') + 1)).toRepresentation();
        }
    }

    /**
     * Two revivals in every status. In the first, {@value #DIRECTEUR} leads {@value #STEERING_GROUP} and one steering
     * group member per capability. In the second, {@value #DIRECTEUR_2} leads alone, with {@value #INVITED} waiting
     * and {@value #STEERING_GROUP} removed.
     */
    private void createSampleRevivals(final JdbcClient jdbcClient,
                                      final List<SpexDto> spex,
                                      final Map<String, SamplePerson> people,
                                      final List<SampleGuide.Version> guide) {
        final List<SpexDto> shuffledSpex = new ArrayList<>(spex);
        final RevivalStatus[] statuses = RevivalStatus.values();
        final SamplePerson admin = people.get(ADMIN);

        final Set<String> taken = new HashSet<>();

        Collections.shuffle(shuffledSpex, rnd);

        for (int i = 0; i < NUMBER_OF_SAMPLES_REVIVALS; i++) {
            final boolean first = i < statuses.length;
            final RevivalStatus status = statuses[i % statuses.length];
            final List<RevivalStatus> path = statusPath(status);
            final @Nullable LocalDate plannedPremiere = premiere(status, first);
            final int plannedYear = plannedPremiere == null ? LocalDate.now().getYear() + 1 : plannedPremiere.getYear();
            int year = plannedYear;
            SpexDto revivedSpex = null;

            // One revival per spex and year: try every spex, then the year after (or before, for those already played)
            while (revivedSpex == null) {
                for (int offset = 0; offset < shuffledSpex.size() && revivedSpex == null; offset++) {
                    final SpexDto candidate = shuffledSpex.get((i + offset) % shuffledSpex.size());

                    if ((candidate.year() == null || candidate.year() <= year) && taken.add(candidate.id() + "/" + year)) {
                        revivedSpex = candidate;
                    }
                }
                if (revivedSpex == null) {
                    year += PLAYED.contains(status) ? -1 : 1;
                }
            }
            final LocalDate premiereDate = plannedPremiere == null ? null : plannedPremiere.plusYears(year - plannedYear);
            // An archived revival was archived some months after its shows; the others moved on recently
            final List<Instant> timeline = timeline(path.size(), status == RevivalStatus.ARCHIVED
                    ? Objects.requireNonNull(premiereDate).plusDays(rnd.nextInt(120, 180)).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : Instant.now().minus(rnd.nextInt(1, 10), ChronoUnit.DAYS));
            final long revivalId = insertRevival(jdbcClient, revivedSpex, year, premiereDate, status, timeline.getFirst(), admin);

            pinGuideVersion(jdbcClient, revivalId, guide, timeline.getFirst(), admin);

            if (premiereDate != null && path.contains(RevivalStatus.ORGANISING)) {
                createShows(jdbcClient, revivalId, premiereDate);
            }

            SamplePerson directeur = null;

            if (path.contains(RevivalStatus.APPROVED)) {
                final Instant approvedAt = timeline.get(path.indexOf(RevivalStatus.APPROVED));
                final SamplePerson steeringGroup = people.get(STEERING_GROUP);

                directeur = people.get(first ? DIRECTEUR : DIRECTEUR_2);
                insertMember(jdbcClient, revivalId, directeur.id(), null, RevivalRole.DIRECTEUR, Set.of(), approvedAt, admin.externalId());

                if (first) {
                    insertMember(jdbcClient, revivalId, steeringGroup.id(), null, RevivalRole.STEERING_GROUP, Set.of(), approvedAt, directeur.externalId());
                    for (final SeededCapability capability : SeededCapability.values()) {
                        insertMember(jdbcClient, revivalId, people.get(steeringGroupWith(capability)).id(), null, RevivalRole.STEERING_GROUP, EnumSet.of(capability), approvedAt, directeur.externalId());
                    }
                } else {
                    insertMember(jdbcClient, revivalId, null, INVITED, RevivalRole.STEERING_GROUP, Set.of(), approvedAt, directeur.externalId());

                    final long memberId = insertMember(jdbcClient, revivalId, steeringGroup.id(), null, RevivalRole.STEERING_GROUP, Set.of(), approvedAt, directeur.externalId());

                    jdbcClient
                            .sql("UPDATE revival_member SET removed_at = :removedAt, removed_by = :removedBy WHERE id = :id")
                            .param("removedAt", utc(timeline.getLast()))
                            .param("removedBy", directeur.id())
                            .param("id", memberId)
                            .update();
                }
            }

            for (int step = 1; step < path.size(); step++) {
                final RevivalStatus to = path.get(step);
                final SamplePerson changedBy = directeur == null || SUPER_ADMIN_STEPS.contains(to) ? admin : directeur;

                jdbcClient
                        .sql("""
                                INSERT INTO revival_status_change
                                    (revival_id, from_status, to_status, changed_by, changed_at)
                                VALUES
                                    (:revivalId, :fromStatus, :toStatus, :changedBy, :changedAt)
                                """)
                        .param("revivalId", revivalId)
                        .param("fromStatus", path.get(step - 1).name())
                        .param("toStatus", to.name())
                        .param("changedBy", changedBy.id())
                        .param("changedAt", utc(timeline.get(step)))
                        .update();
            }
        }
    }

    /**
     * When the revival was created and then moved through each step, oldest first and ending at {@code last}.
     */
    private List<Instant> timeline(final int steps, final Instant last) {
        final List<Instant> timeline = new ArrayList<>();
        Instant at = last;

        for (int step = 0; step < steps; step++) {
            timeline.addFirst(at);
            at = at.minus(rnd.nextInt(10, 60), ChronoUnit.DAYS).minus(rnd.nextInt(24 * 60), ChronoUnit.MINUTES);
        }
        return timeline;
    }

    /**
     * A premiere in line with the status: ahead while the revival is being organised or is open, behind once it has
     * been played. The second archived revival was played {@value #ANONYMIZED_YEARS_AGO} years ago.
     */
    private @Nullable LocalDate premiere(final RevivalStatus status, final boolean first) {
        final LocalDate today = LocalDate.now();

        return switch (status) {
            case PROPOSED, REJECTED -> null;
            case APPROVED -> LocalDate.of(today.getYear() + 1, rnd.nextInt(3, 6), rnd.nextInt(1, 29));
            case ORGANISING -> today.plusDays(rnd.nextInt(150, 330));
            case OPEN -> today.plusDays(rnd.nextInt(20, 90));
            case CLOSING -> today.minusDays(rnd.nextInt(20, 60));
            case SETTLING -> today.minusDays(rnd.nextInt(60, 150));
            case ARCHIVED ->
                    LocalDate.of(today.getYear() - (first ? 1 : ANONYMIZED_YEARS_AGO), rnd.nextInt(3, 6), rnd.nextInt(1, 29));
        };
    }

    private long insertRevival(final JdbcClient jdbcClient,
                               final SpexDto spex,
                               final int year,
                               final @Nullable LocalDate premiereDate,
                               final RevivalStatus status,
                               final Instant createdAt,
                               final SamplePerson createdBy) {
        final KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient
                .sql("""
                        INSERT INTO revival
                            (spex_id, year, premiere_date, status, created_by, created_at, version)
                        VALUES
                            (:spexId, :year, :premiereDate, :status, :createdBy, :createdAt, 0)
                        """)
                .param("spexId", spex.id())
                .param("year", year)
                .param("premiereDate", premiereDate)
                .param("status", status.name())
                .param("createdBy", createdBy.externalId())
                .param("createdAt", utc(createdAt))
                .update(keyHolder);

        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * One to four shows from the premiere at 19:00, a weekend one at 14:00, at one or two venues, the last one's time
     * and venue sometimes not decided yet.
     */
    private void createShows(final JdbcClient jdbcClient, final long revivalId, final LocalDate premiereDate) {
        final List<String> venues = new ArrayList<>(VENUES);
        final List<Integer> days = List.of(0, 1, 2, 7).subList(0, rnd.nextInt(1, 5));
        final int venueCount = rnd.nextInt(1, 3);
        final boolean lastUndecided = days.size() > 1 && rnd.nextBoolean();

        Collections.shuffle(venues, rnd);
        IntStream.range(0, days.size()).forEach(index -> jdbcClient
                .sql("INSERT INTO revival_show (revival_id, show_date, start_time, venue) VALUES (:revivalId, :showDate, :startTime, :venue)")
                .param("revivalId", revivalId)
                .param("showDate", premiereDate.plusDays(days.get(index)))
                .param("startTime", lastUndecided && index == days.size() - 1 ? null
                        : premiereDate.plusDays(days.get(index)).getDayOfWeek().getValue() >= 6 ? java.time.LocalTime.of(14, 0) : java.time.LocalTime.of(19, 0))
                .param("venue", lastUndecided && index == days.size() - 1 ? null : venues.get(index % venueCount))
                .update());
    }

    private record SamplePerson(long id, String externalId) {
    }
}
