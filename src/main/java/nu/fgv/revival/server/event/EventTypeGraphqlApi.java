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

import nu.fgv.revival.server.checklist.template.ChecklistTemplateSummaryDto;
import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * Event types over GraphQL (event.graphqls). A thin adapter: every rule is in {@link EventTypeService}.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@Controller
public class EventTypeGraphqlApi {

    private final EventTypeService service;

    public EventTypeGraphqlApi(final EventTypeService service) {
        this.service = service;
    }

    public record EventTypeInput(LocalizedTextInput name, @Nullable LocalizedTextInput description, @Nullable LocalizedTextInput rooms,
                                 @Nullable LocalizedTextInput catering, @Nullable Integer offsetMinDays, @Nullable Integer offsetMaxDays,
                                 int days, boolean revivalPays, @Nullable Long checklistTemplateId) {
    }

    private static EventTypeService.Definition definition(final EventTypeInput input) {
        return new EventTypeService.Definition(text(input.name()), input.description() == null ? null : text(input.description()),
                input.rooms() == null ? null : text(input.rooms()), input.catering() == null ? null : text(input.catering()),
                input.offsetMinDays(), input.offsetMaxDays(), input.days(), input.revivalPays(), input.checklistTemplateId());
    }

    private static EventTypeService.Text text(final LocalizedTextInput input) {
        return new EventTypeService.Text(input.sv(), input.en());
    }

    @QueryMapping
    public List<EventTypeDto> eventTypes(@Argument final @Nullable Boolean includeRetired) {
        return service.list(Boolean.TRUE.equals(includeRetired));
    }

    @QueryMapping
    public EventTypeDto eventType(@Argument final Long id) {
        return service.get(id);
    }

    @MutationMapping
    public EventTypeDto eventTypeCreate(@Argument final EventTypeInput input) {
        return service.create(definition(input));
    }

    @MutationMapping
    public EventTypeDto eventTypeUpdate(@Argument final Long id, @Argument final Long version, @Argument final EventTypeInput input) {
        return service.update(id, version, definition(input));
    }

    @MutationMapping
    public List<EventTypeDto> eventTypeMove(@Argument final Long id, @Argument final Long version, @Argument final int position) {
        return service.move(id, version, position);
    }

    @MutationMapping
    public EventTypeDto eventTypeRetire(@Argument final Long id, @Argument final Long version) {
        return service.retire(id, version);
    }

    @MutationMapping
    public EventTypeDto eventTypeRestore(@Argument final Long id, @Argument final Long version) {
        return service.restore(id, version);
    }

    @SchemaMapping(typeName = "EventType", field = "checklistTemplate")
    public @Nullable ChecklistTemplateSummaryDto checklistTemplate(final EventTypeDto type) {
        return service.template(type);
    }
}
