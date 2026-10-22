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

package nu.fgv.revival.server.guide;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One version of the guide as an immutable tree, the shape every reader, search, the changes view and the PDF work on.
 * Published versions are cached as this (research R4).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
public record GuideVersionView(Long id,
                               GuideVersionStatus status,
                               @Nullable String label,
                               @Nullable String note,
                               @Nullable Instant publishedAt,
                               @Nullable Long publishedBy,
                               @Nullable Long basedOnId,
                               Long version,
                               Text introduction,
                               List<Chapter> chapters) {

    public GuideVersionView {
        chapters = List.copyOf(chapters);
    }

    public boolean draft() {
        return status == GuideVersionStatus.DRAFT;
    }

    public Optional<Chapter> chapter(final String key) {
        return chapters.stream().filter(chapter -> chapter.key().equals(key)).findFirst();
    }

    public Optional<Section> section(final String key) {
        return chapters.stream().flatMap(chapter -> chapter.sections().stream()).filter(section -> section.key().equals(key)).findFirst();
    }

    public Optional<Item> item(final String key) {
        return items().filter(item -> item.key().equals(key)).findFirst();
    }

    /**
     * Every item in guide order.
     */
    public Stream<Item> items() {
        return chapters.stream().flatMap(chapter -> Stream.concat(chapter.items().stream(),
                chapter.sections().stream().flatMap(section -> section.items().stream())));
    }

    /**
     * Markdown in Swedish and optionally English, each with its plain text for search.
     */
    public record Text(String sv, @Nullable String en, String plainSv, @Nullable String plainEn) {

        /**
         * A title: plain text, not Markdown.
         */
        public static Text plain(final String sv, final @Nullable String en) {
            return new Text(sv, en, sv, en);
        }

        public boolean translated() {
            return en != null;
        }
    }

    public record Chapter(Long id, String key, Text title, List<Item> items, List<Section> sections, Long version) {

        public Chapter {
            items = List.copyOf(items);
            sections = List.copyOf(sections);
        }
    }

    public record Section(Long id, String key, String chapterKey, Text title, List<Item> items, Long version) {

        public Section {
            items = List.copyOf(items);
        }
    }

    public record Item(Long id, String key, String chapterKey, @Nullable String sectionKey, GuideItemType type, Text text, Long version) {
    }
}
