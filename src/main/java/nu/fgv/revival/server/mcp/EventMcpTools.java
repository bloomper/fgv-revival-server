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

import nu.fgv.revival.server.checklist.ChecklistSummaryDto;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.event.EventService;
import nu.fgv.revival.server.event.EventTypeDto;
import nu.fgv.revival.server.event.EventTypeService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A revival's events over MCP (M4-FR-10), read only: readers of the revival's work, as GraphQL's {@code events} except
 * that participants, who read events in the web, get no MCP tools (M6).
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@Component
public class EventMcpTools {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final EventService eventService;
    private final EventTypeService typeService;
    private final McpToolSupport support;

    public EventMcpTools(final EventService eventService, final EventTypeService typeService, final McpToolSupport support) {
        this.eventService = eventService;
        this.typeService = typeService;
        this.support = support;
    }

    @McpTool(name = "list_events",
            description = "List a revival's events in date order: rehearsal weekends, the show weekend and anything else in its timeline, with each event's checklist. Events from, to or across the given dates.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Events listEvents(
            @McpToolParam(description = "A revival you can see") final Long revivalId,
            @McpToolParam(required = false, description = "Events ending on or after this date, yyyy-MM-dd") final @Nullable String from,
            @McpToolParam(required = false, description = "Events starting on or before this date, yyyy-MM-dd") final @Nullable String to,
            @McpToolParam(required = false, description = "Also cancelled events") final @Nullable Boolean includeCancelled,
            @McpToolParam(required = false, description = "1-200, default 50") final @Nullable Integer limit) {
        return support.call("list_events", () -> {
            final @Nullable LocalDate since = McpToolSupport.date(from);
            final @Nullable LocalDate until = McpToolSupport.date(to);
            final List<EventDto> events = eventService.listAsWork(revivalId, Boolean.TRUE.equals(includeCancelled)).stream()
                    .filter(event -> since == null || !event.endDate().isBefore(since))
                    .filter(event -> until == null || !event.startDate().isAfter(until))
                    .toList();
            final int first = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
            final List<EventDto> shown = events.stream().limit(first).toList();
            final Map<Long, EventTypeDto> types = typeService.byIds(shown.stream().map(EventDto::typeId).filter(Objects::nonNull).toList());
            final Map<Long, ChecklistSummaryDto> checklists = eventService.checklists(shown.stream().map(EventDto::id).toList());

            return new Events(shown.stream().map(event -> toMcp(event, types.get(event.typeId()), checklists.get(event.id()))).toList(), events.size());
        });
    }

    static Event toMcp(final EventDto event, final @Nullable EventTypeDto type, final @Nullable ChecklistSummaryDto checklist) {
        return new Event(event.id(), type == null ? null : new ChecklistMcpTools.Text(type.name().sv(), type.name().en()), event.title(),
                event.startDate(), event.endDate(), event.startTime(), event.endTime(), event.location(), event.cancelled(), event.daysFromPremiere(),
                checklist == null ? null : new Checklist(checklist.id(), checklist.openCount(), checklist.totalCount()));
    }

    public record Checklist(Long id, int open, int total) {
    }

    public record Event(Long id, ChecklistMcpTools.@Nullable Text type, String title, LocalDate startDate, LocalDate endDate,
                        @Nullable String startTime, @Nullable String endTime, @Nullable String location, boolean cancelled,
                        @Nullable Integer daysFromPremiere, @Nullable Checklist checklist) {
    }

    public record Events(List<Event> items, int total) {
    }
}
