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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Published versions for super admins: how much each is used, and deleting one nothing uses. A published version
 * never changes (SC-003).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Service
public class GuideVersionService {

    private final GuideVersionRepository versionRepository;
    private final GuideDraftService draftService;
    private final GuideVersionViews views;
    private final RevivalRepository revivalRepository;
    private final Optional<DraftSuggestions> suggestions;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;

    public GuideVersionService(final GuideVersionRepository versionRepository,
                               final GuideDraftService draftService,
                               final GuideVersionViews views,
                               final RevivalRepository revivalRepository,
                               final Optional<DraftSuggestions> suggestions,
                               final CallerService callerService,
                               final AccessPolicy accessPolicy) {
        this.versionRepository = versionRepository;
        this.draftService = draftService;
        this.views = views;
        this.revivalRepository = revivalRepository;
        this.suggestions = suggestions;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
    }

    /**
     * How many revivals follow a version, and how many suggestions were made on it. A version is deletable when both
     * are zero.
     */
    public record Usage(int revivalCount, int suggestionCount) {

        public boolean deletable() {
            return revivalCount == 0 && suggestionCount == 0;
        }
    }

    @Transactional(readOnly = true)
    public Usage usage(final Long id) {
        accessPolicy.requireManageGuide(callerService.current());
        return count(id);
    }

    private Usage count(final Long id) {
        return new Usage(revivalRepository.countByGuideVersionId(id), suggestions.map(found -> found.madeOn(id)).orElse(0));
    }

    private GuideVersion published(final Long id, final Long expectedVersion) {
        final GuideVersion version = versionRepository.findById(id).filter(found -> !found.isDraft())
                .orElseThrow(() -> new ResourceNotFoundException(GuideVersion.class, id));

        GuideDraftService.requireVersion(version, expectedVersion);
        return version;
    }

    /**
     * Deletes a published version no revival follows and no suggestion was made on. Revivals' records of the versions
     * they followed keep its label. A draft based on it is then based on nothing.
     */
    @Transactional
    public void delete(final Long id, final Long expectedVersion) {
        accessPolicy.requireManageGuide(callerService.current());

        final GuideVersion version = published(id, expectedVersion);

        if (!count(id).deletable()) {
            throw new BadRequestException("error.guide.version.inUse");
        }
        AuditContext.operation("guideVersionDelete");
        draftService.deleteContent(id);
        versionRepository.delete(version);
        versionRepository.flush();
        views.evict(id);
    }
}
