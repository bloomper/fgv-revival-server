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

import nu.fgv.revival.server.staffing.ShiftSlot;
import nu.fgv.revival.server.staffing.StaffingDtos.ShiftSlotDto;
import nu.fgv.revival.server.staffing.StaffingService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * A revival's shift slots over MCP: the planning rows, never who holds them. Assigning people, availability and
 * signing up are personal and stay in the web.
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
                slot.assigned().size(), slot.open(), slot.comment());
    }

    private static StaffingService.SlotInput input(final @Nullable Long eventId, final String date, final String startTime, final String endTime,
                                                   final String task, final @Nullable Integer needed, final @Nullable String comment) {
        return new StaffingService.SlotInput(eventId, Objects.requireNonNull(McpToolSupport.date(date)), startTime, endTime, task,
                needed == null ? 1 : needed, comment);
    }

    @McpTool(name = "list_shift_slots",
            description = "List a revival's shift slots for its events that are not cancelled, by date and time: the event, the task, the time, how many people are needed, how many hold it and how many places are open. Never who.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<Slot> listShiftSlots(
            @McpToolParam(description = "A revival you can see") final Long revivalId,
            @McpToolParam(required = false, description = "Only this event, by id") final @Nullable Long eventId) {
        return support.call("list_shift_slots", () -> new McpToolSupport.ListResult<>(staffingService.slots(revivalId, eventId, Locale.ENGLISH).stream()
                .map(StaffingMcpTools::toMcp)
                .toList()));
    }

    @McpTool(name = "create_shift_slot",
            description = "Add a shift slot to one of a revival's events: a task on a day between two times, and how many people it needs. People are assigned in the web.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Slot createShiftSlot(
            @McpToolParam(description = "The event, by id from list_events") final Long eventId,
            @McpToolParam(description = "A day of the event, yyyy-MM-dd") final String date,
            @McpToolParam(description = "HH:mm") final String startTime,
            @McpToolParam(description = "HH:mm") final String endTime,
            @McpToolParam(description = "What to do, e.g. Biljettkontroll") final String task,
            @McpToolParam(required = false, description = "How many people, default 1") final @Nullable Integer needed,
            @McpToolParam(required = false, description = "A comment") final @Nullable String comment) {
        return support.call("create_shift_slot", () -> toMcp(staffingService.create(input(eventId, date, startTime, endTime, task, needed, comment),
                Locale.ENGLISH)));
    }

    @McpTool(name = "update_shift_slot",
            description = "Change a shift slot: its day, times, task, how many it needs or its comment. Give every field; list_shift_slots shows the current ones.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Slot updateShiftSlot(
            @McpToolParam(description = "A revival you can see") final Long revivalId,
            @McpToolParam(description = "The slot, by id from list_shift_slots") final Long slotId,
            @McpToolParam(description = "yyyy-MM-dd") final String date,
            @McpToolParam(description = "HH:mm") final String startTime,
            @McpToolParam(description = "HH:mm") final String endTime,
            @McpToolParam(description = "What to do") final String task,
            @McpToolParam(description = "How many people") final Integer needed,
            @McpToolParam(required = false, description = "A comment; leave out to clear it") final @Nullable String comment) {
        return support.call("update_shift_slot", () -> {
            final ShiftSlotDto slot = McpToolSupport.byId(staffingService.slots(revivalId, null, Locale.ENGLISH), ShiftSlotDto::id, slotId, ShiftSlot.class);

            return toMcp(staffingService.update(slotId, slot.version(), input(slot.event().id(), date, startTime, endTime, task, needed, comment),
                    Locale.ENGLISH));
        });
    }

    /**
     * A slot without who holds it: how many do, and how many places are open.
     */
    public record Slot(Long id, Long eventId, String event, LocalDate date, String startTime, String endTime,
                       String task, int needed, int held, int open, @Nullable String comment) {
    }
}
