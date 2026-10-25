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
import nu.fgv.revival.server.library.LibraryService;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalGuideVersions;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class GuideDraftServicePublishTest {

    private static final long DRAFT_ID = 7L;
    private final GuideDraftService.DraftDiscardListener listener = mock(GuideDraftService.DraftDiscardListener.class);
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
    @Mock
    private ApplicationEventPublisher events;
    private GuideDraftService service;
    private GuideVersion draft;

    private static List<GuideChapter> chapters(final String firstTitle) {
        return GuideTestChapters.KEYS.stream().map(key -> {
            final GuideChapter chapter = new GuideChapter();

            chapter.setKey(key);
            chapter.setPosition(GuideTestChapters.KEYS.indexOf(key));
            chapter.setTitleSv(key.equals("A") ? firstTitle : "Kapitel " + key);
            return chapter;
        }).toList();
    }

    @BeforeEach
    void setUp() {
        service = new GuideDraftService(versionRepository, chapterRepository, sectionRepository, itemRepository,
                views, revivalGuideVersions, callerService, new AccessPolicy(), List.of(listener), events, mock(GuideItemTypeService.class), mock(LibraryService.class));
        draft = new GuideVersion();
        draft.setId(DRAFT_ID);
        draft.setVersion(2L);
        draft.setStatus(GuideVersionStatus.DRAFT);
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(versionRepository.findById(DRAFT_ID)).thenReturn(Optional.of(draft));
        lenient().when(chapterRepository.findByVersionIdOrderByPosition(DRAFT_ID)).thenReturn(chapters("Först"));
        lenient().when(views.find(DRAFT_ID)).thenReturn(mock(GuideVersionView.class));
    }

    @Test
    void publishSetsTheFieldsPinsRevivalsAndTellsListeners() {
        when(versionRepository.findLabels()).thenReturn(List.of("v1", "v4", "v2"));

        service.publish(DRAFT_ID, 2L, " Första versionen i appen ");

        assertThat(draft.getStatus()).isEqualTo(GuideVersionStatus.PUBLISHED);
        assertThat(draft.getLabel()).isEqualTo("v5");
        assertThat(draft.getNote()).isEqualTo("Första versionen i appen");
        assertThat(draft.getPublishedAt()).isNotNull();
        assertThat(draft.getPublishedBy()).isEqualTo(TestCallers.PERSON_ID);

        final InOrder order = inOrder(versionRepository, views, revivalGuideVersions, events);

        order.verify(versionRepository).saveAndFlush(draft);
        order.verify(views).evict(DRAFT_ID);
        order.verify(revivalGuideVersions).pinUnpinned(any(), any(Caller.class));
        order.verify(events).publishEvent(new GuideVersionPublished(DRAFT_ID));
    }

    @Test
    void theFirstVersionIsV1AndLabelsNotOfTheFormAreIgnored() {
        when(versionRepository.findLabels()).thenReturn(List.of("2024", "v2b"));

        service.publish(DRAFT_ID, 2L, null);

        assertThat(draft.getLabel()).isEqualTo("v1");
        assertThat(draft.getNote()).isNull();
    }

    @Test
    void aNoteOver2000CharactersIsRefused() {
        assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, "x".repeat(2001)))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.note.tooLong"));
        verify(revivalGuideVersions, never()).pinUnpinned(any(), any());
    }

    @Test
    void aDraftWithABlankTitleIsRefused() {
        when(chapterRepository.findByVersionIdOrderByPosition(DRAFT_ID)).thenReturn(chapters(" "));

        assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.incomplete"));
    }

    @Test
    void aDraftWithoutChaptersIsRefused() {
        when(chapterRepository.findByVersionIdOrderByPosition(DRAFT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.incomplete"));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void aStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.publish(DRAFT_ID, 1L, null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aPublishedVersionIsNotADraft() {
        draft.setStatus(GuideVersionStatus.PUBLISHED);

        assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, null))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.published"));
        assertThatThrownBy(() -> service.discard(DRAFT_ID, 2L))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.published"));
    }

    @Test
    void onlySuperAdminsPublishOrDiscard() {
        for (final Caller caller : List.of(TestCallers.observer(), TestCallers.directeur(), TestCallers.steeringGroup(), TestCallers.nobody())) {
            when(callerService.current()).thenReturn(caller);
            assertThatThrownBy(() -> service.publish(DRAFT_ID, 2L, null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.discard(DRAFT_ID, 2L)).isInstanceOf(AccessDeniedException.class);
        }
        verify(versionRepository, never()).saveAndFlush(any());
        verify(versionRepository, never()).delete(any());
    }

    @Test
    void discardTellsListenersThenDeletesEverything() {
        service.discard(DRAFT_ID, 2L);

        final InOrder order = inOrder(listener, itemRepository, sectionRepository, chapterRepository, versionRepository);

        order.verify(listener).draftDiscarded(DRAFT_ID);
        order.verify(itemRepository).deleteByVersionId(DRAFT_ID);
        order.verify(sectionRepository).deleteByVersionId(DRAFT_ID);
        order.verify(chapterRepository).deleteByVersionId(DRAFT_ID);
        order.verify(versionRepository).delete(draft);
    }

    @Test
    void discardWithAStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.discard(DRAFT_ID, 9L)).isInstanceOf(ConflictException.class);
        verify(versionRepository, never()).delete(any());
    }
}
