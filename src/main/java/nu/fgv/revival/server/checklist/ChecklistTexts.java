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

import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.GuideVersionViews;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Item texts as shown: a required item's text is its guide item's in the revival's pinned version, read from the cached
 * view, so a reworded requirement shows the new text without an update (FR-025, FR-035, research R10).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Component
public class ChecklistTexts {

    private final RevivalRepository revivalRepository;
    private final GuideVersionViews guideVersionViews;
    private final ChecklistItemRepository itemRepository;

    public ChecklistTexts(final RevivalRepository revivalRepository, final GuideVersionViews guideVersionViews,
                          final ChecklistItemRepository itemRepository) {
        this.revivalRepository = revivalRepository;
        this.guideVersionViews = guideVersionViews;
        this.itemRepository = itemRepository;
    }

    /**
     * The pinned guide version of a revival, if it has one. No access check: callers have checked the revival.
     */
    @Transactional(readOnly = true)
    public Optional<GuideVersionView> pinnedVersion(final Long revivalId) {
        return revivalRepository.findById(revivalId)
                .map(Revival::getGuideVersionId)
                .map(guideVersionViews::find);
    }

    @Transactional(readOnly = true)
    public Map<Long, GuideVersionView.Text> texts(final Collection<ChecklistItem> items) {
        final Map<Long, Optional<GuideVersionView>> versions = new HashMap<>();
        final Map<Long, GuideVersionView.Text> texts = new HashMap<>();

        for (final ChecklistItem item : items) {
            if (item.isRequired()) {
                final GuideVersionView.@Nullable Item guideItem = versions.computeIfAbsent(item.getRevivalId(), this::pinnedVersion)
                        .flatMap(version -> version.item(Objects.requireNonNull(item.getGuideItemKey())))
                        .orElse(null);

                texts.put(item.getId(), guideItem == null ? GuideVersionView.Text.plain("", null) : guideItem.text());
            } else {
                texts.put(item.getId(), GuideVersionView.Text.plain(Objects.requireNonNull(item.getText()), null));
            }
        }
        return texts;
    }

    /**
     * Summaries of items rows link to. Callers have checked access to the rows' revival.
     */
    @Transactional(readOnly = true)
    public Map<Long, ChecklistItemSummaryDto> summariesByIds(final Collection<Long> itemIds) {
        return summaries(itemRepository.findAllById(itemIds));
    }

    public Map<Long, ChecklistItemSummaryDto> summaries(final Collection<ChecklistItem> items) {
        final Map<Long, GuideVersionView.Text> texts = texts(items);
        final Map<Long, ChecklistItemSummaryDto> summaries = new HashMap<>();

        items.forEach(item -> summaries.put(item.getId(),
                new ChecklistItemSummaryDto(item.getId(), item.getChecklistId(), texts.get(item.getId()), item.getState())));
        return summaries;
    }
}
