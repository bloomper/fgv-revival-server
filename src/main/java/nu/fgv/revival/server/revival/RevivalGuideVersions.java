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

import nu.fgv.revival.server.guide.GuideMapper;
import nu.fgv.revival.server.guide.GuideVersion;
import nu.fgv.revival.server.guide.GuideVersionSummaryDto;
import nu.fgv.revival.server.guide.GuideVersionRepository;
import nu.fgv.revival.server.guide.GuideVersionStatus;
import nu.fgv.revival.server.person.Caller;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Which guide version a revival follows, and the append-only record of every change (FR-017, FR-018, FR-020). The
 * callers check access; this only writes.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Component
public class RevivalGuideVersions {

    private final RevivalRepository revivalRepository;
    private final RevivalGuideVersionChangeRepository changeRepository;
    private final GuideVersionRepository versionRepository;
    private final ApplicationEventPublisher events;

    public RevivalGuideVersions(final RevivalRepository revivalRepository,
                                final RevivalGuideVersionChangeRepository changeRepository,
                                final GuideVersionRepository versionRepository,
                                final ApplicationEventPublisher events) {
        this.revivalRepository = revivalRepository;
        this.changeRepository = changeRepository;
        this.versionRepository = versionRepository;
        this.events = events;
    }

    /**
     * Pins a new revival to the latest published version, if there is one (FR-017).
     */
    public void pinToLatest(final Revival revival, final Caller caller) {
        versionRepository.findFirstByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED)
                .ifPresent(latest -> pin(revival, latest.getId(), caller));
    }

    /**
     * Pins every revival without a version to a version just published, the first one (FR-018).
     */
    public void pinUnpinned(final GuideVersion version, final Caller caller) {
        final List<Revival> unpinned = revivalRepository.findByGuideVersionIdIsNull();

        unpinned.forEach(revival -> pin(revival, version.getId(), caller));
    }

    /**
     * Published versions, newest first.
     */
    public List<GuideVersionSummaryDto> published() {
        return versionRepository.findByStatusOrderByPublishedAtDescIdDesc(GuideVersionStatus.PUBLISHED).stream()
                .map(GuideMapper.GUIDE_MAPPER::toSummary)
                .toList();
    }

    public List<RevivalGuideVersionChange> history(final Long revivalId) {
        return changeRepository.findByRevivalIdOrderByChangedAtDescIdDesc(revivalId);
    }

    /**
     * Moves a revival to another version. Access and the {@code from} check are the caller's.
     */
    public void change(final Revival revival, final Long toVersionId, final Caller caller) {
        pin(revival, toVersionId, caller);
    }

    private void pin(final Revival revival, final Long versionId, final Caller caller) {
        final @Nullable Long from = revival.getGuideVersionId();
        final RevivalGuideVersionChange change = new RevivalGuideVersionChange();

        revival.setGuideVersionId(versionId);
        revivalRepository.save(revival);
        change.setRevivalId(revival.getId());
        change.setFromVersionId(from);
        change.setToVersionId(versionId);
        change.setChangedBy(caller.person().getId());
        change.setChangedAt(Instant.now());
        changeRepository.save(change);
        events.publishEvent(new RevivalGuideVersionPinned(revival.getId(), from, versionId));
    }
}
