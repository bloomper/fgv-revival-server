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

import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Searching one version in memory (research R4, FR-021): case-insensitive substring matching on the Swedish and English
 * plain text of the introduction, titles and items. A query without diacritics ignores them in the text ("kar" finds
 * "kår"); a query with diacritics matches them exactly ("kår" does not find "kar").
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
public final class GuideSearch {

    public static final int MIN_QUERY = 2;
    static final int SNIPPET_RADIUS = 80;

    private static final Locale SWEDISH = Locale.of("sv");

    private GuideSearch() {
    }

    public enum HitKind {
        INTRODUCTION,
        CHAPTER_TITLE,
        SECTION_TITLE,
        ITEM
    }

    public record Hit(HitKind kind,
                      @Nullable String chapterKey,
                      GuideVersionView.@Nullable Text chapterTitle,
                      @Nullable String sectionKey,
                      GuideVersionView.@Nullable Text sectionTitle,
                      @Nullable String itemKey,
                      @Nullable String itemType,
                      String snippet,
                      String matchedLanguage) {
    }

    public static List<Hit> search(final GuideVersionView version, final @Nullable String query, final int first) {
        final String needle = query == null ? "" : query.strip().toLowerCase(SWEDISH).replaceAll("\\s+", " ");

        if (needle.length() < MIN_QUERY) {
            throw new BadRequestException("error.guide.search.tooShort", MIN_QUERY);
        }

        final boolean exact = !fold(needle).equals(needle);
        final String foldedNeedle = exact ? needle : fold(needle);
        final List<Hit> hits = new ArrayList<>();
        final Matcher matcher = new Matcher(foldedNeedle, exact);

        matcher.match(version.introduction()).ifPresent(match ->
                hits.add(new Hit(HitKind.INTRODUCTION, null, null, null, null, null, null, match.snippet(), match.language())));
        for (final GuideVersionView.Chapter chapter : version.chapters()) {
            matcher.match(chapter.title()).ifPresent(match ->
                    hits.add(new Hit(HitKind.CHAPTER_TITLE, chapter.key(), chapter.title(), null, null, null, null, match.snippet(), match.language())));
            for (final GuideVersionView.Item item : chapter.items()) {
                matcher.match(item.text()).ifPresent(match ->
                        hits.add(new Hit(HitKind.ITEM, chapter.key(), chapter.title(), null, null, item.key(), item.type(), match.snippet(), match.language())));
            }
            for (final GuideVersionView.Section section : chapter.sections()) {
                matcher.match(section.title()).ifPresent(match ->
                        hits.add(new Hit(HitKind.SECTION_TITLE, chapter.key(), chapter.title(), section.key(), section.title(), null, null, match.snippet(), match.language())));
                for (final GuideVersionView.Item item : section.items()) {
                    matcher.match(item.text()).ifPresent(match ->
                            hits.add(new Hit(HitKind.ITEM, chapter.key(), chapter.title(), section.key(), section.title(), item.key(), item.type(), match.snippet(), match.language())));
                }
            }
        }
        return hits.size() <= first ? hits : List.copyOf(hits.subList(0, Math.max(first, 0)));
    }

    /**
     * Removes diacritics one character at a time, so positions in the folded text are positions in the original.
     */
    static String fold(final String text) {
        final StringBuilder folded = new StringBuilder(text.length());

        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            final String base = Normalizer.normalize(String.valueOf(character), Normalizer.Form.NFD).replaceAll("\\p{M}", "");

            folded.append(base.length() == 1 ? base.charAt(0) : character);
        }
        return folded.toString();
    }

    private record Match(String snippet, String language) {
    }

    private record Matcher(String needle, boolean exact) {

        java.util.Optional<Match> match(final GuideVersionView.Text text) {
            return match(text.plainSv(), "sv").or(() -> text.plainEn() == null ? java.util.Optional.empty() : match(text.plainEn(), "en"));
        }

        private java.util.Optional<Match> match(final String text, final String language) {
            final String collapsed = text.replaceAll("\\s+", " ").strip();
            final String haystack = exact ? collapsed.toLowerCase(SWEDISH) : fold(collapsed.toLowerCase(SWEDISH));
            final int index = haystack.indexOf(needle);

            if (index < 0) {
                return java.util.Optional.empty();
            }

            final int start = Math.max(0, index - SNIPPET_RADIUS);
            final int end = Math.min(collapsed.length(), index + needle.length() + SNIPPET_RADIUS);
            final String snippet = (start > 0 ? "…" : "") + collapsed.substring(start, end).strip() + (end < collapsed.length() ? "…" : "");

            return java.util.Optional.of(new Match(snippet, language));
        }
    }
}
