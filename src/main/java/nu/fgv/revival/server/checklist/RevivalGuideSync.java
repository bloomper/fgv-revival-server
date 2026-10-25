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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.audit.AuditSource;
import nu.fgv.revival.server.decisionlog.LogEntryCounters;
import nu.fgv.revival.server.decisionlog.LogEntryService;
import nu.fgv.revival.server.guide.GuideItemKind;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalCreated;
import nu.fgv.revival.server.revival.RevivalGuideVersionPinned;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps a revival's chapter checklists, required items and guide question rows in line with the guide version it is
 * pinned to. Idempotent. Runs in the transaction of the pin or creation that
 * triggered it, so both succeed or fail together, and once at startup for every revival that is not archived.
 *
 * <p>It writes through internal methods that do not ask {@code AccessPolicy}: the triggering operation was checked, and
 * super admins, who publish versions and create revivals, may not write the log or checklists themselves.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@Slf4j
public class RevivalGuideSync implements ApplicationRunner {

    private final RevivalRepository revivalRepository;
    private final ChecklistService checklistService;
    private final ChecklistTexts texts;
    private final LogEntryService logEntryService;
    private final LogEntryCounters counters;
    private final TransactionTemplate transactions;

    public RevivalGuideSync(final RevivalRepository revivalRepository,
                            final ChecklistService checklistService,
                            final ChecklistTexts texts,
                            final LogEntryService logEntryService,
                            final LogEntryCounters counters,
                            final TransactionTemplate transactions) {
        this.revivalRepository = revivalRepository;
        this.checklistService = checklistService;
        this.texts = texts;
        this.logEntryService = logEntryService;
        this.counters = counters;
        this.transactions = transactions;
    }

    private static Map<String, String> questions(final GuideVersionView version) {
        final Map<String, String> questions = new LinkedHashMap<>();

        version.items().filter(item -> item.kind() == GuideItemKind.QUESTION).forEach(item -> questions.put(item.key(), item.text().plainSv()));
        return questions;
    }

    @EventListener
    public void revivalCreated(final RevivalCreated event) {
        sync(event.revivalId());
    }

    @EventListener
    public void guideVersionPinned(final RevivalGuideVersionPinned event) {
        sync(event.revivalId());
    }

    @Override
    public void run(final ApplicationArguments arguments) {
        final List<Long> revivals = Optional.ofNullable(transactions.execute(status -> revivalRepository.findAll().stream()
                .filter(revival -> revival.getStatus() != RevivalStatus.ARCHIVED)
                .map(Revival::getId)
                .toList())).orElse(List.of());

        for (final Long revivalId : revivals) {
            transactions.executeWithoutResult(status -> {
                AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, "guideSync", null));
                sync(revivalId);
            });
        }
        log.info("Synced the checklists of {} revivals with their guide versions", revivals.size());
    }

    /**
     * Chapter checklists, required items and question rows for one revival, in the caller's transaction. A revival
     * without a pinned version has no chapter checklists. Checklists for chapters the version no longer has are deleted
     * after the required items have followed their requirements.
     */
    public void sync(final Long revivalId) {
        counters.ensure(revivalId);
        texts.pinnedVersion(revivalId).ifPresent(version -> {
            final List<Checklist> created = checklistService.ensureChapterChecklists(revivalId, version);

            checklistService.syncRequiredItems(revivalId, version);
            checklistService.deleteRemovedChapterChecklists(revivalId, version);
            logEntryService.createGuideQuestions(revivalId, questions(version));
            checklistService.addChapterTemplateItems(created);
        });
    }
}
