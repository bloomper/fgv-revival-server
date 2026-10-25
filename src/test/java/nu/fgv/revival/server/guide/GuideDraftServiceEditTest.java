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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalGuideVersions;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Starting a draft and the editing rules. Positions and moves against a real
 * database are in {@code GuideDraftGraphqlApiIntegrationTest}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class GuideDraftServiceEditTest {

    @Nested
    class Rules {

        @Test
        void swedishItemTextIsRequiredAndAtMost5000() {
            assertThatThrownBy(() -> GuideRules.itemTextSv(" ")).isInstanceOfSatisfying(BadRequestException.class,
                    e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.text.blank"));
            assertThatThrownBy(() -> GuideRules.itemTextSv("x".repeat(5001))).isInstanceOfSatisfying(BadRequestException.class,
                    e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.text.tooLong"));
            assertThat(GuideRules.itemTextSv(" " + "x".repeat(5000) + " ")).hasSize(5000);
        }

        @Test
        void englishTextIsOptionalAndBlankClearsIt() {
            assertThat(GuideRules.itemTextEn(null)).isNull();
            assertThat(GuideRules.itemTextEn("  ")).isNull();
            assertThat(GuideRules.itemTextEn(" A tip. ")).isEqualTo("A tip.");
            assertThatThrownBy(() -> GuideRules.itemTextEn("x".repeat(5001))).isInstanceOf(BadRequestException.class);
        }

        @Test
        void titlesAreAtMost255AndNotBlankInSwedish() {
            assertThatThrownBy(() -> GuideRules.titleSv("")).isInstanceOfSatisfying(BadRequestException.class,
                    e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.title.blank"));
            assertThatThrownBy(() -> GuideRules.titleSv("x".repeat(256))).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> GuideRules.titleEn("x".repeat(256))).isInstanceOf(BadRequestException.class);
            assertThat(GuideRules.titleEn(" ")).isNull();
        }

        @Test
        void theSwedishIntroductionMayBeEmptyAndIsAtMost20000() {
            assertThat(GuideRules.introductionSv(null)).isEmpty();
            assertThat(GuideRules.introductionSv("  ")).isEmpty();
            assertThatThrownBy(() -> GuideRules.introductionSv("x".repeat(20_001))).isInstanceOfSatisfying(BadRequestException.class,
                    e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.introduction.tooLong"));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Start {

        @Mock
        private GuideVersionRepository versionRepository;
        @Mock
        private GuideChapterRepository chapterRepository;
        @Mock
        private GuideSectionRepository sectionRepository;
        @Mock
        private GuideItemRepository itemRepository;
        @Mock
        private GuideVersionViews views;
        @Mock
        private RevivalGuideVersions revivalGuideVersions;
        @Mock
        private CallerService callerService;

        private GuideDraftService service;

        @BeforeEach
        void setUp() {
            service = new GuideDraftService(versionRepository, chapterRepository, sectionRepository, itemRepository,
                    views, revivalGuideVersions, callerService, new AccessPolicy(), List.of(), event -> {
            }, mock(GuideItemTypeService.class));
            lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
            lenient().when(versionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
                final GuideVersion version = invocation.getArgument(0);

                version.setId(20L);
                return version;
            });
        }

        @Test
        void aSecondDraftIsAConflict() {
            when(versionRepository.existsByStatus(GuideVersionStatus.DRAFT)).thenReturn(true);

            assertThatThrownBy(() -> service.start()).isInstanceOfSatisfying(ConflictException.class,
                    e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.draftExists"));
            verify(versionRepository, never()).saveAndFlush(any());
        }

        @Test
        void beforeTheFirstPublicationTheDraftHasNoChapters() {
            when(versionRepository.findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED)).thenReturn(Optional.empty());

            service.start();

            verify(versionRepository).saveAndFlush(any());
            verify(chapterRepository, never()).save(any());
        }

        @Test
        void aDraftCopiesTheLatestVersionWithTheSameKeys() {
            final GuideVersion latest = new GuideVersion();
            final GuideChapter chapter = new GuideChapter();
            final GuideSection section = new GuideSection();
            final GuideItem item = new GuideItem();

            latest.setId(5L);
            latest.setIntroSv("Inledning");
            latest.setIntroEn("Introduction");
            chapter.setId(50L);
            chapter.setKey("chapter-key");
            chapter.setPosition(0);
            chapter.setTitleSv("Först");
            section.setId(60L);
            section.setChapterId(50L);
            section.setKey("section-key");
            section.setPosition(0);
            section.setTitleSv("Avsnitt");
            item.setId(70L);
            item.setChapterId(50L);
            item.setSectionId(60L);
            item.setKey("item-key");
            item.setPosition(0);
            item.setType("TIP");
            item.setTextSv("Tips");
            when(versionRepository.findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED)).thenReturn(Optional.of(latest));
            when(chapterRepository.findByVersionIdOrderByPosition(5L)).thenReturn(List.of(chapter));
            when(sectionRepository.findByVersionIdOrderByChapterIdAscPositionAsc(5L)).thenReturn(List.of(section));
            when(itemRepository.findByVersionIdOrderByChapterIdAscPositionAsc(5L)).thenReturn(List.of(item));
            when(chapterRepository.save(any())).thenAnswer(invocation -> {
                final GuideChapter copy = invocation.getArgument(0);

                copy.setId(51L);
                return copy;
            });
            when(sectionRepository.save(any())).thenAnswer(invocation -> {
                final GuideSection copy = invocation.getArgument(0);

                copy.setId(61L);
                return copy;
            });

            service.start();

            final ArgumentCaptor<GuideVersion> draft = ArgumentCaptor.forClass(GuideVersion.class);
            final ArgumentCaptor<GuideChapter> chapterCopy = ArgumentCaptor.forClass(GuideChapter.class);
            final ArgumentCaptor<GuideSection> sectionCopy = ArgumentCaptor.forClass(GuideSection.class);
            final ArgumentCaptor<GuideItem> itemCopy = ArgumentCaptor.forClass(GuideItem.class);

            verify(versionRepository).saveAndFlush(draft.capture());
            verify(chapterRepository).save(chapterCopy.capture());
            verify(sectionRepository).save(sectionCopy.capture());
            verify(itemRepository).save(itemCopy.capture());
            assertThat(draft.getValue().getBasedOnId()).isEqualTo(5L);
            assertThat(draft.getValue().getIntroEn()).isEqualTo("Introduction");
            assertThat(chapterCopy.getValue().getKey()).isEqualTo("chapter-key");
            assertThat(chapterCopy.getValue().getPosition()).isZero();
            assertThat(sectionCopy.getValue().getKey()).isEqualTo("section-key");
            assertThat(sectionCopy.getValue().getChapterId()).isEqualTo(51L);
            assertThat(itemCopy.getValue().getKey()).isEqualTo("item-key");
            assertThat(itemCopy.getValue().getSectionId()).isEqualTo(61L);
            assertThat(itemCopy.getValue().getVersionId()).isEqualTo(20L);
        }

        @Test
        void onlySuperAdminsStartADraft() {
            when(callerService.current()).thenReturn(TestCallers.observer());

            assertThatThrownBy(() -> service.start()).isInstanceOf(AccessDeniedException.class);
        }
    }
}
