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

import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;

/**
 * GraphQL's {@code ChecklistTemplateItem}; its {@code guideItem} is resolved in the latest published version.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public record ChecklistTemplateItemDto(Long id, Long templateId, int position, GuideVersionView.Text text,
                                       @Nullable String guideItemKey,
                                       @Nullable String defaultCapability, @Nullable RelativeDeadline deadline,
                                       boolean retired, Long version) {

    public static ChecklistTemplateItemDto of(final ChecklistTemplateItem item) {
        return new ChecklistTemplateItemDto(item.getId(), item.getTemplateId(), item.getPosition(),
                GuideVersionView.Text.plain(item.getTextSv(), item.getTextEn()), item.getGuideItemKey(), item.getDefaultCapability(),
                item.getDeadline(), item.isRetired(), item.getVersion());
    }
}
