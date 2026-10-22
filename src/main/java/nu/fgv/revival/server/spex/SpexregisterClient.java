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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Reads spex from Spexregister's GraphQL API with the two queries in contracts/spexregister.md. Nothing else is
 * called and nothing is written. Failures are logged at most once a minute, without the token or the response body.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Slf4j
@Component
public class SpexregisterClient {

    static final int MAX_SPEX = 500;

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
    private static final long LOG_INTERVAL_MILLIS = 60_000;

    private final HttpSyncGraphQlClient graphQlClient;
    private final AtomicLong lastErrorLogged = new AtomicLong();

    public SpexregisterClient(final HttpSyncGraphQlClient spexregisterGraphQlClient) {
        this.graphQlClient = spexregisterGraphQlClient;
    }

    static @Nullable Integer year(final @Nullable String year) {
        try {
            return year == null ? null : Integer.valueOf(year);
        } catch (final NumberFormatException _) {
            return null;
        }
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

    private static void requireValid(final ClientGraphQlResponse response) {
        if (!response.isValid() || !response.getErrors().isEmpty()) {
            throw new SpexregisterCallException("Response has errors");
        }
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
            throw new SpexregisterCallException(e);
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
     * Spexregister could not be reached or answered with an error.
     */
    public static class SpexregisterCallException extends RuntimeException {

        SpexregisterCallException(final String message) {
            super(message);
        }

        SpexregisterCallException(final Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
