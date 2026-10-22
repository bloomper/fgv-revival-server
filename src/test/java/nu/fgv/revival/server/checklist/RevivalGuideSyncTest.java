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
import nu.fgv.revival.server.guide.GuideItemType;
import nu.fgv.revival.server.guide.GuideVersionStatus;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.Phase;
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

/**
 * A revival's required items follow its guide version (FR-022, FR-034, SC-004), on versions built in the test and
 * repositories kept in memory.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class RevivalGuideSyncTest {

    private static final long REVIVAL_ID = 1L;

    private final List<Checklist> checklists = new ArrayList<>();
    private final List<ChecklistItem> items = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
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

    private static GuideVersionView.Item requirement(final String key, final Phase phase) {
        return new GuideVersionView.Item(1L, key, phase, null, GuideItemType.REQUIREMENT, GuideVersionView.Text.plain("Krav " + key, null), 0L);
    }

    private static GuideVersionView version(final String label, final GuideVersionView.Item... chapterItems) {
        final Map<Phase, List<GuideVersionView.Item>> byPhase = Arrays.stream(chapterItems).collect(Collectors.groupingBy(GuideVersionView.Item::phase));

        return new GuideVersionView(1L, GuideVersionStatus.PUBLISHED, label, null, null, null, null, 0L, GuideVersionView.Text.plain("", null),
                Arrays.stream(Phase.values()).map(phase -> new GuideVersionView.Chapter(1L, phase, GuideVersionView.Text.plain(phase.name(), null),
                        byPhase.getOrDefault(phase, List.of()), List.of(), 0L)).toList());
    }

    @BeforeEach
    void setUp() {
        final Store store = new Store();

        service = new ChecklistService(repository(ChecklistRepository.class, store), repository(ChecklistItemRepository.class, store), null, null,
                null, null, null, null, null, null, null, null, null, TestClocks.at(LocalDate.of(2026, 10, 21)));
    }

    private Checklist phase(final Phase phase) {
        return checklists.stream().filter(checklist -> checklist.getPhase() == phase).findFirst().orElseThrow();
    }

    private Optional<ChecklistItem> required(final String key) {
        return items.stream().filter(item -> key.equals(item.getGuideItemKey()) && item.isRequired()).findFirst();
    }

    private List<String> order(final Phase phase) {
        return service.ensurePhaseChecklists(REVIVAL_ID).isEmpty() ? items.stream()
                .filter(item -> item.getChecklistId().equals(phase(phase).getId()))
                .sorted(Comparator.comparingInt(ChecklistItem::getPosition))
                .map(item -> item.isRequired() ? item.getGuideItemKey() : item.getText())
                .toList() : List.of();
    }

    @Test
    void theFirstPinGivesTenChecklistsAndOneRequiredItemPerRequirementInGuideOrder() {
        assertThat(service.ensurePhaseChecklists(REVIVAL_ID)).hasSize(10);
        service.syncRequiredItems(REVIVAL_ID, version("v5", requirement("a1", Phase.A), requirement("b1", Phase.B), requirement("b2", Phase.B)));

        assertThat(checklists).extracting(Checklist::getPhase).containsExactly(Phase.values());
        assertThat(items).hasSize(3).allSatisfy(item -> {
            assertThat(item.isRequired()).isTrue();
            assertThat(item.getText()).isNull();
            assertThat(item.getState()).isEqualTo(ChecklistItemState.OPEN);
        });
        assertThat(order(Phase.B)).containsExactly("b1", "b2");
        assertThat(service.ensurePhaseChecklists(REVIVAL_ID)).isEmpty();
    }

    @Test
    void aVersionChangeAddsKeepsMovesAndMarksRemovedRequirementsNotApplicable() {
        service.ensurePhaseChecklists(REVIVAL_ID);
        service.syncRequiredItems(REVIVAL_ID, version("v5", requirement("kept", Phase.C), requirement("moved", Phase.C),
                requirement("removed", Phase.D), requirement("removedDone", Phase.D)));
        required("kept").orElseThrow().setState(ChecklistItemState.DONE);
        required("moved").orElseThrow().setState(ChecklistItemState.DONE);
        required("removedDone").orElseThrow().setState(ChecklistItemState.DONE);

        service.syncRequiredItems(REVIVAL_ID, version("v6", requirement("new", Phase.C), requirement("kept", Phase.C), requirement("moved", Phase.E)));

        assertThat(required("new").orElseThrow().getState()).isEqualTo(ChecklistItemState.OPEN);
        assertThat(required("kept").orElseThrow().getState()).isEqualTo(ChecklistItemState.DONE);
        assertThat(required("moved").orElseThrow().getChecklistId()).isEqualTo(phase(Phase.E).getId());
        assertThat(required("moved").orElseThrow().getState()).isEqualTo(ChecklistItemState.DONE);
        assertThat(required("removed").orElseThrow()).satisfies(item -> {
            assertThat(item.getState()).isEqualTo(ChecklistItemState.NOT_APPLICABLE);
            assertThat(item.getReasonKey()).isEqualTo("checklist.removedFromGuide");
            assertThat(item.getReasonArg()).isEqualTo("v6");
            assertThat(item.getStateChangedBy()).isNull();
        });
        assertThat(required("removedDone").orElseThrow().getState()).isEqualTo(ChecklistItemState.DONE);
        assertThat(order(Phase.C)).containsExactly("new", "kept");
        assertThat(items.stream().filter(ChecklistItem::isRequired).map(ChecklistItem::getGuideItemKey)).doesNotHaveDuplicates();
    }

    @Test
    void itemsMembersAddedKeepTheirOrderAfterTheRequiredOnes() {
        service.ensurePhaseChecklists(REVIVAL_ID);
        service.syncRequiredItems(REVIVAL_ID, version("v5", requirement("r1", Phase.B)));

        final ChecklistItem added = new ChecklistItem();

        added.setChecklistId(phase(Phase.B).getId());
        added.setRevivalId(REVIVAL_ID);
        added.setText("Egen punkt");
        added.setPosition(1);
        items.add(added);
        added.setId(ids.incrementAndGet());

        service.syncRequiredItems(REVIVAL_ID, version("v6", requirement("r0", Phase.B), requirement("r1", Phase.B)));

        assertThat(order(Phase.B)).containsExactly("r0", "r1", "Egen punkt");
    }

    @Test
    void runningTwiceChangesNothing() {
        service.ensurePhaseChecklists(REVIVAL_ID);
        final GuideVersionView version = version("v5", requirement("a1", Phase.A), requirement("j1", Phase.J));

        service.syncRequiredItems(REVIVAL_ID, version);
        final List<String> before = items.stream().map(item -> item.getId() + ":" + item.getChecklistId() + ":" + item.getPosition() + ":" + item.getState()).toList();

        service.syncRequiredItems(REVIVAL_ID, version);

        assertThat(items.stream().map(item -> item.getId() + ":" + item.getChecklistId() + ":" + item.getPosition() + ":" + item.getState()).toList())
                .isEqualTo(before);
    }
}
