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
import nu.fgv.revival.server.checklist.ChecklistItemFilterDto;
import nu.fgv.revival.server.checklist.ChecklistItemState;
import nu.fgv.revival.server.checklist.ChecklistService;
import nu.fgv.revival.server.decisionlog.LogEntryDto;
import nu.fgv.revival.server.decisionlog.LogEntryFilterDto;
import nu.fgv.revival.server.decisionlog.LogEntryService;
import nu.fgv.revival.server.guide.Phase;
import nu.fgv.revival.server.meeting.MeetingService;
import nu.fgv.revival.server.meeting.MeetingStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The composite view of a revival (research R15). Kept apart from {@link RevivalService} so that {@code revival}
 * depends on the decision log and checklists only here; each service checks the caller's access itself.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Service
public class RevivalStatusService {

    private final RevivalService revivalService;
    private final LogEntryService logEntryService;
    private final ChecklistService checklistService;
    private final MeetingService meetingService;

    public RevivalStatusService(final RevivalService revivalService, final LogEntryService logEntryService, final ChecklistService checklistService,
                                final MeetingService meetingService) {
        this.revivalService = revivalService;
        this.logEntryService = logEntryService;
        this.checklistService = checklistService;
        this.meetingService = meetingService;
    }

    /**
     * Counts for the revival page; "open" rows are open or waiting, as in the log's filter (FR-019).
     */
    @Transactional(readOnly = true)
    public RevivalWorkSummaryDto workSummary(final Long revivalId, final Locale locale) {
        final List<LogEntryDto> open = logEntryService.list(revivalId, new LogEntryFilterDto(null, true, null, null, null, null, null), locale);
        final List<ChecklistItemDto> openItems = checklistService.items(revivalId, new ChecklistItemFilterDto(null, true, null, null, null), locale);

        return new RevivalWorkSummaryDto(open.size(), (int) open.stream().filter(LogEntryDto::overdue).count(),
                (int) openItems.stream().filter(ChecklistItemDto::overdue).count(), (int) openItems.stream().filter(ChecklistItemDto::required).count(),
                meetingService.list(revivalId).stream().filter(meeting -> meeting.status() == MeetingStatus.IN_PROGRESS).findFirst().orElse(null));
    }

    @Transactional(readOnly = true)
    public RevivalStatusDto status(final Long revivalId, final Locale locale) {
        final RevivalDto revival = revivalService.findById(revivalId);
        final Map<Phase, List<ChecklistItemDto>> required = checklistService.items(revivalId,
                        new ChecklistItemFilterDto(null, null, null, null, true), locale).stream()
                .filter(item -> item.checklist().phase() != null)
                .collect(Collectors.groupingBy(item -> Objects.requireNonNull(item.checklist().phase())));

        return new RevivalStatusDto(
                new RevivalStatusDto.Summary(revival.id(), Objects.requireNonNullElse(revival.spex().title(), revival.workingTitle()), revival.year(),
                        revival.status()),
                logEntryService.list(revivalId, new LogEntryFilterDto(null, null, true, null, null, null, null), locale),
                checklistService.items(revivalId, new ChecklistItemFilterDto(null, null, true, null, null), locale),
                Arrays.stream(Phase.values()).map(phase -> {
                    final List<ChecklistItemDto> items = required.getOrDefault(phase, List.of());

                    return new RevivalStatusDto.OpenRequirements(phase,
                            (int) items.stream().filter(item -> item.state() == ChecklistItemState.OPEN).count(), items.size());
                }).toList());
    }
}
