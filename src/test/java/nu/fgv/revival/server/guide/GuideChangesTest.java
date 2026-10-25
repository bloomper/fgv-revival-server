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

import nu.fgv.revival.server.guide.GuideChanges.ItemChange;
import nu.fgv.revival.server.guide.GuideChanges.Kind;
import nu.fgv.revival.server.guide.GuideChanges.Result;
import nu.fgv.revival.server.guide.GuideChanges.SectionChange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exactly one change of each kind, and nothing else reported.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class GuideChangesTest {

    private static GuideVersionView.Text t(final String sv) {
        return GuideSearchTest.text(sv, null);
    }

    private static GuideVersionView.Text t(final String sv, final String en) {
        return GuideSearchTest.text(sv, en);
    }

    private static GuideVersionView.Item item(final String key, final String chapter, final String section, final GuideItemKind type, final GuideVersionView.Text text) {
        return new GuideVersionView.Item(1L, key, chapter, section, type.name(), type, text, 0L);
    }

    private static GuideVersionView version(final long id, final GuideVersionView.Text intro, final List<GuideVersionView.Chapter> chapters) {
        return new GuideVersionView(id, GuideVersionStatus.PUBLISHED, "v" + id, null, Instant.parse("2026-11-0" + id + "T10:00:00Z"), null, null, 0L, intro, chapters);
    }

    private static List<GuideVersionView.Chapter> chapters(final GuideVersionView.Chapter a, final GuideVersionView.Chapter b, final GuideVersionView.Chapter c) {
        final List<GuideVersionView.Chapter> chapters = new ArrayList<>(List.of(a, b, c));

        for (final String key : List.of("D", "E", "F", "G", "H", "I", "J")) {
            chapters.add(chapter(key, GuideVersionView.Text.plain("Kapitel " + key, null), List.of(), List.of()));
        }
        return chapters;
    }

    private static GuideVersionView.Chapter chapter(final String key, final GuideVersionView.Text title,
                                                    final List<GuideVersionView.Item> items, final List<GuideVersionView.Section> sections) {
        return new GuideVersionView.Chapter((long) key.hashCode(), key, title, items, sections, 0L);
    }

    private static GuideVersionView.Section section(final String key, final String chapter, final String title, final List<GuideVersionView.Item> items) {
        return new GuideVersionView.Section((long) key.hashCode(), key, chapter, GuideVersionView.Text.plain(title, null), items, 0L);
    }

    private static GuideVersionView before() {
        final GuideVersionView.Chapter a = chapter("A", GuideVersionView.Text.plain("Först", "First"),
                List.of(item("a1", "A", null, GuideItemKind.TEXT, t("Löptext."))), List.of());
        final GuideVersionView.Section kalas = section("s-kalas", "B", "Kalaslokal", List.of(
                item("b1", "B", "s-kalas", GuideItemKind.TIP, t("Tips ett.")),
                item("b2", "B", "s-kalas", GuideItemKind.TIP, t("Tips två.")),
                item("b3", "B", "s-kalas", GuideItemKind.TIP, t("Tips tre.", "Tip three.")),
                item("b4", "B", "s-kalas", GuideItemKind.TIP, t("Ska bort.")),
                item("b5", "B", "s-kalas", GuideItemKind.TIP, t("Ska flyttas.")),
                item("b6", "B", "s-kalas", GuideItemKind.TIP, t("Ska bli krav, få ny text och flyttas."))));
        final GuideVersionView.Chapter b = chapter("B", GuideVersionView.Text.plain("Lokaler", null), List.of(), List.of(
                kalas,
                section("s-tid", "B", "Tidslinjen", List.of()),
                section("s-bort", "B", "Tas bort", List.of()),
                section("s-flytt", "B", "Flyttas", List.of())));
        final GuideVersionView.Chapter c = chapter("C", GuideVersionView.Text.plain("Budget", null), List.of(), List.of());

        return version(1, t("Inledning."), chapters(a, b, c));
    }

    /**
     * One change of each kind: introduction (Swedish), chapter A renamed (English), section added, removed, renamed,
     * moved and reordered, item added, removed, reworded (Swedish), reworded (English), retyped and moved, and one item
     * reworded, retyped and moved at once.
     */
    private static GuideVersionView after() {
        final GuideVersionView.Chapter a = chapter("A", GuideVersionView.Text.plain("Först", "First of all"),
                List.of(item("a1", "A", null, GuideItemKind.TEXT, t("Löptext."))), List.of());
        final GuideVersionView.Section kalas = section("s-kalas", "B", "Kalaslokal", List.of(
                item("new", "B", "s-kalas", GuideItemKind.TIP, t("Nytt tips först.")),
                item("b2", "B", "s-kalas", GuideItemKind.TIP, t("Tips två, omskrivet.")),
                item("b1", "B", "s-kalas", GuideItemKind.REQUIREMENT, t("Tips ett.")),
                item("b3", "B", "s-kalas", GuideItemKind.TIP, t("Tips tre.", "Tip three, reworded."))));
        final GuideVersionView.Chapter b = chapter("B", GuideVersionView.Text.plain("Lokaler", null), List.of(), List.of(
                kalas,
                section("s-tid", "B", "Tidslinje", List.of(item("b5", "B", "s-tid", GuideItemKind.TIP, t("Ska flyttas.")))),
                section("s-ny", "B", "Nytt avsnitt", List.of())));
        final GuideVersionView.Chapter c = chapter("C", GuideVersionView.Text.plain("Budget", null),
                List.of(item("b6", "C", null, GuideItemKind.REQUIREMENT, t("Nu ett krav i C."))),
                List.of(section("s-flytt", "C", "Flyttas", List.of())));

        return version(2, t("Ny inledning."), chapters(a, b, c));
    }

    @Test
    void everyKindOfChangeIsReportedAndNothingElse() {
        final Result result = GuideChanges.compare(before(), after());

        assertThat(result.from().label()).isEqualTo("v1");
        assertThat(result.to().label()).isEqualTo("v2");
        assertThat(result.introductionChanged()).containsExactly("sv");
        assertThat(result.chapters()).singleElement().satisfies(change -> {
            assertThat(change.key()).isEqualTo("A");
            assertThat(change.kind()).isEqualTo(Kind.CHANGED);
            assertThat(change.renamed()).containsExactly("en");
            assertThat(change.moved()).isFalse();
        });

        assertThat(result.sections()).extracting(SectionChange::key, SectionChange::kind, SectionChange::renamed, SectionChange::moved, SectionChange::reordered)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("s-kalas", Kind.CHANGED, List.of(), false, true),
                        org.assertj.core.groups.Tuple.tuple("s-tid", Kind.CHANGED, List.of("sv"), false, false),
                        org.assertj.core.groups.Tuple.tuple("s-ny", Kind.ADDED, List.of(), false, false),
                        org.assertj.core.groups.Tuple.tuple("s-bort", Kind.REMOVED, List.of(), false, false),
                        org.assertj.core.groups.Tuple.tuple("s-flytt", Kind.CHANGED, List.of(), true, false));

        assertThat(result.items()).extracting(ItemChange::key, ItemChange::kind, ItemChange::reworded, ItemChange::retyped, ItemChange::moved)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("new", Kind.ADDED, List.of(), false, false),
                        org.assertj.core.groups.Tuple.tuple("b4", Kind.REMOVED, List.of(), false, false),
                        org.assertj.core.groups.Tuple.tuple("b2", Kind.CHANGED, List.of("sv"), false, false),
                        org.assertj.core.groups.Tuple.tuple("b3", Kind.CHANGED, List.of("en"), false, false),
                        org.assertj.core.groups.Tuple.tuple("b1", Kind.CHANGED, List.of(), true, false),
                        org.assertj.core.groups.Tuple.tuple("b5", Kind.CHANGED, List.of(), false, true),
                        org.assertj.core.groups.Tuple.tuple("b6", Kind.CHANGED, List.of("sv"), true, true));
    }

    @Test
    void aMovedItemCarriesBothPlaces() {
        final ItemChange moved = GuideChanges.compare(before(), after()).items().stream().filter(change -> change.key().equals("b6")).findFirst().orElseThrow();

        assertThat(moved.before().chapter().key()).isEqualTo("B");
        assertThat(moved.before().chapter().title().sv()).isEqualTo("Lokaler");
        assertThat(moved.before().sectionKey()).isEqualTo("s-kalas");
        assertThat(moved.after().chapter().key()).isEqualTo("C");
        assertThat(moved.after().sectionKey()).isNull();
    }

    @Test
    void anInsertionDoesNotMarkLaterItemsAsMoved() {
        final Result result = GuideChanges.compare(before(), after());

        assertThat(result.items()).filteredOn(change -> change.key().equals("b3")).singleElement()
                .satisfies(change -> assertThat(change.moved()).isFalse());
    }

    @Test
    void chaptersAreComparedByKey() {
        final GuideVersionView.Chapter a = chapter("A", GuideVersionView.Text.plain("Först", null), List.of(), List.of());
        final GuideVersionView.Chapter b = chapter("B", GuideVersionView.Text.plain("Lokaler", null), List.of(), List.of());
        final GuideVersionView.Chapter c = chapter("C", GuideVersionView.Text.plain("Budget", null), List.of(), List.of());
        final GuideVersionView.Chapter d = chapter("D", GuideVersionView.Text.plain("Organisation", null), List.of(), List.of());
        final GuideVersionView.Chapter added = chapter("X", GuideVersionView.Text.plain("Nytt kapitel", null), List.of(), List.of());
        final Result result = GuideChanges.compare(version(1, t(""), List.of(a, b, c, d)), version(2, t(""), List.of(added, a, d, c)));

        assertThat(result.chapters()).extracting(GuideChanges.ChapterChange::key, GuideChanges.ChapterChange::kind, GuideChanges.ChapterChange::moved)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("X", Kind.ADDED, false),
                        org.assertj.core.groups.Tuple.tuple("B", Kind.REMOVED, false),
                        org.assertj.core.groups.Tuple.tuple("D", Kind.CHANGED, true),
                        org.assertj.core.groups.Tuple.tuple("C", Kind.CHANGED, true));
        assertThat(result.chapters()).filteredOn(change -> change.key().equals("B")).singleElement()
                .satisfies(change -> {
                    assertThat(change.before()).isNotNull();
                    assertThat(change.after()).isNull();
                });
    }

    @Test
    void identicalVersionsHaveNoChanges() {
        final Result result = GuideChanges.compare(before(), before());

        assertThat(result.introductionChanged()).isEmpty();
        assertThat(result.chapters()).isEmpty();
        assertThat(result.sections()).isEmpty();
        assertThat(result.items()).isEmpty();
    }
}
