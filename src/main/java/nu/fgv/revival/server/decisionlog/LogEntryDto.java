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
import java.util.UUID;

/**
 * A row as GraphQL's {@code LogEntry}. People are display names; {@code overdue} is computed for today in Sweden.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record LogEntryDto(UUID id,
                          UUID revivalId,
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
                          @Nullable UUID meetingId,
                          @Nullable String guideItemKey,
                          @Nullable UUID checklistItemId,
                          @Nullable UUID eventId,
                          @Nullable UUID answersEntryId,
                          LogEntryOrigin origin,
                          @Nullable String createdBy,
                          Instant createdAt,
                          @Nullable String lastModifiedBy,
                          @Nullable Instant lastModifiedAt,
                          Long version) {
}
