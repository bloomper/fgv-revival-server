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
 * Exactly one change of each kind, and nothing else reported (SC-004).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
class GuideChangesTest {

    private static GuideVersionView.Text t(final String sv) {
        return GuideSearchTest.text(sv, null);
    }

    private static GuideVersionView.Text t(final String sv, final String en) {
        return GuideSearchTest.text(sv, en);
    }

    private static GuideVersionView.Item item(final String key, final Phase phase, final String section, final GuideItemType type, final GuideVersionView.Text text) {
        return new GuideVersionView.Item(1L, key, phase, section, type, text, 0L);
    }

    private static GuideVersionView version(final long id, final GuideVersionView.Text intro, final List<GuideVersionView.Chapter> chapters) {
        return new GuideVersionView(id, GuideVersionStatus.PUBLISHED, "v" + id, null, Instant.parse("2026-11-0" + id + "T10:00:00Z"), null, null, 0L, intro, chapters);
    }

    private static List<GuideVersionView.Chapter> chapters(final GuideVersionView.Chapter a, final GuideVersionView.Chapter b, final GuideVersionView.Chapter c) {
        final List<GuideVersionView.Chapter> chapters = new ArrayList<>(List.of(a, b, c));

        for (final Phase phase : Phase.values()) {
            if (phase.ordinal() > 2) {
                chapters.add(new GuideVersionView.Chapter((long) phase.ordinal(), phase, GuideVersionView.Text.plain("Kapitel " + phase, null), List.of(), List.of(), 0L));
            }
        }
        return chapters;
    }

    private static GuideVersionView.Chapter chapter(final Phase phase, final GuideVersionView.Text title,
                                                    final List<GuideVersionView.Item> items, final List<GuideVersionView.Section> sections) {
        return new GuideVersionView.Chapter((long) phase.ordinal() + 1, phase, title, items, sections, 0L);
    }

    private static GuideVersionView.Section section(final String key, final Phase phase, final String title, final List<GuideVersionView.Item> items) {
        return new GuideVersionView.Section((long) key.hashCode(), key, phase, GuideVersionView.Text.plain(title, null), items, 0L);
    }

    private static GuideVersionView before() {
        final GuideVersionView.Chapter a = chapter(Phase.A, GuideVersionView.Text.plain("Först", "First"),
                List.of(item("a1", Phase.A, null, GuideItemType.TEXT, t("Löptext."))), List.of());
        final GuideVersionView.Section kalas = section("s-kalas", Phase.B, "Kalaslokal", List.of(
                item("b1", Phase.B, "s-kalas", GuideItemType.TIP, t("Tips ett.")),
                item("b2", Phase.B, "s-kalas", GuideItemType.TIP, t("Tips två.")),
                item("b3", Phase.B, "s-kalas", GuideItemType.TIP, t("Tips tre.", "Tip three.")),
                item("b4", Phase.B, "s-kalas", GuideItemType.TIP, t("Ska bort.")),
                item("b5", Phase.B, "s-kalas", GuideItemType.TIP, t("Ska flyttas.")),
                item("b6", Phase.B, "s-kalas", GuideItemType.TIP, t("Ska bli krav, få ny text och flyttas."))));
        final GuideVersionView.Chapter b = chapter(Phase.B, GuideVersionView.Text.plain("Lokaler", null), List.of(), List.of(
                kalas,
                section("s-tid", Phase.B, "Tidslinjen", List.of()),
                section("s-bort", Phase.B, "Tas bort", List.of()),
                section("s-flytt", Phase.B, "Flyttas", List.of())));
        final GuideVersionView.Chapter c = chapter(Phase.C, GuideVersionView.Text.plain("Budget", null), List.of(), List.of());

        return version(1, t("Inledning."), chapters(a, b, c));
    }

    /**
     * One change of each kind: introduction (Swedish), chapter A renamed (English), section added, removed, renamed,
     * moved and reordered, item added, removed, reworded (Swedish), reworded (English), retyped and moved, and one item
     * reworded, retyped and moved at once.
     */
    private static GuideVersionView after() {
        final GuideVersionView.Chapter a = chapter(Phase.A, GuideVersionView.Text.plain("Först", "First of all"),
                List.of(item("a1", Phase.A, null, GuideItemType.TEXT, t("Löptext."))), List.of());
        final GuideVersionView.Section kalas = section("s-kalas", Phase.B, "Kalaslokal", List.of(
                item("new", Phase.B, "s-kalas", GuideItemType.TIP, t("Nytt tips först.")),
                item("b2", Phase.B, "s-kalas", GuideItemType.TIP, t("Tips två, omskrivet.")),
                item("b1", Phase.B, "s-kalas", GuideItemType.REQUIREMENT, t("Tips ett.")),
                item("b3", Phase.B, "s-kalas", GuideItemType.TIP, t("Tips tre.", "Tip three, reworded."))));
        final GuideVersionView.Chapter b = chapter(Phase.B, GuideVersionView.Text.plain("Lokaler", null), List.of(), List.of(
                kalas,
                section("s-tid", Phase.B, "Tidslinje", List.of(item("b5", Phase.B, "s-tid", GuideItemType.TIP, t("Ska flyttas.")))),
                section("s-ny", Phase.B, "Nytt avsnitt", List.of())));
        final GuideVersionView.Chapter c = chapter(Phase.C, GuideVersionView.Text.plain("Budget", null),
                List.of(item("b6", Phase.C, null, GuideItemType.REQUIREMENT, t("Nu ett krav i C."))),
                List.of(section("s-flytt", Phase.C, "Flyttas", List.of())));

        return version(2, t("Ny inledning."), chapters(a, b, c));
    }

    @Test
    void everyKindOfChangeIsReportedAndNothingElse() {
        final Result result = GuideChanges.compare(before(), after());

        assertThat(result.from().label()).isEqualTo("v1");
        assertThat(result.to().label()).isEqualTo("v2");
        assertThat(result.introductionChanged()).containsExactly("sv");
        assertThat(result.chapters()).singleElement().satisfies(change -> {
            assertThat(change.phase()).isEqualTo(Phase.A);
            assertThat(change.renamed()).containsExactly("en");
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

        assertThat(moved.before().phase()).isEqualTo(Phase.B);
        assertThat(moved.before().sectionKey()).isEqualTo("s-kalas");
        assertThat(moved.after().phase()).isEqualTo(Phase.C);
        assertThat(moved.after().sectionKey()).isNull();
    }

    @Test
    void anInsertionDoesNotMarkLaterItemsAsMoved() {
        final Result result = GuideChanges.compare(before(), after());

        assertThat(result.items()).filteredOn(change -> change.key().equals("b3")).singleElement()
                .satisfies(change -> assertThat(change.moved()).isFalse());
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
