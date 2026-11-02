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
package nu.fgv.revival.server.news;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * GraphQL's {@code News}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record NewsDto(UUID id, String subject, String text, @Nullable LocalDate visibleFrom,
                      @Nullable LocalDate visibleTo,
                      boolean published, Instant createdAt, @Nullable Instant lastModifiedAt, Long version) {

    static NewsDto of(final News news, final LocalDate today) {
        return new NewsDto(news.getUuid(), news.getSubject(), news.getText(), news.getVisibleFrom(), news.getVisibleTo(),
                news.isPublishedOn(today), news.getCreatedAt(), news.getLastModifiedAt(), news.getVersion() == null ? 0L : news.getVersion());
    }
}
