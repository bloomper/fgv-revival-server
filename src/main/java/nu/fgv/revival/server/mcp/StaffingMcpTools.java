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

package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.staffing.StaffingDtos.ShiftSlotDto;
import nu.fgv.revival.server.staffing.StaffingService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * A revival's staffing over MCP, read only, for readers of staffing: shift slots with their task, time,
 * how many are needed, who holds them by name and how many places are open. Never contact details.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class StaffingMcpTools {

    private final StaffingService staffingService;
    private final McpToolSupport support;

    public StaffingMcpTools(final StaffingService staffingService, final McpToolSupport support) {
        this.staffingService = staffingService;
        this.support = support;
    }

    static Slot toMcp(final ShiftSlotDto slot) {
        return new Slot(slot.id(), slot.event().id(), slot.event().title(), slot.date(), slot.startTime(), slot.endTime(), slot.task(), slot.needed(),
                slot.assigned().stream().map(assigned -> assigned.participant().displayName()).toList(), slot.open(), slot.comment());
    }

    @McpTool(name = "get_staffing",
            description = "List a revival's shift slots for its events that are not cancelled, by date and time: the event, the task, the time, how many people are needed, who holds each slot by name, and how many places are open. Never contact details.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<Slot> getStaffing(
            @McpToolParam(description = "A revival you can see") final Long revivalId,
            @McpToolParam(required = false, description = "Only this event, by id") final @Nullable Long eventId) {
        return support.call("get_staffing", () -> new McpToolSupport.ListResult<>(staffingService.slots(revivalId, eventId, Locale.ENGLISH).stream()
                .map(StaffingMcpTools::toMcp)
                .toList()));
    }

    public record Slot(Long id, Long eventId, String event, LocalDate date, String startTime, String endTime,
                       String task, int needed,
                       List<String> assigned, int open, @Nullable String comment) {
    }
}
