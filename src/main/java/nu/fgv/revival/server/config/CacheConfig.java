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
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Two caches: Spexregister's original spex, kept in memory for 5 minutes as the product spec allows (M1 research R5),
 * and published guide versions, which are immutable and so never expire (M2 research R4).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String ORIGINAL_SPEX = "originalSpex";
    public static final String GUIDE_VERSIONS = "guideVersions";

    @Bean
    public CacheManager cacheManager() {
        final CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        cacheManager.registerCustomCache(ORIGINAL_SPEX,
                Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(10).build());
        cacheManager.registerCustomCache(GUIDE_VERSIONS, Caffeine.newBuilder().maximumSize(20).build());
        return cacheManager;
    }

}
