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

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * GraphQL's {@code LogEntryFilter}: every given filter must match; within types and tags any value may.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record LogEntryFilterDto(@Nullable Boolean mine,
                                @Nullable Boolean open,
                                @Nullable Boolean overdue,
                                @Nullable List<LogEntryType> types,
                                @Nullable Long meetingId,
                                @Nullable List<String> tags,
                                @Nullable String text) {

    public static LogEntryFilterDto none() {
        return new LogEntryFilterDto(null, null, null, null, null, null, null);
    }
}
