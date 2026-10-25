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
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.PersonRefDto;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A checklist item as GraphQL's {@code ChecklistItem}. A required item's text is the guide's, in the revival's pinned
 * version; {@code reason} is rendered in the reader's language.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record ChecklistItemDto(Long id,
                               ChecklistSummaryDto checklist,
                               boolean required,
                               GuideVersionView.Text text,
                               ChecklistItemState state,
                               @Nullable PersonRefDto stateChangedBy,
                               @Nullable Instant stateChangedAt,
                               @Nullable String reason,
                               @Nullable OwnerDto owner,
                               @Nullable LocalDate deadline,
                               boolean deadlineManual,
                               boolean overdue,
                               @Nullable String comment,
                               GuideVersionView.@Nullable Item guideItem,
                               @Nullable String guideItemKey,
                               @Nullable String defaultCapability,
                               Long version) {
}
