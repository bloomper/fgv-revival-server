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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What changed between two versions, compared by key (research R5, FR-016). An item that moves within its section is
 * not reported on its own; its section is reported as reordered, so an insertion does not mark every later item as
 * moved.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
public final class GuideChanges {

    private GuideChanges() {
    }

    public enum Kind {
        ADDED,
        REMOVED,
        CHANGED
    }

    public record Result(GuideVersionSummaryDto from,
                         GuideVersionSummaryDto to,
                         List<String> introductionChanged,
                         List<ChapterChange> chapters,
                         List<SectionChange> sections,
                         List<ItemChange> items) {
    }

    /**
     * A chapter as it is in one of the versions.
     */
    public record ChapterRef(String key, GuideVersionView.Text title) {
    }

    /**
     * {@code moved}: the chapter is in a different place among the chapters both versions have.
     */
    public record ChapterChange(String key,
                                Kind kind,
                                List<String> renamed,
                                boolean moved,
                                GuideVersionView.@Nullable Text before,
                                GuideVersionView.@Nullable Text after) {
    }

    public record SectionChange(String key,
                                Kind kind,
                                @Nullable ChapterRef chapterBefore,
                                @Nullable ChapterRef chapterAfter,
                                List<String> renamed,
                                boolean moved,
                                boolean reordered,
                                GuideVersionView.@Nullable Text before,
                                GuideVersionView.@Nullable Text after) {
    }

    public record ItemChange(String key,
                             Kind kind,
                             List<String> reworded,
                             boolean retyped,
                             boolean moved,
                             @Nullable ItemSnapshot before,
                             @Nullable ItemSnapshot after) {
    }

    public record ItemSnapshot(ChapterRef chapter,
                               @Nullable String sectionKey,
                               GuideVersionView.@Nullable Text sectionTitle,
                               GuideItemType type,
                               GuideVersionView.Text text) {
    }

    public static Result compare(final GuideVersionView from, final GuideVersionView to) {
        return new Result(
                GuideMapper.GUIDE_MAPPER.toSummary(from),
                GuideMapper.GUIDE_MAPPER.toSummary(to),
                languagesChanged(from.introduction(), to.introduction()),
                chapters(from, to),
                sections(from, to),
                items(from, to));
    }

    private static List<String> languagesChanged(final GuideVersionView.Text before, final GuideVersionView.Text after) {
        final List<String> languages = new ArrayList<>();

        if (!Objects.equals(before.sv(), after.sv())) {
            languages.add("sv");
        }
        if (!Objects.equals(before.en(), after.en())) {
            languages.add("en");
        }
        return languages;
    }

    private static List<ChapterChange> chapters(final GuideVersionView from, final GuideVersionView to) {
        final Map<String, GuideVersionView.Chapter> before = chaptersByKey(from);
        final Map<String, GuideVersionView.Chapter> after = chaptersByKey(to);
        final List<String> sharedBefore = before.keySet().stream().filter(after::containsKey).toList();
        final List<String> sharedAfter = after.keySet().stream().filter(before::containsKey).toList();
        final List<ChapterChange> changes = new ArrayList<>();

        for (final GuideVersionView.Chapter chapter : after.values()) {
            final GuideVersionView.Chapter old = before.get(chapter.key());

            if (old == null) {
                changes.add(new ChapterChange(chapter.key(), Kind.ADDED, List.of(), false, null, chapter.title()));
                continue;
            }

            final List<String> renamed = languagesChanged(old.title(), chapter.title());
            final boolean moved = sharedBefore.indexOf(chapter.key()) != sharedAfter.indexOf(chapter.key());

            if (!renamed.isEmpty() || moved) {
                changes.add(new ChapterChange(chapter.key(), Kind.CHANGED, renamed, moved, old.title(), chapter.title()));
            }
        }
        for (final GuideVersionView.Chapter old : before.values()) {
            if (!after.containsKey(old.key())) {
                changes.add(new ChapterChange(old.key(), Kind.REMOVED, List.of(), false, old.title(), null));
            }
        }
        return changes;
    }

    private static Map<String, GuideVersionView.Chapter> chaptersByKey(final GuideVersionView version) {
        final Map<String, GuideVersionView.Chapter> chapters = new LinkedHashMap<>();

        version.chapters().forEach(chapter -> chapters.put(chapter.key(), chapter));
        return chapters;
    }

    private static ChapterRef ref(final GuideVersionView.Chapter chapter) {
        return new ChapterRef(chapter.key(), chapter.title());
    }

    private static Map<String, GuideVersionView.Section> sectionsByKey(final GuideVersionView version) {
        final Map<String, GuideVersionView.Section> sections = new LinkedHashMap<>();

        version.chapters().forEach(chapter -> chapter.sections().forEach(section -> sections.put(section.key(), section)));
        return sections;
    }

    private static List<SectionChange> sections(final GuideVersionView from, final GuideVersionView to) {
        final Map<String, GuideVersionView.Section> before = sectionsByKey(from);
        final Map<String, GuideVersionView.Section> after = sectionsByKey(to);
        final Map<String, GuideVersionView.Chapter> chaptersBefore = chaptersByKey(from);
        final Map<String, GuideVersionView.Chapter> chaptersAfter = chaptersByKey(to);
        final List<SectionChange> changes = new ArrayList<>();

        for (final GuideVersionView.Section section : after.values()) {
            final GuideVersionView.Section old = before.get(section.key());

            if (old == null) {
                changes.add(new SectionChange(section.key(), Kind.ADDED, null, ref(chaptersAfter.get(section.chapterKey())), List.of(), false, false,
                        null, section.title()));
                continue;
            }

            final List<String> renamed = languagesChanged(old.title(), section.title());
            final boolean moved = !old.chapterKey().equals(section.chapterKey());
            final boolean reordered = reordered(old.items(), section.items());

            if (!renamed.isEmpty() || moved || reordered) {
                changes.add(new SectionChange(section.key(), Kind.CHANGED, ref(chaptersBefore.get(old.chapterKey())),
                        ref(chaptersAfter.get(section.chapterKey())), renamed, moved, reordered, old.title(), section.title()));
            }
        }
        for (final GuideVersionView.Section old : before.values()) {
            if (!after.containsKey(old.key())) {
                changes.add(new SectionChange(old.key(), Kind.REMOVED, ref(chaptersBefore.get(old.chapterKey())), null, List.of(), false, false,
                        old.title(), null));
            }
        }
        return changes;
    }

    /**
     * Whether the items the two sections share appear in a different order.
     */
    private static boolean reordered(final List<GuideVersionView.Item> before, final List<GuideVersionView.Item> after) {
        final List<String> afterKeys = after.stream().map(GuideVersionView.Item::key).toList();
        final List<String> beforeKeys = before.stream().map(GuideVersionView.Item::key).toList();
        final List<String> sharedBefore = beforeKeys.stream().filter(afterKeys::contains).toList();
        final List<String> sharedAfter = afterKeys.stream().filter(beforeKeys::contains).toList();

        return !sharedBefore.equals(sharedAfter);
    }

    private static List<ItemChange> items(final GuideVersionView from, final GuideVersionView to) {
        final Map<String, ItemSnapshot> before = snapshots(from);
        final Map<String, ItemSnapshot> after = snapshots(to);
        final List<ItemChange> changes = new ArrayList<>();

        for (final Map.Entry<String, ItemSnapshot> entry : after.entrySet()) {
            final ItemSnapshot old = before.get(entry.getKey());
            final ItemSnapshot current = entry.getValue();

            if (old == null) {
                changes.add(new ItemChange(entry.getKey(), Kind.ADDED, List.of(), false, false, null, current));
                continue;
            }

            final List<String> reworded = languagesChanged(old.text(), current.text());
            final boolean retyped = old.type() != current.type();
            final boolean moved = !old.chapter().key().equals(current.chapter().key()) || !Objects.equals(old.sectionKey(), current.sectionKey());

            if (!reworded.isEmpty() || retyped || moved) {
                changes.add(new ItemChange(entry.getKey(), Kind.CHANGED, reworded, retyped, moved, old, current));
            }
        }
        for (final Map.Entry<String, ItemSnapshot> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) {
                changes.add(new ItemChange(entry.getKey(), Kind.REMOVED, List.of(), false, false, entry.getValue(), null));
            }
        }
        return changes;
    }

    private static Map<String, ItemSnapshot> snapshots(final GuideVersionView version) {
        final Map<String, ItemSnapshot> snapshots = new LinkedHashMap<>();

        for (final GuideVersionView.Chapter chapter : version.chapters()) {
            chapter.items().forEach(item -> snapshots.put(item.key(), new ItemSnapshot(ref(chapter), null, null, item.type(), item.text())));
            chapter.sections().forEach(section -> section.items().forEach(item ->
                    snapshots.put(item.key(), new ItemSnapshot(ref(chapter), section.key(), section.title(), item.type(), item.text()))));
        }
        return snapshots;
    }
}
