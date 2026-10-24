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


package nu.fgv.revival.server.event;

import nu.fgv.revival.server.checklist.ChecklistDto;
import nu.fgv.revival.server.checklist.ChecklistSummaryDto;
import nu.fgv.revival.server.revival.RevivalDto;
import nu.fgv.revival.server.revival.RevivalWorkSummaryDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A revival's events over GraphQL (event.graphqls). A thin adapter: every rule is in {@link EventService}.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@Controller
public class EventGraphqlApi {

    private final EventService service;
    private final EventTypeService typeService;

    public EventGraphqlApi(final EventService service, final EventTypeService typeService) {
        this.service = service;
        this.typeService = typeService;
    }

    public record EventCreateInput(Long revivalId, @Nullable Long typeId, @Nullable String title, LocalDate startDate, @Nullable LocalDate endDate,
                                   @Nullable String startTime, @Nullable String endTime, @Nullable String location, @Nullable String description,
                                   @Nullable Boolean revivalPays) {
    }

    public record EventUpdateInput(Long id, Long version, @Nullable String title, @Nullable LocalDate startDate, @Nullable LocalDate endDate,
                                   @Nullable String startTime, @Nullable Boolean clearStartTime, @Nullable String endTime,
                                   @Nullable Boolean clearEndTime, @Nullable String location, @Nullable Boolean clearLocation,
                                   @Nullable String description, @Nullable Boolean clearDescription, @Nullable Boolean revivalPays) {
    }

    @QueryMapping
    public List<EventDto> events(@Argument final Long revivalId, @Argument final @Nullable Boolean includeCancelled) {
        return service.list(revivalId, Boolean.TRUE.equals(includeCancelled));
    }

    @QueryMapping
    public EventDto event(@Argument final Long id) {
        return service.get(id);
    }

    @MutationMapping
    public EventDto eventCreate(@Argument final EventCreateInput input, final Locale locale) {
        return service.create(new EventService.NewEvent(input.revivalId(), input.typeId(), input.title(), input.startDate(), input.endDate(),
                input.startTime(), input.endTime(), input.location(), input.description(), input.revivalPays()), locale);
    }

    @MutationMapping
    public EventDto eventUpdate(@Argument final EventUpdateInput input) {
        return service.update(new EventService.Change(input.id(), input.version(), input.title(), input.startDate(), input.endDate(),
                input.startTime(), Boolean.TRUE.equals(input.clearStartTime()), input.endTime(), Boolean.TRUE.equals(input.clearEndTime()),
                input.location(), Boolean.TRUE.equals(input.clearLocation()), input.description(), Boolean.TRUE.equals(input.clearDescription()),
                input.revivalPays()));
    }

    @MutationMapping
    public EventDto eventCancel(@Argument final Long id, @Argument final Long version) {
        return service.cancel(id, version);
    }

    @MutationMapping
    public EventDto eventRestore(@Argument final Long id, @Argument final Long version) {
        return service.restore(id, version);
    }

    @BatchMapping(typeName = "Event", field = "type")
    public Map<EventDto, EventTypeDto> type(final List<EventDto> events) {
        final Map<Long, EventTypeDto> types = typeService.byIds(events.stream().map(EventDto::typeId).filter(Objects::nonNull).toList());
        final Map<EventDto, EventTypeDto> result = new HashMap<>();

        events.stream().filter(event -> event.typeId() != null && types.containsKey(event.typeId()))
                .forEach(event -> result.put(event, types.get(event.typeId())));
        return result;
    }

    @BatchMapping(typeName = "Event", field = "checklist")
    public Map<EventDto, ChecklistSummaryDto> checklist(final List<EventDto> events) {
        final Map<Long, ChecklistSummaryDto> checklists = service.checklists(events.stream().map(EventDto::id).toList());
        final Map<EventDto, ChecklistSummaryDto> result = new HashMap<>();

        events.stream().filter(event -> checklists.containsKey(event.id())).forEach(event -> result.put(event, checklists.get(event.id())));
        return result;
    }

    @SchemaMapping(typeName = "Revival", field = "eventProposals")
    public List<EventProposalDto> eventProposals(final RevivalDto revival) {
        return service.proposals(revival.id());
    }

    @SchemaMapping(typeName = "RevivalWorkSummary", field = "upcomingEvents")
    public List<EventDto> upcomingEvents(final RevivalWorkSummaryDto summary) {
        return service.upcoming(summary.revivalId());
    }

    @SchemaMapping(typeName = "Checklist", field = "event")
    public @Nullable EventDto checklistEvent(final ChecklistDto checklist) {
        return checklist.eventId() == null ? null : service.get(checklist.eventId());
    }
}
