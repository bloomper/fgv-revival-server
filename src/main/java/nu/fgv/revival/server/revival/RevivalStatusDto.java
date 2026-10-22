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


package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.checklist.ChecklistItemDto;
import nu.fgv.revival.server.decisionlog.LogEntryDto;
import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A revival's state at a glance for MCP's get_revival_status (research R15). M4–M6 add events, the current phase,
 * responses and the budget.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public record RevivalStatusDto(Summary revival,
                               List<LogEntryDto> overdueLogEntries,
                               List<ChecklistItemDto> overdueChecklistItems,
                               List<OpenRequirements> openRequirements) {

    public record Summary(Long id, String title, Integer year, RevivalStatus status) {
    }

    public record OpenRequirements(String chapterKey, GuideVersionView.@Nullable Text chapterTitle, int open, int total) {
    }
}
