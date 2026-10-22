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


package nu.fgv.revival.server.checklist;

import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.decisionlog.LogEntryService;
import nu.fgv.revival.server.guide.GuideItemType;
import nu.fgv.revival.server.guide.GuideTestChapters;
import nu.fgv.revival.server.guide.GuideVersionStatus;
import nu.fgv.revival.server.guide.GuideVersionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A revival's chapter checklists and required items follow its guide version (FR-022, FR-034, SC-004), on versions
 * built in the test and repositories kept in memory.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class RevivalGuideSyncTest {

    private static final long REVIVAL_ID = 1L;

    private final List<Checklist> checklists = new ArrayList<>();
    private final List<ChecklistItem> items = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final LogEntryService logEntries = mock(LogEntryService.class);
    private ChecklistService service;

    @SuppressWarnings("unchecked")
    private <T> T repository(final Class<T> type, final Object implementation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) ->
                implementation.getClass().getMethod(method.getName(), method.getParameterTypes()).invoke(implementation, arguments));
    }

    /**
     * The repository methods the sync uses, over the lists above.
     */
    public final class Store {

        public Checklist save(final Checklist checklist) {
            if (checklist.getId() == null) {
                checklist.setId(ids.incrementAndGet());
                checklists.add(checklist);
            }
            return checklist;
        }

        public List<Checklist> findByRevivalIdAndKind(final Long revivalId, final ChecklistKind kind) {
            return checklists.stream().filter(checklist -> checklist.getKind() == kind).toList();
        }

        public void delete(final Checklist checklist) {
            checklists.remove(checklist);
        }

        public void deleteAll(final Iterable<ChecklistItem> deleted) {
            deleted.forEach(items::remove);
        }

        public ChecklistItem save(final ChecklistItem item) {
            if (item.getId() == null) {
                item.setId(ids.incrementAndGet());
                items.add(item);
            }
            return item;
        }

        public List<ChecklistItem> findByRevivalIdAndRequiredTrue(final Long revivalId) {
            return items.stream().filter(ChecklistItem::isRequired).toList();
        }

        public List<ChecklistItem> findByChecklistIdOrderByPositionAscIdAsc(final Long checklistId) {
            return items.stream().filter(item -> item.getChecklistId().equals(checklistId))
                    .sorted(Comparator.comparingInt(ChecklistItem::getPosition).thenComparing(ChecklistItem::getId)).toList();
        }
    }

    private static GuideVersionView.Item requirement(final String key, final String chapter) {
        return new GuideVersionView.Item(1L, key, chapter, null, GuideItemType.REQUIREMENT, GuideVersionView.Text.plain("Krav " + key, null), 0L);
    }

    private static GuideVersionView version(final String label, final GuideVersionView.Item... chapterItems) {
        return version(label, GuideTestChapters.KEYS, chapterItems);
    }

    private static GuideVersionView version(final String label, final List<String> chapters, final GuideVersionView.Item... chapterItems) {
        final Map<String, List<GuideVersionView.Item>> byChapter = Arrays.stream(chapterItems).collect(Collectors.groupingBy(GuideVersionView.Item::chapterKey));

        return new GuideVersionView(1L, GuideVersionStatus.PUBLISHED, label, null, null, null, null, 0L, GuideVersionView.Text.plain("", null),
                chapters.stream().map(key -> new GuideVersionView.Chapter(1L, key, GuideVersionView.Text.plain(key, null),
                        byChapter.getOrDefault(key, List.of()), List.of(), 0L)).toList());
    }

    @BeforeEach
    void setUp() {
        final Store store = new Store();

        service = new ChecklistService(repository(ChecklistRepository.class, store), repository(ChecklistItemRepository.class, store), null, null,
                null, null, null, null, null, null, null, null, null, TestClocks.at(LocalDate.of(2026, 10, 21)), logEntries);
    }

    private Checklist chapter(final String key) {
        return checklists.stream().filter(checklist -> key.equals(checklist.getChapterKey())).findFirst().orElseThrow();
    }

    private Optional<ChecklistItem> required(final String key) {
        return items.stream().filter(item -> key.equals(item.getGuideItemKey()) && item.isRequired()).findFirst();
    }

    private List<String> order(final String chapter) {
        return items.stream()
                .filter(item -> item.getChecklistId().equals(chapter(chapter).getId()))
                .sorted(Comparator.comparingInt(ChecklistItem::getPosition))
                .map(item -> item.isRequired() ? item.getGuideItemKey() : item.getText())
                .toList();
    }

    @Test
    void theFirstPinGivesOneChecklistPerChapterAndOneRequiredItemPerRequirementInGuideOrder() {
        final GuideVersionView version = version("v5", requirement("a1", "A"), requirement("b1", "B"), requirement("b2", "B"));

        assertThat(service.ensureChapterChecklists(REVIVAL_ID, version)).hasSize(10);
        service.syncRequiredItems(REVIVAL_ID, version);

        assertThat(checklists).extracting(Checklist::getChapterKey).containsExactlyElementsOf(GuideTestChapters.KEYS);
        assertThat(checklists).extracting(Checklist::getKind).containsOnly(ChecklistKind.CHAPTER);
        assertThat(items).hasSize(3).allSatisfy(item -> {
            assertThat(item.isRequired()).isTrue();
            assertThat(item.getText()).isNull();
            assertThat(item.getState()).isEqualTo(ChecklistItemState.OPEN);
        });
        assertThat(order("B")).containsExactly("b1", "b2");
        assertThat(service.ensureChapterChecklists(REVIVAL_ID, version)).isEmpty();
    }

    @Test
    void aVersionChangeAddsKeepsMovesAndMarksRemovedRequirementsNotApplicable() {
        service.ensureChapterChecklists(REVIVAL_ID, version("v5"));
        service.syncRequiredItems(REVIVAL_ID, version("v5", requirement("kept", "C"), requirement("moved", "C"),
                requirement("removed", "D"), requirement("removedDone", "D")));
        required("kept").orElseThrow().setState(ChecklistItemState.DONE);
        required("moved").orElseThrow().setState(ChecklistItemState.DONE);
        required("removedDone").orElseThrow().setState(ChecklistItemState.DONE);

        service.syncRequiredItems(REVIVAL_ID, version("v6", requirement("new", "C"), requirement("kept", "C"), requirement("moved", "E")));

        assertThat(required("new").orElseThrow().getState()).isEqualTo(ChecklistItemState.OPEN);
        assertThat(required("kept").orElseThrow().getState()).isEqualTo(ChecklistItemState.DONE);
        assertThat(required("moved").orElseThrow().getChecklistId()).isEqualTo(chapter("E").getId());
        assertThat(required("moved").orElseThrow().getState()).isEqualTo(ChecklistItemState.DONE);
        assertThat(required("removed").orElseThrow()).satisfies(item -> {
            assertThat(item.getState()).isEqualTo(ChecklistItemState.NOT_APPLICABLE);
            assertThat(item.getReasonKey()).isEqualTo("checklist.removedFromGuide");
            assertThat(item.getReasonArg()).isEqualTo("v6");
            assertThat(item.getStateChangedBy()).isNull();
        });
        assertThat(required("removedDone").orElseThrow().getState()).isEqualTo(ChecklistItemState.DONE);
        assertThat(order("C")).containsExactly("new", "kept");
        assertThat(items.stream().filter(ChecklistItem::isRequired).map(ChecklistItem::getGuideItemKey)).doesNotHaveDuplicates();
    }

    @Test
    void itemsMembersAddedKeepTheirOrderAfterTheRequiredOnes() {
        service.ensureChapterChecklists(REVIVAL_ID, version("v5"));
        service.syncRequiredItems(REVIVAL_ID, version("v5", requirement("r1", "B")));

        final ChecklistItem added = new ChecklistItem();

        added.setChecklistId(chapter("B").getId());
        added.setRevivalId(REVIVAL_ID);
        added.setText("Egen punkt");
        added.setPosition(1);
        items.add(added);
        added.setId(ids.incrementAndGet());

        service.syncRequiredItems(REVIVAL_ID, version("v6", requirement("r0", "B"), requirement("r1", "B")));

        assertThat(order("B")).containsExactly("r0", "r1", "Egen punkt");
    }

    @Test
    void aVersionThatOnlyReordersAChapterReordersItsItems() {
        service.ensureChapterChecklists(REVIVAL_ID, version("v5"));
        service.syncRequiredItems(REVIVAL_ID, version("v5", requirement("f1", "F"), requirement("f2", "F"), requirement("f3", "F")));

        service.syncRequiredItems(REVIVAL_ID, version("v6", requirement("f3", "F"), requirement("f1", "F"), requirement("f2", "F")));

        assertThat(order("F")).containsExactly("f3", "f1", "f2");
    }

    @Test
    void aChapterAddedInANewVersionGetsAChecklist() {
        service.ensureChapterChecklists(REVIVAL_ID, version("v5", List.of("A", "B")));

        assertThat(service.ensureChapterChecklists(REVIVAL_ID, version("v6", List.of("A", "X", "B")))).extracting(Checklist::getChapterKey)
                .containsExactly("X");
        assertThat(checklists).extracting(Checklist::getChapterKey).containsExactlyInAnyOrder("A", "B", "X");
    }

    @Test
    void aRemovedChapterLosesItsChecklistAfterItsRequirementsHaveMoved() {
        final GuideVersionView v5 = version("v5", List.of("A", "B"), requirement("stays", "B"), requirement("moves", "B"), requirement("goes", "B"));

        service.ensureChapterChecklists(REVIVAL_ID, v5);
        service.syncRequiredItems(REVIVAL_ID, v5);

        final ChecklistItem own = new ChecklistItem();

        own.setChecklistId(chapter("B").getId());
        own.setRevivalId(REVIVAL_ID);
        own.setText("Egen punkt");
        own.setPosition(9);
        items.add(own);
        own.setId(ids.incrementAndGet());

        final long removedChecklist = chapter("B").getId();
        final long goes = required("goes").orElseThrow().getId();
        final GuideVersionView v6 = version("v6", List.of("A"), requirement("stays", "A"), requirement("moves", "A"));

        service.ensureChapterChecklists(REVIVAL_ID, v6);
        service.syncRequiredItems(REVIVAL_ID, v6);
        service.deleteRemovedChapterChecklists(REVIVAL_ID, v6);

        assertThat(checklists).extracting(Checklist::getChapterKey).containsExactly("A");
        assertThat(order("A")).containsExactly("stays", "moves");
        assertThat(items).noneMatch(item -> item.getChecklistId().equals(removedChecklist));
        assertThat(required("goes")).isEmpty();
        verify(logEntries).unlinkChecklistItems(List.of(goes, own.getId()));
    }

    @Test
    void runningTwiceChangesNothing() {
        service.ensureChapterChecklists(REVIVAL_ID, version("v5"));
        final GuideVersionView version = version("v5", requirement("a1", "A"), requirement("j1", "J"));

        service.syncRequiredItems(REVIVAL_ID, version);
        final List<String> before = items.stream().map(item -> item.getId() + ":" + item.getChecklistId() + ":" + item.getPosition() + ":" + item.getState()).toList();

        service.syncRequiredItems(REVIVAL_ID, version);

        assertThat(items.stream().map(item -> item.getId() + ":" + item.getChecklistId() + ":" + item.getPosition() + ":" + item.getState()).toList())
                .isEqualTo(before);
    }
}
