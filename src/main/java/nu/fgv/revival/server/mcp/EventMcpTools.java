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
import nu.fgv.revival.server.event.EventProposalDto;
import nu.fgv.revival.server.event.EventService;
import nu.fgv.revival.server.event.EventTypeDto;
import nu.fgv.revival.server.event.EventTypeService;
import nu.fgv.revival.server.budget.EventTypeBudgetLineDto;
import nu.fgv.revival.server.budget.EventTypeBudgetLineService;
import nu.fgv.revival.server.util.Locales;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A revival's events over MCP, read only: readers of the revival's work, as GraphQL's {@code events} except
 * that participants, who read events in the web, get no MCP tools.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class EventMcpTools {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final EventService eventService;
    private final EventTypeService typeService;
    private final EventTypeBudgetLineService typeLineService;
    private final McpToolSupport support;

    public EventMcpTools(final EventService eventService, final EventTypeService typeService, final EventTypeBudgetLineService typeLineService,
                         final McpToolSupport support) {
        this.eventService = eventService;
        this.typeService = typeService;
        this.typeLineService = typeLineService;
        this.support = support;
    }

    private Event written(final EventDto event) {
        return toMcp(event, event.typeId() == null ? null : typeService.get(event.typeId()), eventService.checklists(List.of(event.id())).get(event.id()));
    }

    static Event toMcp(final EventDto event, final @Nullable EventTypeDto type, final @Nullable ChecklistSummaryDto checklist) {
        return new Event(event.id(), type == null ? null : new ChecklistMcpTools.Text(type.name().sv(), type.name().en()), event.title(),
                event.startDate(), event.endDate(), event.startTime(), event.endTime(), event.location(), event.cancelled(), event.daysFromPremiere(),
                checklist == null ? null : new Checklist(checklist.id(), checklist.openCount(), checklist.totalCount()));
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

    @McpTool(name = "create_event",
            description = "Add an event to a revival's timeline, e.g. a rehearsal weekend. From an event type, the title, the number of days and whether the revival pays come from the type, and the type's checklist template gives it a checklist.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Event createEvent(
            @McpToolParam(description = "A revival you can work in") final Long revivalId,
            @McpToolParam(description = "The first day, yyyy-MM-dd") final String startDate,
            @McpToolParam(required = false, description = "An event type, by id from list_event_types") final @Nullable Long typeId,
            @McpToolParam(required = false, description = "The title; required without a type") final @Nullable String title,
            @McpToolParam(required = false, description = "The last day, yyyy-MM-dd") final @Nullable String endDate,
            @McpToolParam(required = false, description = "HH:mm") final @Nullable String startTime,
            @McpToolParam(required = false, description = "HH:mm") final @Nullable String endTime,
            @McpToolParam(required = false, description = "Where") final @Nullable String location,
            @McpToolParam(required = false, description = "A description") final @Nullable String description,
            @McpToolParam(required = false, description = "Whether the revival pays for it") final @Nullable Boolean revivalPays) {
        return support.call("create_event", () -> written(eventService.create(new EventService.NewEvent(revivalId, typeId, title,
                Objects.requireNonNull(McpToolSupport.date(startDate)), McpToolSupport.date(endDate), startTime, endTime, location, description, revivalPays),
                Locales.SWEDISH)));
    }

    @McpTool(name = "update_event",
            description = "Change an event: title, dates, times, location, description or whether the revival pays. Leave out what stays as it is; an empty string clears a time, the location or the description. Its checklist follows the new name and date.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Event updateEvent(
            @McpToolParam(description = "The event, by id") final Long eventId,
            @McpToolParam(required = false, description = "The title") final @Nullable String title,
            @McpToolParam(required = false, description = "yyyy-MM-dd") final @Nullable String startDate,
            @McpToolParam(required = false, description = "yyyy-MM-dd") final @Nullable String endDate,
            @McpToolParam(required = false, description = "HH:mm") final @Nullable String startTime,
            @McpToolParam(required = false, description = "HH:mm") final @Nullable String endTime,
            @McpToolParam(required = false, description = "Where") final @Nullable String location,
            @McpToolParam(required = false, description = "A description") final @Nullable String description,
            @McpToolParam(required = false, description = "Whether the revival pays for it") final @Nullable Boolean revivalPays) {
        return support.call("update_event", () -> {
            final EventDto event = eventService.get(eventId);

            return written(eventService.update(new EventService.Change(eventId, event.version(), title, McpToolSupport.date(startDate),
                    McpToolSupport.date(endDate), blankToNull(startTime), isBlank(startTime), blankToNull(endTime), isBlank(endTime),
                    blankToNull(location), isBlank(location), blankToNull(description), isBlank(description), revivalPays)));
        });
    }

    @McpTool(name = "set_event_cancelled",
            description = "Cancel an event, or restore a cancelled one. Its checklist is cancelled or restored with it. Events are never deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Event setEventCancelled(
            @McpToolParam(description = "The event, by id") final Long eventId,
            @McpToolParam(description = "true to cancel, false to restore") final Boolean cancelled) {
        return support.call("set_event_cancelled", () -> {
            final EventDto event = eventService.get(eventId);

            return written(cancelled ? eventService.cancel(eventId, event.version()) : eventService.restore(eventId, event.version()));
        });
    }

    @McpTool(name = "list_event_proposals",
            description = "List the events a revival's timeline proposes from its premiere and the event types, with the dates allowed, for those not added yet.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<EventProposalDto> listEventProposals(@McpToolParam(description = "A revival you can work in") final Long revivalId) {
        return support.call("list_event_proposals", () -> new McpToolSupport.ListResult<>(eventService.proposals(revivalId)));
    }

    @McpTool(name = "list_event_types",
            description = "List the event types a revival's timeline is planned from: name, how many days before or after the premiere, how many days, whether the revival pays, and the checklist template.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<EventType> listEventTypes(
            @McpToolParam(required = false, description = "Also retired ones") final @Nullable Boolean includeRetired) {
        return support.call("list_event_types", () -> {
            final List<EventTypeDto> types = typeService.list(Boolean.TRUE.equals(includeRetired));
            final Map<Long, List<EventTypeBudgetLineDto>> lines = typeLineService.byTypes(types.stream().map(EventTypeDto::id).toList());

            return new McpToolSupport.ListResult<>(types.stream().map(type -> new EventType(type, lines.getOrDefault(type.id(), List.of()))).toList());
        });
    }

    @McpTool(name = "create_event_type",
            description = "Super admins: add an event type. Its offsets are days from the premiere (negative before it).",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public EventTypeDto createEventType(@McpToolParam(description = "The type: name, description, rooms and catering in Swedish and optionally English, offsets, days, whether the revival pays, and a checklist template id") final EventTypeService.Definition definition) {
        return support.call("create_event_type", () -> typeService.create(definition));
    }

    @McpTool(name = "update_event_type",
            description = "Super admins: replace an event type's definition, and optionally move it to a position in the list (counting from 0).",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public EventTypeDto updateEventType(
            @McpToolParam(description = "The type, by id") final Long typeId,
            @McpToolParam(required = false, description = "The whole definition, as in create_event_type") final EventTypeService.@Nullable Definition definition,
            @McpToolParam(required = false, description = "Its place in the list, counting from 0") final @Nullable Integer position) {
        return support.call("update_event_type", () -> {
            EventTypeDto type = typeService.get(typeId);

            if (definition != null) {
                type = typeService.update(typeId, type.version(), definition);
            }
            if (position != null) {
                final Long version = type.version();

                type = McpToolSupport.byId(typeService.move(typeId, version, position), EventTypeDto::id, typeId, EventTypeDto.class);
            }
            return type;
        });
    }

    @McpTool(name = "set_event_type_retired",
            description = "Super admins: retire an event type so it is no longer offered, or restore it. Events of the type keep it.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public EventTypeDto setEventTypeRetired(
            @McpToolParam(description = "The type, by id") final Long typeId,
            @McpToolParam(description = "true to retire, false to restore") final Boolean retired) {
        return support.call("set_event_type_retired", () -> {
            final EventTypeDto type = typeService.get(typeId);

            return retired ? typeService.retire(typeId, type.version()) : typeService.restore(typeId, type.version());
        });
    }

    @McpTool(name = "create_event_type_budget_line",
            description = "Super admins: add a budget line an event type proposes to revivals that hold such an event, fixed or per participant.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public EventTypeDto createEventTypeBudgetLine(@McpToolParam(description = "The line: the event type's id, a budget category id, descriptions, FIXED or PER_PARTICIPANT, the quantity and the amount each") final EventTypeBudgetLineService.Definition definition) {
        return support.call("create_event_type_budget_line", () -> typeLineService.create(definition));
    }

    @McpTool(name = "update_event_type_budget_line",
            description = "Super admins: replace a budget line an event type proposes.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public EventTypeDto updateEventTypeBudgetLine(
            @McpToolParam(description = "The line, by id from list_event_types") final Long lineId,
            @McpToolParam(description = "The whole line, as in create_event_type_budget_line") final EventTypeBudgetLineService.Definition definition) {
        return support.call("update_event_type_budget_line", () -> {
            final List<Long> types = typeService.list(true).stream().map(EventTypeDto::id).toList();
            final EventTypeBudgetLineDto line = McpToolSupport.byId(typeLineService.byTypes(types).values().stream().flatMap(List::stream).toList(),
                    EventTypeBudgetLineDto::id, lineId, EventTypeBudgetLineDto.class);

            return typeLineService.update(lineId, line.version(), definition);
        });
    }

    private static @Nullable String blankToNull(final @Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isBlank(final @Nullable String value) {
        return value != null && value.isBlank();
    }

    /**
     * An event type with the budget lines it proposes.
     */
    public record EventType(EventTypeDto type, List<EventTypeBudgetLineDto> budgetLines) {
    }

    public record Checklist(Long id, int open, int total) {
    }

    public record Event(Long id, ChecklistMcpTools.@Nullable Text type, String title, LocalDate startDate,
                        LocalDate endDate,
                        @Nullable String startTime, @Nullable String endTime, @Nullable String location,
                        boolean cancelled,
                        @Nullable Integer daysFromPremiere, @Nullable Checklist checklist) {
    }

    public record Events(List<Event> items, int total) {
    }
}
