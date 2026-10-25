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

import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;

/**
 * GraphQL's {@code ChecklistTemplateSummary}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record ChecklistTemplateSummaryDto(Long id, ChecklistKind kind, @Nullable String chapterKey,
                                          GuideVersionView.@Nullable Text name,
                                          boolean retired) {

    public static ChecklistTemplateSummaryDto of(final ChecklistTemplate template) {
        return new ChecklistTemplateSummaryDto(template.getId(), template.getKind(), template.getChapterKey(), name(template), template.isRetired());
    }

    static GuideVersionView.@Nullable Text name(final ChecklistTemplate template) {
        return template.getNameSv() == null ? null : GuideVersionView.Text.plain(template.getNameSv(), template.getNameEn());
    }
}
