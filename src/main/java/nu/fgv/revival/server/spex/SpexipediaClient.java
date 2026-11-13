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

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Spexipedia's GraphQL API, the source of spex, revivals and tasks: read without restrictions, and revivals created with
 * {@code REVIVAL} on {@code fgv-spexipedia} (Spexipedia's spec, 5.6). Posters come from Spexipedia's public image
 * addresses. Failures are {@link SpexipediaCallException}s, which the callers of Spexregister's client also handle.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SpexipediaClient {

    private static final String ORIGINAL_SPEX = """
            query OriginalSpex {
                spexAll { id year title revival }
            }""";
    private static final String SPEX_BY_ID = """
            query SpexById($id: ID!) {
                spex(id: $id) { id year title revival poster { pageJpegUrl } }
            }""";
    private static final String SPEX_FAMILY = """
            query SpexFamily($id: ID!) {
                spex(id: $id) { id revivals { id year } }
            }""";
    private static final String SPEX_REVIVALS = """
            query SpexRevivals($id: ID!) {
                spex(id: $id) { id revivals { id year } }
            }""";
    private static final String TASKS = """
            query Tasks {
                tasks { id name category { id name actorPresent } }
            }""";
    private static final String SPEX_REVIVAL_CREATE = """
            mutation SpexRevivalCreate($parentId: ID!, $year: String!) {
                spexRevivalCreate(parentId: $parentId, year: $year) { id }
            }""";

    private final HttpSyncGraphQlClient graphQlClient;
    private final RestClient mediaClient;

    public SpexipediaClient(@Qualifier("spexipediaGraphQlClient") final HttpSyncGraphQlClient graphQlClient,
                            @Qualifier("spexipediaMediaRestClient") final RestClient mediaClient) {
        this.graphQlClient = graphQlClient;
        this.mediaClient = mediaClient;
    }

    private static <T> T call(final Supplier<T> action) {
        try {
            return action.get();
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            throw e;
        } catch (final RuntimeException e) {
            if (e instanceof RestClientException || e.getClass().getName().startsWith("org.springframework.graphql")) {
                throw new SpexipediaCallException(e.getMessage() == null ? e.getClass().getName() : e.getMessage(), SpexipediaCallException.SPEXIPEDIA_UNAVAILABLE);
            }
            throw e;
        }
    }

    private static void requireValid(final ClientGraphQlResponse response) {
        if (!response.isValid() || !response.getErrors().isEmpty()) {
            throw new SpexipediaCallException("Spexipedia answered with errors: " + response.getErrors(), SpexipediaCallException.REFUSED);
        }
    }

    static @Nullable Integer year(final @Nullable String year) {
        try {
            return year == null ? null : Integer.valueOf(year);
        } catch (final NumberFormatException _) {
            return null;
        }
    }

    /**
     * Every original spex, i.e. not a revival.
     */
    public List<SpexDto> originalSpex() {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(ORIGINAL_SPEX).executeSync();

            requireValid(response);
            return response.field("spexAll").toEntityList(SpexregisterClient.SpexNode.class).stream()
                    .filter(Objects::nonNull)
                    .filter(node -> !Boolean.TRUE.equals(node.revival()))
                    .map(node -> new SpexDto(node.id(), node.title(), year(node.year()), true))
                    .toList();
        });
    }

    /**
     * One spex, with whether it is a revival, or empty when Spexipedia does not know it.
     */
    public Optional<SpexregisterClient.SpexNode> spexById(final String id) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(SPEX_BY_ID).variable("id", id).executeSync();

            requireValid(response);
            return Optional.ofNullable(response.field("spex").toEntity(SpexregisterClient.SpexNode.class));
        });
    }

    /**
     * A spex's poster (a revival without one has its original's), or empty when it has none or Spexipedia does not
     * know the spex.
     */
    public Optional<SpexregisterClient.Poster> poster(final String id) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(SPEX_BY_ID).variable("id", id).executeSync();

            requireValid(response);

            final PosterSpex spex = response.field("spex").toEntity(PosterSpex.class);

            if (spex == null || spex.poster() == null || spex.poster().pageJpegUrl() == null) {
                return Optional.<SpexregisterClient.Poster>empty();
            }

            final ResponseEntity<byte[]> image = mediaClient.get().uri(URI.create(spex.poster().pageJpegUrl())).retrieve().toEntity(byte[].class);
            final MediaType contentType = image.getHeaders().getContentType();

            return image.getBody() == null || contentType == null ? Optional.<SpexregisterClient.Poster>empty()
                    : Optional.of(new SpexregisterClient.Poster(image.getBody(), contentType.toString()));
        });
    }

    /**
     * The spex and its revivals' ids; empty when Spexipedia does not know it.
     */
    public List<String> spexFamily(final String spexId) {
        return call(() -> {
            final Family family = family(SPEX_FAMILY, spexId);

            if (family == null) {
                return List.<String>of();
            }

            final List<String> ids = new java.util.ArrayList<>();

            ids.add(family.id());
            family.allRevivals().forEach(revival -> ids.add(revival.id()));
            return ids;
        });
    }

    /**
     * The revivals Spexipedia has of a spex, read fresh; empty when it does not know the spex.
     */
    public List<SpexregisterClient.SpexRevival> spexRevivals(final String spexId) {
        return call(() -> {
            final Family family = family(SPEX_REVIVALS, spexId);

            return family == null ? List.<SpexregisterClient.SpexRevival>of()
                    : family.allRevivals().stream().map(revival -> new SpexregisterClient.SpexRevival(revival.id(), year(revival.year()))).toList();
        });
    }

    private @Nullable Family family(final String document, final String spexId) {
        final ClientGraphQlResponse response = graphQlClient.document(document).variable("id", spexId).executeSync();

        requireValid(response);
        return response.field("spex").toEntity(Family.class);
    }

    /**
     * Spexipedia's tasks with their categories.
     */
    public List<SpexregisterClient.Task> tasks() {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(TASKS).executeSync();

            requireValid(response);
            return response.field("tasks").toEntityList(SpexregisterClient.Task.class).stream().filter(Objects::nonNull).toList();
        });
    }

    /**
     * Creates a revival of the original spex in the year in Spexipedia and returns its id. Spexregister gets it from
     * Spexipedia's change feed.
     */
    public String createSpexRevival(final String spexId, final int year) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(SPEX_REVIVAL_CREATE)
                    .variable("parentId", spexId).variable("year", String.valueOf(year)).executeSync();

            requireValid(response);
            return Objects.requireNonNull(response.field("spexRevivalCreate.id").toEntity(String.class));
        });
    }

    record RevivalNode(String id, @Nullable String year) {
    }

    record Family(String id, @Nullable List<RevivalNode> revivals) {

        List<RevivalNode> allRevivals() {
            return revivals == null ? List.of() : revivals.stream().filter(Objects::nonNull).toList();
        }
    }

    record PosterUrl(@Nullable String pageJpegUrl) {
    }

    record PosterSpex(String id, @Nullable PosterUrl poster) {
    }

    /**
     * Spexipedia could not be reached or answered with an error.
     */
    public static class SpexipediaCallException extends SpexregisterClient.SpexregisterCallException {

        /**
         * Spexipedia could not be reached or did not answer properly.
         */
        public static final String SPEXIPEDIA_UNAVAILABLE = "SPEXIPEDIA_UNAVAILABLE";

        SpexipediaCallException(final String message, final String code) {
            super(message, code);
        }
    }
}
