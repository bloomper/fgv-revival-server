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
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;

/**
 * A checklist as GraphQL's {@code Checklist}. A chapter checklist's title and tips come from the pinned guide version
 * (FR-029).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public record ChecklistDto(Long id,
                           Long revivalId,
                           ChecklistKind kind,
                           @Nullable String chapterKey,
                           GuideVersionView.@Nullable Text chapterTitle,
                           @Nullable String name,
                           @Nullable LocalDate date,
                           @Nullable Long templateId,
                           @Nullable Long eventId,
                           boolean cancelled,
                           List<ChecklistItemDto> items,
                           List<GuideVersionView.Item> tips,
                           Long version) {
}
