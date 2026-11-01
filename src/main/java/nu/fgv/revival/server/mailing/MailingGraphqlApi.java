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

package nu.fgv.revival.server.mailing;

import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateGraphqlApi.RelativeDeadlineInput;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventType;
import nu.fgv.revival.server.event.EventTypeDto;
import nu.fgv.revival.server.event.EventTypeService;
import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import nu.fgv.revival.server.mailing.MailingDtos.MailingDto;
import nu.fgv.revival.server.mailing.MailingDtos.MailingPlanEntry;
import nu.fgv.revival.server.mailing.MailingDtos.MailingRecipientPreview;
import nu.fgv.revival.server.mailing.MailingDtos.MailingTemplateDto;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalDto;
import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mailings and the mailing plan over GraphQL (mailing.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class MailingGraphqlApi {

    private final ExternalIds ids;

    private final MailingService service;
    private final MailingTemplateService templateService;
    private final EventTypeService eventTypeService;

    public MailingGraphqlApi(final MailingService service, final MailingTemplateService templateService, final EventTypeService eventTypeService, final ExternalIds ids) {
        this.ids = ids;
        this.service = service;
        this.templateService = templateService;
        this.eventTypeService = eventTypeService;
    }

    @QueryMapping
    public List<MailingDto> mailings(@Argument final UUID revivalId, final Locale locale) {
        return service.list(ids.idOfNullable(Revival.class, revivalId), locale);
    }

    @QueryMapping
    public MailingDto mailing(@Argument final UUID id, final Locale locale) {
        return service.get(ids.idOf(Mailing.class, id), locale);
    }

    @QueryMapping
    public List<MailingRecipientPreview> mailingAudiencePreview(@Argument final UUID revivalId, @Argument final MailingAudienceInput audience,
                                                                final Locale locale) {
        return service.preview(ids.idOfNullable(Revival.class, revivalId), audience.toInput(ids), locale);
    }

    @MutationMapping
    public MailingDto mailingCreate(@Argument final MailingInput input, final Locale locale) {
        return service.create(input.toInput(ids), locale);
    }

    @MutationMapping
    public MailingDto mailingUpdate(@Argument final UUID id, @Argument final Long version, @Argument final MailingInput input, final Locale locale) {
        return service.update(ids.idOf(Mailing.class, id), version, input.toInput(ids), locale);
    }

    @MutationMapping
    public MailingDto mailingCancel(@Argument final UUID id, @Argument final Long version, final Locale locale) {
        return service.cancel(ids.idOf(Mailing.class, id), version, locale);
    }

    @MutationMapping
    public MailingDto mailingSend(@Argument final UUID id, @Argument final Long version, final Locale locale) {
        return service.send(ids.idOf(Mailing.class, id), version, locale);
    }

    @MutationMapping
    public MailingDto mailingRetry(@Argument final UUID id, final Locale locale) {
        return service.retry(ids.idOf(Mailing.class, id), locale);
    }

    @SchemaMapping(typeName = "Mailing", field = "plannedFor")
    public @Nullable MailingPlanEntry plannedFor(final MailingDto mailing, final Locale locale) {
        return service.plannedFor(mailing, locale);
    }

    @SchemaMapping(typeName = "Revival", field = "mailingPlan")
    public List<MailingPlanEntry> mailingPlan(final RevivalDto revival, final Locale locale) {
        return service.plan(ids.idOf(Revival.class, revival.id()), locale);
    }

    @QueryMapping
    public List<MailingTemplateDto> mailingTemplates(@Argument final @Nullable Boolean includeRetired) {
        return templateService.list(Boolean.TRUE.equals(includeRetired));
    }

    @MutationMapping
    public MailingTemplateDto mailingTemplateCreate(@Argument final MailingTemplateInput input) {
        return templateService.create(input.toDefinition(ids));
    }

    @MutationMapping
    public MailingTemplateDto mailingTemplateUpdate(@Argument final UUID id, @Argument final Long version, @Argument final MailingTemplateInput input) {
        return templateService.update(ids.idOf(MailingTemplate.class, id), version, input.toDefinition(ids));
    }

    @MutationMapping
    public List<MailingTemplateDto> mailingTemplateMove(@Argument final UUID id, @Argument final Long version, @Argument final int position) {
        return templateService.move(ids.idOf(MailingTemplate.class, id), version, position);
    }

    @MutationMapping
    public MailingTemplateDto mailingTemplateRetire(@Argument final UUID id, @Argument final Long version) {
        return templateService.retire(ids.idOf(MailingTemplate.class, id), version);
    }

    @MutationMapping
    public MailingTemplateDto mailingTemplateRestore(@Argument final UUID id, @Argument final Long version) {
        return templateService.restore(ids.idOf(MailingTemplate.class, id), version);
    }

    @BatchMapping(typeName = "MailingTemplate", field = "eventType")
    public Map<MailingTemplateDto, EventTypeDto> eventType(final List<MailingTemplateDto> templates) {
        final Map<UUID, EventTypeDto> types = ids.byUuid(EventType.class, eventTypeService.byIds(ids.idsOf(EventType.class, templates.stream().map(MailingTemplateDto::eventTypeId).filter(Objects::nonNull).distinct().toList())));
        final Map<MailingTemplateDto, EventTypeDto> result = new HashMap<>();

        templates.stream().filter(template -> template.eventTypeId() != null && types.containsKey(template.eventTypeId()))
                .forEach(template -> result.put(template, types.get(template.eventTypeId())));
        return result;
    }

    public record MailingAudienceInput(MailingAudienceKind kind, @Nullable String positionId, @Nullable UUID eventId) {

        MailingService.AudienceInput toInput(final ExternalIds ids) {
            return new MailingService.AudienceInput(kind, positionId, ids.idOfNullable(Event.class, eventId));
        }
    }

    public record MailingInput(@Nullable UUID revivalId, MailingAudienceInput audience, String subject, String body,
                               @Nullable UUID templateId,
                               @Nullable UUID eventId) {

        MailingService.Input toInput(final ExternalIds ids) {
            return new MailingService.Input(ids.idOfNullable(Revival.class, revivalId), audience.toInput(ids), subject, body, ids.idOfNullable(MailingTemplate.class, templateId), ids.idOfNullable(Event.class, eventId));
        }
    }

    public record MailingTemplateInput(LocalizedTextInput name, LocalizedTextInput subject, LocalizedTextInput body,
                                       MailingAudienceKind audience,
                                       @Nullable UUID eventTypeId, RelativeDeadlineInput due) {

        private static MailingTemplateService.Text text(final LocalizedTextInput input) {
            return new MailingTemplateService.Text(input.sv(), input.en());
        }

        MailingTemplateService.Definition toDefinition(final ExternalIds ids) {
            return new MailingTemplateService.Definition(text(name), text(subject), text(body), audience, ids.idOfNullable(EventType.class, eventTypeId),
                    RelativeDeadline.of(due.kind(), due.amount(), due.weekday()));
        }
    }
}
