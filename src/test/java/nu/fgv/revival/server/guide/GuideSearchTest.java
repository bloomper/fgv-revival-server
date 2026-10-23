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

import nu.fgv.revival.server.guide.GuideSearch.Hit;
import nu.fgv.revival.server.guide.GuideSearch.HitKind;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Anders Jacobsson
 * @since 0.2
 */
class GuideSearchTest {

    private static final GuideMarkdown MARKDOWN = new GuideMarkdown();

    static GuideVersionView.Text text(final String sv, final String en) {
        return new GuideVersionView.Text(sv, en, MARKDOWN.plainText(sv), en == null ? null : MARKDOWN.plainText(en));
    }

    static GuideVersionView.Item item(final String key, final String chapter, final String sectionKey, final GuideItemKind type, final String sv, final String en) {
        return new GuideVersionView.Item(1L, key, chapter, sectionKey, type.name(), type, text(sv, en), 0L);
    }

    /**
     * A small version: an introduction, chapter A with a leading item, and chapter B with a section of two items.
     */
    static GuideVersionView version() {
        final List<GuideVersionView.Chapter> chapters = new java.util.ArrayList<>();

        for (final String key : GuideTestChapters.KEYS) {
            switch (key) {
                case "A" -> chapters.add(new GuideVersionView.Chapter(1L, key, GuideVersionView.Text.plain("Först: godkännande", "First: approval"),
                        List.of(item("a1", key, null, GuideItemKind.REQUIREMENT, "Kontakta **JK** via kåren.", "Contact JK through the student union.")),
                        List.of(), 0L));
                case "B" -> chapters.add(new GuideVersionView.Chapter(2L, key, GuideVersionView.Text.plain("Boka lokaler", null), List.of(),
                        List.of(new GuideVersionView.Section(3L, "kalas", key, GuideVersionView.Text.plain("Kalaslokal", "Party venue"), List.of(
                                item("b1", key, "kalas", GuideItemKind.TIP, "Kontrollera sprittillstånd i vald lokal.", null),
                                item("b2", key, "kalas", GuideItemKind.TIP, "Se [kårens sida](https://example.org/kar) om bokning.", null)), 0L)),
                        0L));
                default -> chapters.add(new GuideVersionView.Chapter((long) GuideTestChapters.KEYS.indexOf(key) + 10, key, GuideVersionView.Text.plain("Kapitel " + key, null), List.of(), List.of(), 0L));
            }
        }
        return new GuideVersionView(1L, GuideVersionStatus.PUBLISHED, "v1", null, null, null, null, 0L,
                text("Ni funderar på en nyuppsättning. Grattis!", null), chapters);
    }

    @Test
    void findsItemsCaseInsensitivelyWithTheirPlace() {
        final List<Hit> hits = GuideSearch.search(version(), "SPRITTILLSTÅND", 50);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.kind()).isEqualTo(HitKind.ITEM);
            assertThat(hit.chapterKey()).isEqualTo("B");
            assertThat(hit.sectionKey()).isEqualTo("kalas");
            assertThat(hit.sectionTitle().sv()).isEqualTo("Kalaslokal");
            assertThat(hit.itemKey()).isEqualTo("b1");
            assertThat(hit.itemType()).isEqualTo("TIP");
            assertThat(hit.matchedLanguage()).isEqualTo("sv");
        });
    }

    @Test
    void aSubstringMatches() {
        assertThat(GuideSearch.search(version(), "sprit", 50)).extracting(Hit::itemKey).containsExactly("b1");
    }

    @Test
    void aQueryWithoutDiacriticsIgnoresThem() {
        assertThat(GuideSearch.search(version(), "karen", 50)).extracting(Hit::itemKey).containsExactly("a1", "b2");
    }

    @Test
    void aQueryWithDiacriticsMatchesThemExactly() {
        assertThat(GuideSearch.search(version(), "kåren", 50)).extracting(Hit::itemKey).containsExactly("a1", "b2");
        assertThat(GuideSearch.search(version(), "kår", 50)).extracting(Hit::itemKey).doesNotContain((String) null).hasSize(2);
        assertThat(GuideSearch.search(version(), "sprít", 50)).isEmpty();
    }

    @Test
    void englishTextIsSearchedToo() {
        assertThat(GuideSearch.search(version(), "student union", 50)).singleElement().satisfies(hit -> {
            assertThat(hit.itemKey()).isEqualTo("a1");
            assertThat(hit.matchedLanguage()).isEqualTo("en");
        });
    }

    @Test
    void titlesAndTheIntroductionAreSearched() {
        assertThat(GuideSearch.search(version(), "grattis", 50)).extracting(Hit::kind).containsExactly(HitKind.INTRODUCTION);
        assertThat(GuideSearch.search(version(), "godkännande", 50)).extracting(Hit::kind).containsExactly(HitKind.CHAPTER_TITLE);
        assertThat(GuideSearch.search(version(), "party venue", 50)).extracting(Hit::kind).containsExactly(HitKind.SECTION_TITLE);
    }

    @Test
    void markdownSyntaxIsNotMatchedButLinkTextIs() {
        assertThat(GuideSearch.search(version(), "**", 50)).isEmpty();
        assertThat(GuideSearch.search(version(), "https", 50)).isEmpty();
        assertThat(GuideSearch.search(version(), "kårens sida", 50)).extracting(Hit::itemKey).containsExactly("b2");
    }

    @Test
    void resultsAreInGuideOrderAndLimited() {
        assertThat(GuideSearch.search(version(), "lokal", 50)).extracting(Hit::kind, Hit::chapterKey)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(HitKind.CHAPTER_TITLE, "B"),
                        org.assertj.core.groups.Tuple.tuple(HitKind.SECTION_TITLE, "B"),
                        org.assertj.core.groups.Tuple.tuple(HitKind.ITEM, "B"));
        assertThat(GuideSearch.search(version(), "lokal", 2)).extracting(Hit::kind).containsExactly(HitKind.CHAPTER_TITLE, HitKind.SECTION_TITLE);
    }

    @Test
    void theSnippetIsAroundTheFirstMatch() {
        final GuideVersionView version = version();
        final String long_ = "Inledning ".repeat(30) + "sprittillstånd" + " avslutning".repeat(30);
        final GuideVersionView changed = new GuideVersionView(version.id(), version.status(), version.label(), null, null, null, null, 0L,
                text(long_, null), version.chapters());
        final Hit hit = GuideSearch.search(changed, "sprittillstånd", 50).getFirst();

        assertThat(hit.snippet()).startsWith("…").endsWith("…").contains("sprittillstånd");
        assertThat(hit.snippet().length()).isLessThanOrEqualTo(2 * GuideSearch.SNIPPET_RADIUS + "sprittillstånd".length() + 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a"})
    void aQueryUnderTwoCharactersIsRefused(final String query) {
        assertThatThrownBy(() -> GuideSearch.search(version(), query, 50))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.search.tooShort"));
    }

    @Test
    void noMatchIsAnEmptyList() {
        assertThat(GuideSearch.search(version(), "finns inte", 50)).isEmpty();
    }
}
