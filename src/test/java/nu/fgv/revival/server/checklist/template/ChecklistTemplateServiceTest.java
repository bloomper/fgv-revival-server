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


package nu.fgv.revival.server.checklist.template;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.checklist.RelativeDeadlineKind;
import nu.fgv.revival.server.guide.GuideAccess;
import nu.fgv.revival.server.guide.GuideVersion;
import nu.fgv.revival.server.guide.GuideVersionPublished;
import nu.fgv.revival.server.guide.GuideVersionRepository;
import nu.fgv.revival.server.guide.GuideVersionStatus;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.GuideVersionViews;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.CapabilityService;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules for checklist templates and their items.
 *
 * @author Anders Jacobsson
 */
@ExtendWith(MockitoExtension.class)
class ChecklistTemplateServiceTest {

    private final List<ChecklistTemplateItem> items = new ArrayList<>();
    @Mock
    private ChecklistTemplateRepository templateRepository;
    @Mock
    private ChecklistTemplateItemRepository itemRepository;
    @Mock
    private GuideVersionRepository versionRepository;
    @Mock
    private GuideVersionViews guideVersionViews;
    @Mock
    private GuideAccess guideAccess;
    @Mock
    private CallerService callerService;
    private ChecklistTemplateService service;

    private static ChecklistTemplateService.NewItem newItem(final String text, final RelativeDeadline deadline) {
        return new ChecklistTemplateService.NewItem(5L, null, new ChecklistTemplateService.Texts(text, null), null, null, deadline);
    }

    static Stream<Caller> notSuperAdmins() {
        return Stream.of(TestCallers.observer(), TestCallers.directeur(), TestCallers.steeringGroup(), TestCallers.nobody());
    }

    @BeforeEach
    void setUp() {
        service = new ChecklistTemplateService(templateRepository, itemRepository, versionRepository, guideVersionViews, guideAccess, callerService,
                new AccessPolicy(), mock(CapabilityService.class), TestClocks.at(LocalDate.of(2026, 10, 21)));
        as(TestCallers.superAdmin());
        lenient().when(guideAccess.callerRevivals(any())).thenReturn(List.of());
        lenient().when(templateRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(itemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(itemRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(itemRepository.findByTemplateIdAndRetiredAtIsNullOrderByPositionAscIdAsc(any()))
                .thenAnswer(invocation -> items.stream().filter(item -> !item.isRetired()).toList());
    }

    /**
     * The latest published version, with chapters of the given keys and nothing in them.
     */
    private GuideVersionView latestWithChapters(final String... keys) {
        final GuideVersion latest = new GuideVersion();
        final GuideVersionView view = new GuideVersionView(1L, GuideVersionStatus.PUBLISHED, "v1", null, null, null, null, 0L,
                GuideVersionView.Text.plain("", null), java.util.Arrays.stream(keys)
                .map(key -> new GuideVersionView.Chapter((long) key.hashCode(), key, GuideVersionView.Text.plain("Kapitel " + key, null), List.of(), List.of(), 0L))
                .toList());

        latest.setId(1L);
        lenient().when(versionRepository.findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED)).thenReturn(Optional.of(latest));
        lenient().when(guideVersionViews.find(1L)).thenReturn(view);
        return view;
    }

    private void as(final Caller caller) {
        lenient().when(callerService.current()).thenReturn(caller);
    }

    private ChecklistTemplate template(final ChecklistKind kind) {
        final ChecklistTemplate template = new ChecklistTemplate();

        template.setId(5L);
        template.setKind(kind);
        template.setChapterKey(kind == ChecklistKind.CHAPTER ? "B" : null);
        template.setNameSv(kind == ChecklistKind.EVENT ? "Rephelg" : null);
        template.setVersion(0L);
        lenient().when(templateRepository.findById(5L)).thenReturn(Optional.of(template));
        return template;
    }

    private ChecklistTemplateItem item(final long id, final int position) {
        final ChecklistTemplateItem item = new ChecklistTemplateItem();

        item.setId(id);
        item.setTemplateId(5L);
        item.setPosition(position);
        item.setTextSv("Punkt " + id);
        item.setVersion(0L);
        items.add(item);
        lenient().when(itemRepository.findById(id)).thenReturn(Optional.of(item));
        return item;
    }

    @ParameterizedTest
    @MethodSource("notSuperAdmins")
    void everyMutationIsRefusedForAnyoneButASuperAdmin(final Caller caller) {
        template(ChecklistKind.EVENT);
        item(1L, 0);
        as(caller);

        assertThatThrownBy(() -> service.create(new ChecklistTemplateService.NewTemplate(ChecklistKind.CHAPTER, "A", null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(5L, 0L, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.retire(5L, 0L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.createItem(newItem("x", null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.updateItem(new ChecklistTemplateService.ItemChange(1L, 0L, null, null, false, null, false, null, false)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.moveItem(1L, 0L, 0)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.retireItem(1L, 0L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.restore(5L, 0L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.restoreItem(1L, 0L)).isInstanceOf(AccessDeniedException.class);
        verify(templateRepository, never()).saveAndFlush(any());
    }

    @Test
    void readingFollowsTheGuide() {
        as(TestCallers.nobody());

        assertThatThrownBy(() -> service.list(false)).isInstanceOf(AccessDeniedException.class);

        as(TestCallers.observer());
        when(itemRepository.findByTemplateIdIn(anyCollection())).thenReturn(List.of());
        assertThat(service.list(false)).isEmpty();
    }

    @Nested
    class Templates {

        @Test
        void aChapterTemplateNeedsAChapterOfTheLatestVersion() {
            latestWithChapters("A", "B");

            assertThatThrownBy(() -> service.create(new ChecklistTemplateService.NewTemplate(ChecklistKind.CHAPTER, null, null)))
                    .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.template.chapter.required"));
            assertThatThrownBy(() -> service.create(new ChecklistTemplateService.NewTemplate(ChecklistKind.CHAPTER, "X", null)))
                    .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.template.chapter.unknown"));
            assertThat(service.create(new ChecklistTemplateService.NewTemplate(ChecklistKind.CHAPTER, "B", null)).chapterKey()).isEqualTo("B");
        }

        @Test
        void aSecondActiveTemplateForAChapterIsAConflict() {
            latestWithChapters("A", "B");
            when(templateRepository.findByChapterKeyAndRetiredAtIsNull("B")).thenReturn(Optional.of(new ChecklistTemplate()));

            assertThatThrownBy(() -> service.create(new ChecklistTemplateService.NewTemplate(ChecklistKind.CHAPTER, "B", null)))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void publishingAVersionWithoutAChapterRetiresItsTemplate() {
            final ChecklistTemplate kept = new ChecklistTemplate();
            final ChecklistTemplate orphaned = new ChecklistTemplate();

            kept.setKind(ChecklistKind.CHAPTER);
            kept.setChapterKey("A");
            orphaned.setKind(ChecklistKind.CHAPTER);
            orphaned.setChapterKey("B");
            latestWithChapters("A");
            when(templateRepository.findByKindAndRetiredAtIsNullOrderByNameSvAscIdAsc(ChecklistKind.CHAPTER)).thenReturn(List.of(kept, orphaned));

            service.guideVersionPublished(new GuideVersionPublished(1L));

            assertThat(kept.isRetired()).isFalse();
            assertThat(orphaned.isRetired()).isTrue();
        }

        @Test
        void chapterTemplatesAreListedInTheLatestVersionsChapterOrderBeforeEventTemplates() {
            final ChecklistTemplate event = new ChecklistTemplate();
            final ChecklistTemplate second = new ChecklistTemplate();
            final ChecklistTemplate first = new ChecklistTemplate();

            event.setId(1L);
            event.setKind(ChecklistKind.EVENT);
            event.setNameSv("Rephelg");
            second.setId(2L);
            second.setKind(ChecklistKind.CHAPTER);
            second.setChapterKey("B");
            first.setId(3L);
            first.setKind(ChecklistKind.CHAPTER);
            first.setChapterKey("A");
            latestWithChapters("A", "B");
            when(templateRepository.findByRetiredAtIsNullOrderByNameSvAscIdAsc()).thenReturn(List.of(event, second, first));

            assertThat(service.list(false)).extracting(ChecklistTemplateDto::id).containsExactly(3L, 2L, 1L);
        }

        @Test
        void anEventTemplateNeedsASwedishName() {
            assertThatThrownBy(() -> service.create(new ChecklistTemplateService.NewTemplate(ChecklistKind.EVENT, null, null)))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.create(new ChecklistTemplateService.NewTemplate(ChecklistKind.EVENT, null,
                    new ChecklistTemplateService.Texts(" ", "Rehearsal weekend")))).isInstanceOf(BadRequestException.class);

            final ChecklistTemplateDto created = service.create(new ChecklistTemplateService.NewTemplate(ChecklistKind.EVENT, null,
                    new ChecklistTemplateService.Texts(" Rephelg ", "")));

            assertThat(created.name()).isNotNull();
            assertThat(created.name().sv()).isEqualTo("Rephelg");
            assertThat(created.name().en()).isNull();
        }

        @Test
        void aRetiredTemplateCannotBeChanged() {
            template(ChecklistKind.EVENT).setRetiredAt(Instant.now());

            assertThatThrownBy(() -> service.update(5L, 0L, new ChecklistTemplateService.Texts("Ny", null))).isInstanceOf(BadRequestException.class);
        }

        @Test
        void retiredTemplatesAreListedOnlyForSuperAdminsWhoAskForThem() {
            service.list(true);
            verify(templateRepository).findAllByOrderByNameSvAscIdAsc();

            as(TestCallers.steeringGroup());
            when(guideAccess.callerRevivals(any())).thenReturn(List.of(TestCallers.revival(RevivalStatus.ORGANISING)));
            service.list(true);
            verify(templateRepository).findByRetiredAtIsNullOrderByNameSvAscIdAsc();
        }

        @Test
        void aRestoredEventTemplateIsActiveAgain() {
            template(ChecklistKind.EVENT).setRetiredAt(Instant.now());

            assertThat(service.restore(5L, 0L).retired()).isFalse();
        }

        @Test
        void restoringATemplateThatIsNotRetiredOrStaleIsAConflict() {
            template(ChecklistKind.EVENT).setRetiredAt(Instant.now());

            assertThatThrownBy(() -> service.restore(5L, 1L)).isInstanceOf(ConflictException.class);

            service.restore(5L, 0L);
            assertThatThrownBy(() -> service.restore(5L, 0L)).isInstanceOf(ConflictException.class);
        }

        @Test
        void aChapterTemplateIsRestoredOnlyWhileItsChapterIsInTheGuideAndFree() {
            template(ChecklistKind.CHAPTER).setRetiredAt(Instant.now());
            latestWithChapters("A");

            assertThatThrownBy(() -> service.restore(5L, 0L))
                    .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.template.chapter.unknown"));

            latestWithChapters("A", "B");
            when(templateRepository.findByChapterKeyAndRetiredAtIsNull("B")).thenReturn(Optional.of(new ChecklistTemplate()));
            assertThatThrownBy(() -> service.restore(5L, 0L)).isInstanceOf(ConflictException.class);

            when(templateRepository.findByChapterKeyAndRetiredAtIsNull("B")).thenReturn(Optional.empty());
            assertThat(service.restore(5L, 0L).retired()).isFalse();
        }

        @Test
        void aRetiredTemplateIsStillReadableByIdForChecklistsMadeFromIt() {
            template(ChecklistKind.EVENT).setRetiredAt(Instant.now());
            as(TestCallers.observer());

            assertThat(service.get(5L).retired()).isTrue();
            assertThatThrownBy(() -> service.get(6L)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class Items {

        @Test
        void textIsNonBlankAndAtMost1000Characters() {
            template(ChecklistKind.EVENT);

            assertThatThrownBy(() -> service.createItem(newItem(" ", null))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.createItem(newItem("x".repeat(1001), null))).isInstanceOf(BadRequestException.class);
            assertThat(service.createItem(newItem("x".repeat(1000), null)).text().sv()).hasSize(1000);
        }

        @Test
        void phaseTemplateItemsHaveNoDeadline() {
            template(ChecklistKind.CHAPTER);

            assertThatThrownBy(() -> service.createItem(newItem("Boka", RelativeDeadline.of(RelativeDeadlineKind.DAYS, -14, null))))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void anEventTemplateItemKeepsItsDeadline() {
            template(ChecklistKind.EVENT);

            final RelativeDeadline deadline = RelativeDeadline.of(RelativeDeadlineKind.WORKING_DAYS, -5, null);

            assertThat(service.createItem(newItem("Boka", deadline)).deadline()).isEqualTo(deadline);
        }

        @Test
        void aNewItemAtAPositionPushesTheOthersDown() {
            template(ChecklistKind.EVENT);
            item(1L, 0);
            item(2L, 1);

            final ChecklistTemplateItemDto created = service.createItem(new ChecklistTemplateService.NewItem(5L, 0,
                    new ChecklistTemplateService.Texts("Först", null), null, null, null));

            assertThat(created.position()).isZero();
            assertThat(items).extracting(ChecklistTemplateItem::getPosition).containsExactly(1, 2);
        }

        @Test
        void moveRenumbersPositionsDensely() {
            template(ChecklistKind.EVENT);
            item(1L, 0);
            item(2L, 4);
            item(3L, 9);

            service.moveItem(3L, 0L, 0);

            assertThat(items).extracting(ChecklistTemplateItem::getId, ChecklistTemplateItem::getPosition)
                    .containsExactly(tuple(1L, 1), tuple(2L, 2),
                            tuple(3L, 0));
        }

        @Test
        void retireKeepsTheRow() {
            template(ChecklistKind.EVENT);
            final ChecklistTemplateItem first = item(1L, 0);
            item(2L, 1);

            final ChecklistTemplateDto template = service.retireItem(1L, 0L);

            assertThat(first.getRetiredAt()).isNotNull();
            assertThat(template.items()).extracting(ChecklistTemplateItemDto::id).containsExactly(2L);
            assertThat(items.get(1).getPosition()).isZero();
        }

        @Test
        void aRestoredItemComesBackLast() {
            template(ChecklistKind.EVENT);
            final ChecklistTemplateItem first = item(1L, 0);
            item(2L, 1);
            item(3L, 2);

            service.retireItem(1L, 0L);
            service.restoreItem(1L, 0L);

            assertThat(first.isRetired()).isFalse();
            assertThat(items).extracting(ChecklistTemplateItem::getId, ChecklistTemplateItem::getPosition)
                    .containsExactly(tuple(1L, 2), tuple(2L, 0), tuple(3L, 1));
        }

        @Test
        void anItemOfARetiredTemplateIsNotRestored() {
            template(ChecklistKind.EVENT).setRetiredAt(Instant.now());
            item(1L, 0).setRetiredAt(Instant.now());

            assertThatThrownBy(() -> service.restoreItem(1L, 0L)).isInstanceOf(BadRequestException.class);
            assertThat(items.getFirst().isRetired()).isTrue();
        }

        @Test
        void onlySuperAdminsSeeRetiredItems() {
            final ChecklistTemplateItem retired = item(1L, 0);

            when(itemRepository.findByTemplateIdAndRetiredAtIsNotNullOrderByRetiredAtDescIdDesc(5L)).thenReturn(List.of(retired));

            assertThat(service.retiredItems(5L)).extracting(ChecklistTemplateItemDto::id).containsExactly(1L);

            as(TestCallers.observer());
            assertThat(service.retiredItems(5L)).isEmpty();
        }

        @Test
        void aStaleVersionIsAConflict() {
            template(ChecklistKind.EVENT);
            item(1L, 0).setVersion(3L);

            assertThatThrownBy(() -> service.retireItem(1L, 2L)).isInstanceOf(ConflictException.class);
        }
    }
}
