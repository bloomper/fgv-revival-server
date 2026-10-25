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


package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.guide.GuideVersion;
import nu.fgv.revival.server.guide.GuideVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every pin publishes exactly one {@link RevivalGuideVersionPinned}, which the checklists follow.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class RevivalGuideVersionsTest {

    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private RevivalGuideVersionChangeRepository changeRepository;
    @Mock
    private GuideVersionRepository versionRepository;
    @Mock
    private ApplicationEventPublisher events;

    private static Revival revival(final long id, final Long versionId) {
        final Revival revival = TestCallers.revival(RevivalStatus.ORGANISING);

        revival.setId(id);
        revival.setGuideVersionId(versionId);
        return revival;
    }

    private RevivalGuideVersions guideVersions() {
        return new RevivalGuideVersions(revivalRepository, changeRepository, versionRepository, events);
    }

    private void labelled(final long id, final String label) {
        final GuideVersion version = new GuideVersion();

        version.setId(id);
        version.setLabel(label);
        when(versionRepository.findById(id)).thenReturn(Optional.of(version));
    }

    @Test
    void aChangePublishesFromAndTo() {
        labelled(5L, "v5");
        labelled(6L, "v6");

        guideVersions().change(revival(1L, 5L), 6L, TestCallers.directeur());

        verify(events).publishEvent(new RevivalGuideVersionPinned(1L, 5L, 6L));
    }

    @Test
    void aChangeKeepsTheLabelsSoItStillReadsOnceAVersionIsDeleted() {
        labelled(5L, "v5");
        labelled(6L, "v6");
        final ArgumentCaptor<RevivalGuideVersionChange> change = ArgumentCaptor.forClass(RevivalGuideVersionChange.class);

        guideVersions().change(revival(1L, 5L), 6L, TestCallers.directeur());

        verify(changeRepository).save(change.capture());
        assertThat(change.getValue().getFromLabel()).isEqualTo("v5");
        assertThat(change.getValue().getToLabel()).isEqualTo("v6");
    }

    @Test
    void theFirstPublicationPublishesOnePerUnpinnedRevival() {
        final GuideVersion version = new GuideVersion();

        version.setId(5L);
        labelled(5L, "v1");
        when(revivalRepository.findByGuideVersionIdIsNull()).thenReturn(List.of(revival(1L, null), revival(2L, null)));

        guideVersions().pinUnpinned(version, TestCallers.superAdmin());

        verify(events).publishEvent(new RevivalGuideVersionPinned(1L, null, 5L));
        verify(events).publishEvent(new RevivalGuideVersionPinned(2L, null, 5L));
        verify(events, times(2)).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
    }
}
