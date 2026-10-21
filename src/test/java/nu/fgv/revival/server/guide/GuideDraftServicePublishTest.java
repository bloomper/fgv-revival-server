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
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalGuideVersions;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 0.2
 */
@ExtendWith(MockitoExtension.class)
class GuideDraftServicePublishTest {

    private static final long DRAFT_ID = 7L;

    @Mock
    private GuideVersionRepository versionRepository;
    @Mock
    private GuideChapterRepository chapterRepository;
    @Mock
    private GuideSectionRepository sectionRepository;
    @Mock
    private GuideItemRepository itemRepository;
    @Mock
    private GuideImportReportRepository reportRepository;
    @Mock
    private GuideVersionViews views;
    @Mock
    private RevivalGuideVersions revivalGuideVersions;
    @Mock
    private CallerService callerService;

    private final GuideDraftService.DraftDiscardListener listener = mock(GuideDraftService.DraftDiscardListener.class);
    private GuideDraftService service;
    private GuideVersion draft;

    private static List<GuideChapter> chapters(final String firstTitle) {
        return Arrays.stream(Phase.values()).map(phase -> {
            final GuideChapter chapter = new GuideChapter();

            chapter.setPhase(phase);
            chapter.setTitleSv(phase == Phase.A ? firstTitle : "Kapitel " + phase);
            return chapter;
        }).toList();
    }

    @BeforeEach
    void setUp() {
        service = new GuideDraftService(versionRepository, chapterRepository, sectionRepository, itemRepository, reportRepository,
                views, revivalGuideVersions, callerService, new AccessPolicy(), List.of(listener));
        draft = new GuideVersion();
        draft.setId(DRAFT_ID);
        draft.setVersion(2L);
        draft.setStatus(GuideVersionStatus.DRAFT);
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(versionRepository.findById(DRAFT_ID)).thenReturn(Optional.of(draft));
        lenient().when(chapterRepository.findByVersionIdOrderByPhase(DRAFT_ID)).thenReturn(chapters("Först"));
        lenient().when(views.find(DRAFT_ID)).thenReturn(mock(GuideVersionView.class));
    }

    @Test
    void publishSetsTheFieldsPinsRevivalsAndDropsTheReport() {
        service.publish(DRAFT_ID, 2L, " v5 ", "Första versionen i appen");

        assertThat(draft.getStatus()).isEqualTo(GuideVersionStatus.PUBLISHED);
        assertThat(draft.getLabel()).isEqualTo("v5");
        assertThat(draft.getNote()).isEqualTo("Första versionen i appen");
        assertThat(draft.getPublishedAt()).isNotNull();
        assertThat(draft.getPublishedBy()).isEqualTo(TestCallers.PERSON_ID);

        final InOrder order = inOrder(versionRepository, revivalGuideVersions, reportRepository, views);

        order.verify(versionRepository).saveAndFlush(draft);
        order.verify(revivalGuideVersions).pinUnpinned(any(), any(Caller.class));
        order.verify(reportRepository).deleteByVersionId(DRAFT_ID);
        order.verify(views).evict(DRAFT_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void aBlankLabelIsRefused(final String label) {
        assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, label, null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.label.blank"));
    }

    @Test
    void aLabelOver32CharactersIsRefused() {
        assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, "v".repeat(33), null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.label.tooLong"));
    }

    @Test
    void aLabelInUseIsRefused() {
        when(versionRepository.existsByLabelIgnoreCase("v5")).thenReturn(true);

        assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, "v5", null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.label.duplicate"));
        verify(revivalGuideVersions, never()).pinUnpinned(any(), any());
    }

    @Test
    void aDraftWithABlankTitleIsRefused() {
        when(chapterRepository.findByVersionIdOrderByPhase(DRAFT_ID)).thenReturn(chapters(" "));

        assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, "v5", null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.incomplete"));
    }

    @Test
    void aStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.publish(DRAFT_ID, 1L, "v5", null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aPublishedVersionIsNotADraft() {
        draft.setStatus(GuideVersionStatus.PUBLISHED);

        assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, "v6", null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.published"));
        assertThatThrownBy(() -> service.discard(DRAFT_ID, 2L))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.published"));
    }

    @Test
    void onlySuperAdminsPublishOrDiscard() {
        for (final Caller caller : List.of(TestCallers.observer(), TestCallers.directeur(), TestCallers.steeringGroup(), TestCallers.nobody())) {
            when(callerService.current()).thenReturn(caller);
            assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, "v5", null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.discard(DRAFT_ID, 2L)).isInstanceOf(AccessDeniedException.class);
        }
        verify(versionRepository, never()).saveAndFlush(any());
        verify(versionRepository, never()).delete(any());
    }

    @Test
    void discardTellsListenersThenDeletesEverything() {
        service.discard(DRAFT_ID, 2L);

        final InOrder order = inOrder(listener, itemRepository, sectionRepository, chapterRepository, reportRepository, versionRepository);

        order.verify(listener).draftDiscarded(DRAFT_ID);
        order.verify(itemRepository).deleteByVersionId(DRAFT_ID);
        order.verify(sectionRepository).deleteByVersionId(DRAFT_ID);
        order.verify(chapterRepository).deleteByVersionId(DRAFT_ID);
        order.verify(reportRepository).deleteByVersionId(DRAFT_ID);
        order.verify(versionRepository).delete(draft);
    }

    @Test
    void discardWithAStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.discard(DRAFT_ID, 9L)).isInstanceOf(ConflictException.class);
        verify(versionRepository, never()).delete(any());
        verify(versionRepository, never()).existsByLabelIgnoreCase(anyString());
    }
}
