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

package nu.fgv.revival.server.spex;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Spexregister's GraphQL API, as its {@code REVIVAL} role allows: spex and a spex's poster
 * from its REST API, which GraphQL cannot carry; and spexare: reading them by id, by spex and by name, the
 * task list and vocal parts, and writing what participants consent to: a new unpublished spexare for a pending
 * person, their {@value #HOME} address (email and phone) and their {@value #REGISTER} consent. Every spexare call says
 * whom the service account acts for in {@value #ACTING_PERSON_HEADER}. Names and contact details pass through to the
 * caller and are never logged. Failures are logged at most once a minute, without the token or the response body.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SpexregisterClient {

    /**
     * At most this many spexare per {@code spexareByIds} call, as Spexregister allows.
     */
    public static final int MAX_SPEXARE = 500;
    /**
     * The address type that holds a person's email and phone.
     */
    public static final String HOME = "HOME";
    /**
     * The consent to being kept in FGV's register.
     */
    public static final String REGISTER = "REGISTER";
    /**
     * The membership type FGV's memberships are recorded with.
     */
    public static final String FGV_MEMBERSHIP = "FGV";
    /**
     * The vocal type for an actor whose vocal part is not known.
     */
    public static final String UNKNOWN_VOCAL = "UNKNOWN";
    static final int MAX_SPEX = 500;
    static final int MAX_TASKS = 500;
    static final int MAX_SEARCH = 50;
    static final String ACTING_PERSON_HEADER = "X-Acting-Person";
    static final int ACTING_PERSON_MAX_LENGTH = 255;
    private static final String SPEXARE_FIELDS = "id firstName lastName nickName graduation deceased";
    private static final String SPEXARE_BY_IDS = """
            query SpexareByIds($ids: [ID!]!) {
                spexareByIds(ids: $ids) {
                    %s
                    addresses { id type { id } emailAddress phone phoneMobile streetAddress postalCode city }
                    consents { id value type { id } }
                    activities { id spexActivity { id spex { id year title revival } } }
                }
            }""".formatted(SPEXARE_FIELDS);
    private static final String SPEXARE_BY_SPEX = """
            query SpexareBySpex($spexId: ID!) {
                spexareBySpex(spexId: $spexId) { %s }
            }""".formatted(SPEXARE_FIELDS);
    private static final String SPEXARE_SEARCH = """
            query SpexareSearch($q: String!, $first: Int!) {
                spexareSearchPaged(first: $first, q: $q) { edges { node { %s } } totalCount }
            }""".formatted(SPEXARE_FIELDS);
    private static final String SPEX_FAMILY = """
            query SpexFamily($id: ID!) {
                spex(id: $id) { id revivals { id year } }
            }""";
    private static final String TASKS = """
            query Tasks($first: Int!) {
                taskPaged(first: $first) { edges { node { id name category { id name actorPresent } } } totalCount }
            }""";
    private static final String VOCAL_PARTS = """
            query VocalParts {
                typesOfType(type: VOCAL) { id label }
            }""";
    private static final String SPEXARE_CREATE = """
            mutation SpexareCreate($input: SpexareCreate!) {
                spexareCreate(input: $input) { id }
            }""";
    private static final String ADDRESS_CREATE = """
            mutation AddressCreate($spexareId: ID!, $typeId: ID!, $input: AddressCreate!) {
                addressCreate(spexareId: $spexareId, typeId: $typeId, input: $input) { id }
            }""";
    private static final String ADDRESS_UPDATE = """
            mutation AddressUpdate($spexareId: ID!, $typeId: ID!, $input: AddressUpdate!) {
                addressUpdate(spexareId: $spexareId, typeId: $typeId, input: $input) { id }
            }""";
    private static final String CONSENT_CREATE = """
            mutation ConsentCreate($spexareId: ID!, $typeId: ID!, $input: ConsentCreate!) {
                consentCreate(spexareId: $spexareId, typeId: $typeId, input: $input) { id }
            }""";
    private static final String CONSENT_UPDATE = """
            mutation ConsentUpdate($spexareId: ID!, $typeId: ID!, $input: ConsentUpdate!) {
                consentUpdate(spexareId: $spexareId, typeId: $typeId, input: $input) { id }
            }""";

    private static final String SPEXARE_ACTIVITIES = """
            query SpexareActivities($ids: [ID!]!) {
                spexareByIds(ids: $ids) {
                    id
                    activities { id spexActivity { id spex { id } } taskActivities { id task { id } actors { id role vocal { id } } } }
                }
            }""";
    private static final String SPEX_REVIVALS = """
            query SpexRevivals($id: ID!) {
                spex(id: $id) { id revivals { id year } }
            }""";
    private static final String SPEX_REVIVAL_CREATE = """
            mutation SpexRevivalCreate($spexId: ID!, $year: Year!) {
                spexRevivalCreate(spexId: $spexId, year: $year) { id year }
            }""";
    private static final String ACTIVITY_CREATE = """
            mutation ActivityCreate($spexareId: ID!) {
                activityCreate(spexareId: $spexareId) { id }
            }""";
    private static final String SPEX_ACTIVITY_CREATE = """
            mutation SpexActivityCreate($spexareId: ID!, $activityId: ID!, $spexId: ID!) {
                spexActivityCreate(spexareId: $spexareId, activityId: $activityId, spexId: $spexId) { id }
            }""";
    private static final String TASK_ACTIVITY_CREATE = """
            mutation TaskActivityCreate($spexareId: ID!, $activityId: ID!, $taskId: ID!) {
                taskActivityCreate(spexareId: $spexareId, activityId: $activityId, taskId: $taskId) { id }
            }""";
    private static final String ACTOR_CREATE = """
            mutation ActorCreate($spexareId: ID!, $activityId: ID!, $taskActivityId: ID!, $vocalId: ID!, $input: ActorCreate!) {
                actorCreate(spexareId: $spexareId, activityId: $activityId, taskActivityId: $taskActivityId, vocalId: $vocalId, input: $input) { id }
            }""";
    private static final String MEMBERSHIP_CREATE = """
            mutation MembershipCreate($spexareId: ID!, $typeId: ID!, $input: MembershipCreate!) {
                membershipCreate(spexareId: $spexareId, typeId: $typeId, input: $input) { id }
            }""";
    private static final String ORIGINAL_SPEX = """
            query OriginalSpex($first: Int!) {
                spexPaged(first: $first, filter: "parent:NULL", sort: ["year"], direction: ASC) {
                    edges { node { id year title } }
                    totalCount
                }
            }""";
    private static final String SPEX_BY_ID = """
            query SpexById($id: ID!) {
                spex(id: $id) { id year title revival }
            }""";
    private static final String POSTER = "/api/spex/{id}/poster";
    private static final long LOG_INTERVAL_MILLIS = 60_000;

    private final HttpSyncGraphQlClient graphQlClient;
    private final RestClient restClient;
    private final AtomicLong lastErrorLogged = new AtomicLong();

    public SpexregisterClient(final HttpSyncGraphQlClient spexregisterGraphQlClient, final RestClient spexregisterRestClient) {
        this.graphQlClient = spexregisterGraphQlClient;
        this.restClient = spexregisterRestClient;
    }

    static @Nullable Integer year(final @Nullable String year) {
        try {
            return year == null ? null : Integer.valueOf(year);
        } catch (final NumberFormatException _) {
            return null;
        }
    }

    /**
     * The value of {@value #ACTING_PERSON_HEADER}: at most {@value #ACTING_PERSON_MAX_LENGTH} characters,
     * percent-encoded as UTF-8, which Spexregister decodes.
     */
    static String actingPersonHeader(final String actingPerson) {
        final String trimmed = actingPerson.strip();
        final String limited = trimmed.length() <= ACTING_PERSON_MAX_LENGTH ? trimmed : trimmed.substring(0, ACTING_PERSON_MAX_LENGTH);

        return UriUtils.encode(limited, StandardCharsets.UTF_8);
    }

    private static boolean notFound(final ClientGraphQlResponse response) {
        return !response.getErrors().isEmpty()
                && response.getErrors().stream().allMatch(error -> ErrorType.NOT_FOUND.equals(error.getErrorType()));
    }

    private static void requireValid(final ClientGraphQlResponse response) {
        if (!response.getErrors().isEmpty()) {
            throw new SpexregisterCallException("Response has errors", classify(response));
        }
        if (!response.isValid()) {
            throw new SpexregisterCallException("Response has no data");
        }
    }

    /**
     * What Spexregister's errors mean for a write: it does not know something, it already has it, or it refused.
     */
    private static String classify(final ClientGraphQlResponse response) {
        final List<String> classifications = response.getErrors().stream()
                .map(error -> String.valueOf(error.getExtensions().getOrDefault("classification", error.getErrorType())))
                .toList();

        if (classifications.stream().allMatch(ErrorType.NOT_FOUND.name()::equals)) {
            return SpexregisterCallException.NOT_FOUND;
        }
        if (classifications.contains("CONFLICT")) {
            return SpexregisterCallException.EXISTS;
        }
        return SpexregisterCallException.REFUSED;
    }

    /**
     * Every original spex, i.e. not a revival recorded in Spexregister.
     */
    public List<SpexDto> originalSpex() {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(ORIGINAL_SPEX)
                    .variable("first", MAX_SPEX)
                    .executeSync();

            requireValid(response);

            final SpexPage page = response.field("spexPaged").toEntity(SpexPage.class);

            if (page == null) {
                throw new SpexregisterCallException("No spexPaged in response");
            }
            if (page.totalCount() != null && page.totalCount() > MAX_SPEX) {
                log.warn("Spexregister has {} original spex; only the first {} are offered", page.totalCount(), MAX_SPEX);
            }

            return page.edges().stream()
                    .map(SpexEdge::node)
                    .map(node -> new SpexDto(Long.valueOf(node.id()), node.title(), year(node.year()), true))
                    .toList();
        });
    }

    /**
     * One spex, with whether it is a revival, or empty when Spexregister does not know it.
     */
    public Optional<SpexNode> spexById(final Long id) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(SPEX_BY_ID)
                    .variable("id", id)
                    .executeSync();
            final ClientResponseField field = response.field("spex");

            if (field.getValue() == null) {
                final boolean notFound = !response.getErrors().isEmpty()
                        && response.getErrors().stream().allMatch(error -> ErrorType.NOT_FOUND.equals(error.getErrorType()));

                if (notFound || response.getErrors().isEmpty()) {
                    return Optional.empty();
                }
                throw new SpexregisterCallException("spex failed");
            }

            return Optional.ofNullable(field.toEntity(SpexNode.class));
        });
    }

    /**
     * A spex's poster, or empty when it has none or Spexregister does not know the spex.
     */
    public Optional<Poster> poster(final Long id) {
        return call(() -> {
            final ResponseEntity<byte[]> response = restClient.get()
                    .uri(POSTER, id)
                    .header("X-API-Version", "1.0")
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (_, _) -> {
                    })
                    .toEntity(byte[].class);
            final MediaType contentType = response.getHeaders().getContentType();

            if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value() || response.getBody() == null || contentType == null) {
                return Optional.empty();
            }
            return Optional.of(new Poster(response.getBody(), contentType.toString()));
        });
    }

    private HttpSyncGraphQlClient actingFor(final String actingPerson) {
        return graphQlClient.mutate().header(ACTING_PERSON_HEADER, actingPersonHeader(actingPerson)).build();
    }

    /**
     * The spexare with these ids that Spexregister lets the service account read, with their addresses, consents and
     * spex activities, at most {@value #MAX_SPEXARE}.
     */
    public List<Spexare> spexareByIds(final Collection<Long> ids, final String actingPerson) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_SPEXARE) {
            throw new IllegalArgumentException("At most %d spexare per call".formatted(MAX_SPEXARE));
        }
        return call(() -> {
            final ClientGraphQlResponse response = actingFor(actingPerson).document(SPEXARE_BY_IDS)
                    .variable("ids", ids.stream().map(String::valueOf).toList())
                    .executeSync();

            requireValid(response);
            return Objects.requireNonNullElse(response.field("spexareByIds").toEntityList(Spexare.class), List.<Spexare>of());
        });
    }

    /**
     * The participants of a spex and its revivals recorded in Spexregister, by last and first name; empty when
     * Spexregister does not know the spex.
     */
    public List<Spexare> spexareBySpex(final Long spexId, final String actingPerson) {
        return call(() -> {
            final ClientGraphQlResponse response = actingFor(actingPerson).document(SPEXARE_BY_SPEX)
                    .variable("spexId", spexId)
                    .executeSync();

            if (notFound(response)) {
                return List.of();
            }
            requireValid(response);
            return Objects.requireNonNullElse(response.field("spexareBySpex").toEntityList(Spexare.class), List.<Spexare>of());
        });
    }

    /**
     * Spexare matching a free-text query, at most {@value #MAX_SEARCH}, best match first.
     */
    public List<Spexare> searchSpexare(final String query, final String actingPerson) {
        return call(() -> {
            final ClientGraphQlResponse response = actingFor(actingPerson).document(SPEXARE_SEARCH)
                    .variable("q", query)
                    .variable("first", MAX_SEARCH)
                    .executeSync();

            requireValid(response);

            final SpexarePage page = response.field("spexareSearchPaged").toEntity(SpexarePage.class);

            return page == null || page.edges() == null ? List.of()
                    : page.edges().stream().filter(Objects::nonNull).map(SpexareEdge::node).filter(Objects::nonNull).toList();
        });
    }

    /**
     * The ids of a spex and of its revivals recorded in Spexregister; empty when Spexregister does not know it.
     */
    public List<Long> spexFamily(final Long spexId) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(SPEX_FAMILY)
                    .variable("id", spexId)
                    .executeSync();

            if (notFound(response) || (response.getErrors().isEmpty() && response.field("spex").getValue() == null)) {
                return List.of();
            }
            requireValid(response);

            final SpexFamily family = response.field("spex").toEntity(SpexFamily.class);

            if (family == null) {
                return List.of();
            }
            return java.util.stream.Stream.concat(java.util.stream.Stream.of(family.id()),
                            family.revivals() == null ? java.util.stream.Stream.empty() : family.revivals().stream().filter(Objects::nonNull).map(SpexRevivalNode::id))
                    .map(Long::valueOf)
                    .toList();
        });
    }

    /**
     * Spexregister's tasks with their categories, at most {@value #MAX_TASKS}.
     */
    public List<Task> tasks() {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(TASKS)
                    .variable("first", MAX_TASKS)
                    .executeSync();

            requireValid(response);

            final TaskPage page = response.field("taskPaged").toEntity(TaskPage.class);

            if (page == null || page.edges() == null) {
                return List.of();
            }
            if (page.totalCount() != null && page.totalCount() > MAX_TASKS) {
                log.warn("Spexregister has {} tasks; only the first {} are offered", page.totalCount(), MAX_TASKS);
            }
            return page.edges().stream().filter(Objects::nonNull).map(TaskEdge::node).filter(Objects::nonNull).toList();
        });
    }

    /**
     * Spexregister's vocal types, e.g. {@code B1} or {@code S2}.
     */
    public List<TypeNode> vocalParts() {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(VOCAL_PARTS).executeSync();

            requireValid(response);
            return Objects.requireNonNullElse(response.field("typesOfType").toEntityList(TypeNode.class), List.<TypeNode>of());
        });
    }

    /**
     * Creates an unpublished spexare who is not deceased, and returns its id.
     */
    public Long createSpexare(final String firstName, final String lastName, final String actingPerson) {
        return call(() -> {
            final Map<String, Object> input = new LinkedHashMap<>();

            input.put("firstName", firstName);
            input.put("lastName", lastName);
            input.put("deceased", false);
            input.put("published", false);

            final ClientGraphQlResponse response = actingFor(actingPerson).document(SPEXARE_CREATE)
                    .variable("input", input)
                    .executeSync();

            requireValid(response);

            final IdNode created = response.field("spexareCreate").toEntity(IdNode.class);

            if (created == null) {
                throw new SpexregisterCallException("No spexareCreate in response");
            }
            return Long.valueOf(created.id());
        });
    }

    /**
     * Writes the spexare's {@value #HOME} address: updates the one with {@code addressId}, or creates it when null.
     */
    public void saveHomeAddress(final Long spexareId, final @Nullable String addressId, final AddressInput address, final String actingPerson) {
        call(() -> {
            final Map<String, Object> input = new LinkedHashMap<>();

            if (addressId != null) {
                input.put("id", addressId);
            }
            input.put("emailAddress", address.email());
            input.put("phoneMobile", address.phone());
            input.put("streetAddress", address.streetAddress());
            input.put("postalCode", address.postalCode());
            input.put("city", address.city());

            final ClientGraphQlResponse response = actingFor(actingPerson).document(addressId == null ? ADDRESS_CREATE : ADDRESS_UPDATE)
                    .variable("spexareId", spexareId)
                    .variable("typeId", HOME)
                    .variable("input", input)
                    .executeSync();

            requireValid(response);
            return null;
        });
    }

    /**
     * Records the spexare's {@value #REGISTER} consent: updates the one with {@code consentId}, or creates it when
     * null.
     */
    public void saveRegisterConsent(final Long spexareId, final @Nullable String consentId, final boolean value, final String actingPerson) {
        call(() -> {
            final Map<String, Object> input = new LinkedHashMap<>();

            if (consentId != null) {
                input.put("id", consentId);
            }
            input.put("value", value);

            final ClientGraphQlResponse response = actingFor(actingPerson).document(consentId == null ? CONSENT_CREATE : CONSENT_UPDATE)
                    .variable("spexareId", spexareId)
                    .variable("typeId", REGISTER)
                    .variable("input", input)
                    .executeSync();

            requireValid(response);
            return null;
        });
    }

    /**
     * The spexare's activities with their spex, tasks and actors, read fresh (never cached): the sync decides from them
     * what already exists. At most {@value #MAX_SPEXARE} ids.
     */
    public List<SpexareActivities> spexareActivities(final Collection<Long> ids, final String actingPerson) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_SPEXARE) {
            throw new IllegalArgumentException("At most %d spexare per call".formatted(MAX_SPEXARE));
        }
        return call(() -> {
            final ClientGraphQlResponse response = actingFor(actingPerson).document(SPEXARE_ACTIVITIES)
                    .variable("ids", ids.stream().map(String::valueOf).toList())
                    .executeSync();

            requireValid(response);
            return Objects.requireNonNullElse(response.field("spexareByIds").toEntityList(SpexareActivities.class), List.<SpexareActivities>of());
        });
    }

    /**
     * The revivals Spexregister records for a spex, read fresh; empty when it does not know the spex.
     */
    public List<SpexRevival> spexRevivals(final Long spexId) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(SPEX_REVIVALS)
                    .variable("id", spexId)
                    .executeSync();

            if (notFound(response) || (response.getErrors().isEmpty() && response.field("spex").getValue() == null)) {
                return List.of();
            }
            requireValid(response);

            final SpexFamily family = response.field("spex").toEntity(SpexFamily.class);

            return family == null || family.revivals() == null ? List.of()
                    : family.revivals().stream().filter(Objects::nonNull).map(node -> new SpexRevival(Long.valueOf(node.id()), year(node.year()))).toList();
        });
    }

    /**
     * Records a revival of the spex in Spexregister and returns its id.
     */
    public Long createSpexRevival(final Long spexId, final int year, final String actingPerson) {
        return created(actingFor(actingPerson).document(SPEX_REVIVAL_CREATE)
                .variable("spexId", spexId)
                .variable("year", String.valueOf(year)), "spexRevivalCreate");
    }

    /**
     * A new, empty activity of the spexare.
     */
    public Long createActivity(final Long spexareId, final String actingPerson) {
        return created(actingFor(actingPerson).document(ACTIVITY_CREATE).variable("spexareId", spexareId), "activityCreate");
    }

    /**
     * Ties the activity to a spex (here, Spexregister's revival).
     */
    public Long createSpexActivity(final Long spexareId, final Long activityId, final Long spexId, final String actingPerson) {
        return created(actingFor(actingPerson).document(SPEX_ACTIVITY_CREATE)
                .variable("spexareId", spexareId)
                .variable("activityId", activityId)
                .variable("spexId", spexId), "spexActivityCreate");
    }

    public Long createTaskActivity(final Long spexareId, final Long activityId, final String taskId, final String actingPerson) {
        return created(actingFor(actingPerson).document(TASK_ACTIVITY_CREATE)
                .variable("spexareId", spexareId)
                .variable("activityId", activityId)
                .variable("taskId", taskId), "taskActivityCreate");
    }

    public Long createActor(final Long spexareId, final Long activityId, final Long taskActivityId, final String vocalId, final @Nullable String role,
                            final String actingPerson) {
        final Map<String, Object> input = new LinkedHashMap<>();

        input.put("role", role);
        return created(actingFor(actingPerson).document(ACTOR_CREATE)
                .variable("spexareId", spexareId)
                .variable("activityId", activityId)
                .variable("taskActivityId", taskActivityId)
                .variable("vocalId", vocalId)
                .variable("input", input), "actorCreate");
    }

    /**
     * Records the spexare's {@value #FGV_MEMBERSHIP} membership for the year. Spexregister refuses a second one for the
     * same year with {@link SpexregisterCallException#EXISTS}.
     */
    public Long createMembership(final Long spexareId, final int year, final String actingPerson) {
        return created(actingFor(actingPerson).document(MEMBERSHIP_CREATE)
                .variable("spexareId", spexareId)
                .variable("typeId", FGV_MEMBERSHIP)
                .variable("input", Map.of("year", String.valueOf(year))), "membershipCreate");
    }

    private Long created(final org.springframework.graphql.client.GraphQlClient.RequestSpec request, final String field) {
        return call(() -> {
            final ClientGraphQlResponse response = request.executeSync();

            requireValid(response);

            final IdNode created = response.field(field).toEntity(IdNode.class);

            if (created == null) {
                throw new SpexregisterCallException("No %s in response".formatted(field), SpexregisterCallException.REFUSED);
            }
            return Long.valueOf(created.id());
        });
    }

    private <T> T call(final Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (final RuntimeException e) {
            final long now = System.currentTimeMillis();
            final long last = lastErrorLogged.get();

            if (now - last > LOG_INTERVAL_MILLIS && lastErrorLogged.compareAndSet(last, now)) {
                log.warn("Spexregister is unavailable: {}", e.getClass().getSimpleName());
            }
            throw e instanceof final SpexregisterCallException known ? known : new SpexregisterCallException(e);
        }
    }

    record SpexPage(List<SpexEdge> edges, @Nullable Integer totalCount) {
    }

    record SpexEdge(SpexNode node) {
    }

    /**
     * A spex as Spexregister returns it.
     */
    public record SpexNode(String id, @Nullable String year, @Nullable String title, @Nullable Boolean revival) {
    }

    /**
     * A spexare as Spexregister returns it. Addresses, consents and activities only from {@link #spexareByIds}.
     */
    public record Spexare(String id, String firstName, String lastName, @Nullable String nickName,
                          @Nullable String graduation,
                          @Nullable Boolean deceased, @Nullable List<Address> addresses,
                          @Nullable List<Consent> consents,
                          @Nullable List<Activity> activities) {

        public boolean isDeceased() {
            return Boolean.TRUE.equals(deceased);
        }

        /**
         * The {@value #HOME} address, if any.
         */
        public Optional<Address> home() {
            return addresses == null ? Optional.empty()
                    : addresses.stream().filter(Objects::nonNull).filter(address -> address.type() != null && HOME.equals(address.type().id())).findFirst();
        }

        /**
         * The {@value #REGISTER} consent, if any.
         */
        public Optional<Consent> registerConsent() {
            return consents == null ? Optional.empty()
                    : consents.stream().filter(Objects::nonNull).filter(consent -> consent.type() != null && REGISTER.equals(consent.type().id())).findFirst();
        }

        /**
         * The spex of their activities, as Spexregister records them.
         */
        public List<SpexNode> spex() {
            return activities == null ? List.of()
                    : activities.stream().filter(Objects::nonNull).map(Activity::spexActivity).filter(Objects::nonNull).map(SpexActivity::spex)
                    .filter(Objects::nonNull).toList();
        }
    }

    public record Address(String id, @Nullable TypeNode type, @Nullable String emailAddress, @Nullable String phone,
                          @Nullable String phoneMobile,
                          @Nullable String streetAddress, @Nullable String postalCode, @Nullable String city) {
    }

    public record Consent(String id, @Nullable Boolean value, @Nullable TypeNode type) {
    }

    public record Activity(String id, @Nullable SpexActivity spexActivity) {
    }

    public record SpexActivity(String id, @Nullable SpexNode spex) {
    }

    /**
     * One of Spexregister's types, e.g. an address type or a vocal part.
     */
    public record TypeNode(String id, @Nullable String label) {
    }

    /**
     * A task and its category; {@code actorPresent} marks the ensemble's categories.
     */
    public record Task(String id, String name, @Nullable TaskCategory category) {
    }

    public record TaskCategory(String id, @Nullable String name, @Nullable Boolean actorPresent) {
    }

    /**
     * What is written to a {@value #HOME} address.
     */
    public record AddressInput(@Nullable String email, @Nullable String phone, @Nullable String streetAddress,
                               @Nullable String postalCode,
                               @Nullable String city) {
    }

    record SpexarePage(@Nullable List<SpexareEdge> edges, @Nullable Integer totalCount) {
    }

    record SpexareEdge(@Nullable Spexare node) {
    }

    record TaskPage(@Nullable List<TaskEdge> edges, @Nullable Integer totalCount) {
    }

    record TaskEdge(@Nullable Task node) {
    }

    record SpexFamily(String id, @Nullable List<SpexRevivalNode> revivals) {
    }

    record SpexRevivalNode(String id, @Nullable String year) {
    }

    record IdNode(String id) {
    }

    /**
     * A spexare's activities, for the sync.
     */
    public record SpexareActivities(String id, @Nullable List<ActivityDetail> activities) {

        public List<ActivityDetail> all() {
            return activities == null ? List.of() : activities.stream().filter(Objects::nonNull).toList();
        }
    }

    public record ActivityDetail(String id, @Nullable SpexActivityRef spexActivity,
                                 @Nullable List<TaskActivity> taskActivities) {

        public @Nullable String spexId() {
            return spexActivity == null || spexActivity.spex() == null ? null : spexActivity.spex().id();
        }

        public List<TaskActivity> tasks() {
            return taskActivities == null ? List.of() : taskActivities.stream().filter(Objects::nonNull).toList();
        }
    }

    public record SpexActivityRef(String id, @Nullable IdRef spex) {
    }

    public record TaskActivity(String id, @Nullable IdRef task, @Nullable List<Actor> actors) {

        public List<Actor> allActors() {
            return actors == null ? List.of() : actors.stream().filter(Objects::nonNull).toList();
        }
    }

    public record Actor(String id, @Nullable String role, @Nullable IdRef vocal) {
    }

    public record IdRef(String id) {
    }

    /**
     * A revival of a spex as Spexregister records it.
     */
    public record SpexRevival(Long id, @Nullable Integer year) {
    }

    /**
     * A poster image as Spexregister stores it.
     */
    public record Poster(byte[] content, String contentType) {
    }

    /**
     * Spexregister could not be reached or answered with an error.
     */
    public static class SpexregisterCallException extends RuntimeException {

        /**
         * Spexregister could not be reached or did not answer properly.
         */
        public static final String UNAVAILABLE = "SPEXREGISTER_UNAVAILABLE";
        /**
         * Spexregister answered with an error.
         */
        public static final String REFUSED = "REFUSED";
        /**
         * Spexregister does not know something the call referred to.
         */
        public static final String NOT_FOUND = "NOT_FOUND";
        /**
         * Spexregister already has what the call would create.
         */
        public static final String EXISTS = "EXISTS";

        private final String code;

        SpexregisterCallException(final String message) {
            this(message, UNAVAILABLE);
        }

        SpexregisterCallException(final String message, final String code) {
            super(message);
            this.code = code;
        }

        SpexregisterCallException(final Throwable cause) {
            super(cause.getMessage(), cause);
            this.code = UNAVAILABLE;
        }

        /**
         * One of {@link #UNAVAILABLE}, {@link #REFUSED}, {@link #NOT_FOUND} and {@link #EXISTS}.
         */
        public String code() {
            return code;
        }
    }
}
