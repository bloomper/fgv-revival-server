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


package nu.fgv.revival.server.decisionlog;

import nu.fgv.revival.server.member.OwnerDto;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A row as GraphQL's {@code LogEntry}. People are display names; {@code overdue} is computed for today in Sweden
 * (FR-011).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public record LogEntryDto(Long id,
                          Long revivalId,
                          int number,
                          LocalDate date,
                          LogEntryType type,
                          String text,
                          LogEntryStatus status,
                          @Nullable OwnerDto owner,
                          @Nullable LocalDate deadline,
                          boolean overdue,
                          @Nullable String comment,
                          List<String> tags,
                          @Nullable Long meetingId,
                          @Nullable String guideItemKey,
                          @Nullable Long checklistItemId,
                          @Nullable Long answersEntryId,
                          LogEntryOrigin origin,
                          @Nullable String createdBy,
                          Instant createdAt,
                          @Nullable String lastModifiedBy,
                          @Nullable Instant lastModifiedAt,
                          Long version) {
}
