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

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * More concurrent requests than the pool has connections, each resolving the caller inside a transaction. Resolving
 * there once held a connection while waiting for another and exhausted the pool.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class CallerConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int REQUESTS = 25;
    private static final String NEWCOMER = "newcomer" + DOMAIN;

    @Test
    void manyConcurrentRequestsFromANewUserAllSucceed() {
        try (Keycloak admin = keycloakAdmin()) {
            createUser(realm(admin), NEWCOMER, "Nils", "Nykomling", CLIENT_ID, List.of("USER"));
        }
        graphQl(NEWCOMER);

        final long started = System.nanoTime();
        final List<Boolean> results;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<CompletableFuture<Boolean>> futures = IntStream.range(0, REQUESTS)
                    .mapToObj(_ -> CompletableFuture.supplyAsync(() -> {
                        graphQl(NEWCOMER).document("{ revivals { id } me { id } }").execute().path("me.id").hasValue();
                        return true;
                    }, executor))
                    .toList();

            results = futures.stream().map(CompletableFuture::join).toList();
        }

        assertThat(results).hasSize(REQUESTS).containsOnly(true);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM person WHERE email = ?").param(NEWCOMER).query(Integer.class).single()).isOne();
    }
}
