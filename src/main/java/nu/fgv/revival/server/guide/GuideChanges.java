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

    public record ChapterChange(Phase phase, List<String> renamed, GuideVersionView.Text before, GuideVersionView.Text after) {
    }

    public record SectionChange(String key,
                                Kind kind,
                                @Nullable Phase phaseBefore,
                                @Nullable Phase phaseAfter,
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

    public record ItemSnapshot(Phase phase,
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
        final List<ChapterChange> changes = new ArrayList<>();

        for (final Phase phase : Phase.values()) {
            final GuideVersionView.Text before = from.chapter(phase).title();
            final GuideVersionView.Text after = to.chapter(phase).title();
            final List<String> renamed = languagesChanged(before, after);

            if (!renamed.isEmpty()) {
                changes.add(new ChapterChange(phase, renamed, before, after));
            }
        }
        return changes;
    }

    private static Map<String, GuideVersionView.Section> sectionsByKey(final GuideVersionView version) {
        final Map<String, GuideVersionView.Section> sections = new LinkedHashMap<>();

        version.chapters().forEach(chapter -> chapter.sections().forEach(section -> sections.put(section.key(), section)));
        return sections;
    }

    private static List<SectionChange> sections(final GuideVersionView from, final GuideVersionView to) {
        final Map<String, GuideVersionView.Section> before = sectionsByKey(from);
        final Map<String, GuideVersionView.Section> after = sectionsByKey(to);
        final List<SectionChange> changes = new ArrayList<>();

        for (final GuideVersionView.Section section : after.values()) {
            final GuideVersionView.Section old = before.get(section.key());

            if (old == null) {
                changes.add(new SectionChange(section.key(), Kind.ADDED, null, section.phase(), List.of(), false, false, null, section.title()));
                continue;
            }

            final List<String> renamed = languagesChanged(old.title(), section.title());
            final boolean moved = old.phase() != section.phase();
            final boolean reordered = reordered(old.items(), section.items());

            if (!renamed.isEmpty() || moved || reordered) {
                changes.add(new SectionChange(section.key(), Kind.CHANGED, old.phase(), section.phase(), renamed, moved, reordered, old.title(), section.title()));
            }
        }
        for (final GuideVersionView.Section old : before.values()) {
            if (!after.containsKey(old.key())) {
                changes.add(new SectionChange(old.key(), Kind.REMOVED, old.phase(), null, List.of(), false, false, old.title(), null));
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
            final boolean moved = old.phase() != current.phase() || !Objects.equals(old.sectionKey(), current.sectionKey());

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
            chapter.items().forEach(item -> snapshots.put(item.key(), new ItemSnapshot(chapter.phase(), null, null, item.type(), item.text())));
            chapter.sections().forEach(section -> section.items().forEach(item ->
                    snapshots.put(item.key(), new ItemSnapshot(chapter.phase(), section.key(), section.title(), item.type(), item.text()))));
        }
        return snapshots;
    }
}
