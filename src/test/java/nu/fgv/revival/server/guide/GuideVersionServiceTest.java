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
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class GuideVersionServiceTest {

    private static final long VERSION_ID = 4L;

    @Mock
    private GuideVersionRepository versionRepository;
    @Mock
    private GuideDraftService draftService;
    @Mock
    private GuideVersionViews views;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private DraftSuggestions suggestions;
    @Mock
    private CallerService callerService;

    private GuideVersionService service;
    private GuideVersion version;

    @BeforeEach
    void setUp() {
        service = new GuideVersionService(versionRepository, draftService, views, revivalRepository, Optional.of(suggestions),
                callerService, new AccessPolicy());
        version = new GuideVersion();
        version.setId(VERSION_ID);
        version.setVersion(3L);
        version.setStatus(GuideVersionStatus.PUBLISHED);
        version.setLabel("v4");
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
    }

    @Test
    void usageCountsRevivalsAndSuggestions() {
        when(revivalRepository.countByGuideVersionId(VERSION_ID)).thenReturn(2);
        when(suggestions.madeOn(VERSION_ID)).thenReturn(0);

        final GuideVersionService.Usage usage = service.usage(VERSION_ID);

        assertThat(usage.revivalCount()).isEqualTo(2);
        assertThat(usage.deletable()).isFalse();
    }

    @Test
    void anUnusedVersionIsDeletedWithItsContent() {
        service.delete(VERSION_ID, 3L);

        final InOrder order = inOrder(draftService, versionRepository, views);

        order.verify(draftService).deleteContent(VERSION_ID);
        order.verify(versionRepository).delete(version);
        order.verify(views).evict(VERSION_ID);
    }

    @Test
    void aVersionARevivalFollowsIsNotDeleted() {
        when(revivalRepository.countByGuideVersionId(VERSION_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.delete(VERSION_ID, 3L))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.version.inUse"));
        verify(versionRepository, never()).delete(any());
    }

    @Test
    void aVersionWithSuggestionsIsNotDeleted() {
        when(suggestions.madeOn(VERSION_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.delete(VERSION_ID, 3L)).isInstanceOf(BadRequestException.class);
        verify(draftService, never()).deleteContent(any());
    }

    @Test
    void theDraftIsNotAPublishedVersion() {
        version.setStatus(GuideVersionStatus.DRAFT);

        assertThatThrownBy(() -> service.delete(VERSION_ID, 3L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.delete(VERSION_ID, 2L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void onlySuperAdminsManageVersions() {
        for (final Caller caller : List.of(TestCallers.observer(), TestCallers.directeur(), TestCallers.steeringGroup(), TestCallers.nobody())) {
            when(callerService.current()).thenReturn(caller);
            assertThatThrownBy(() -> service.usage(VERSION_ID)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.delete(VERSION_ID, 3L)).isInstanceOf(AccessDeniedException.class);
        }
        verify(versionRepository, never()).delete(any());
    }
}
