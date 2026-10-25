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
package nu.fgv.revival.server.library;

import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;

/**
 * GraphQL's {@code LibraryEntry}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record LibraryEntryDto(Long id,
                              LibraryEntryKind kind,
                              GuideVersionView.Text name,
                              GuideVersionView.@Nullable Text description,
                              GuideVersionView.@Nullable Text pros,
                              GuideVersionView.@Nullable Text cons,
                              GuideVersionView.@Nullable Text booking,
                              GuideVersionView.@Nullable Text prices,
                              GuideVersionView.@Nullable Text contact,
                              @Nullable String url,
                              boolean retired,
                              Long version) {

    public static LibraryEntryDto of(final LibraryEntry entry) {
        return new LibraryEntryDto(entry.getId(), entry.getKind(), GuideVersionView.Text.plain(entry.getNameSv(), entry.getNameEn()),
                text(entry.getDescriptionSv(), entry.getDescriptionEn()), text(entry.getProsSv(), entry.getProsEn()),
                text(entry.getConsSv(), entry.getConsEn()), text(entry.getBookingSv(), entry.getBookingEn()),
                text(entry.getPricesSv(), entry.getPricesEn()), text(entry.getContactSv(), entry.getContactEn()), entry.getUrl(),
                entry.isRetired(), entry.getVersion() == null ? 0L : entry.getVersion());
    }

    private static GuideVersionView.@Nullable Text text(final @Nullable String sv, final @Nullable String en) {
        return sv == null ? null : GuideVersionView.Text.plain(sv, en);
    }
}
