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

package nu.fgv.revival.server.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import nu.fgv.revival.server.spex.SpexregisterClient;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;

/**
 * Five caches: Spexregister's original spex, kept in memory for 5 minutes,
 * published guide versions, which are immutable and so never expire, spex posters, kept for 5
 * minutes like the spex and bounded by their size, so a list of revivals does not ask Spexregister
 * for every poster on every view, spexare by id with their names and contact details, kept in memory only and for at
 * most 5 minutes, and Spexregister's task list, vocal parts and spex families, kept for an
 * hour.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String ORIGINAL_SPEX = "originalSpex";
    public static final String GUIDE_VERSIONS = "guideVersions";
    public static final String SPEX_POSTERS = "spexPosters";
    public static final String SPEXARE = "spexare";
    public static final String SPEXREGISTER_REFERENCE = "spexregisterReference";

    private static final long POSTER_CACHE_BYTES = 64L * 1024 * 1024;

    @Bean
    public CacheManager cacheManager() {
        final CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        cacheManager.registerCustomCache(ORIGINAL_SPEX,
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(10).build());
        cacheManager.registerCustomCache(GUIDE_VERSIONS, Caffeine.newBuilder().maximumSize(20).build());
        cacheManager.registerCustomCache(SPEX_POSTERS, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumWeight(POSTER_CACHE_BYTES)
                .weigher((_, value) -> value instanceof Optional<?> poster && poster.orElse(null) instanceof SpexregisterClient.Poster image
                        ? image.content().length + 64
                        : 64)
                .build());
        cacheManager.registerCustomCache(SPEXARE, Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(5_000).build());
        cacheManager.registerCustomCache(SPEXREGISTER_REFERENCE, Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(200).build());
        return cacheManager;
    }

}
