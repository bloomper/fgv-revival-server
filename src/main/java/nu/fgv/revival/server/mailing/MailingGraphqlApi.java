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
import nu.fgv.revival.server.event.EventTypeDto;
import nu.fgv.revival.server.event.EventTypeService;
import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import nu.fgv.revival.server.mailing.MailingDtos.MailingDto;
import nu.fgv.revival.server.mailing.MailingDtos.MailingPlanEntry;
import nu.fgv.revival.server.mailing.MailingDtos.MailingRecipientPreview;
import nu.fgv.revival.server.mailing.MailingDtos.MailingTemplateDto;
import nu.fgv.revival.server.revival.RevivalDto;
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

/**
 * Mailings and the mailing plan over GraphQL (mailing.graphqls). A thin adapter: every rule is in the services.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Controller
public class MailingGraphqlApi {

    private final MailingService service;
    private final MailingTemplateService templateService;
    private final EventTypeService eventTypeService;

    public MailingGraphqlApi(final MailingService service, final MailingTemplateService templateService, final EventTypeService eventTypeService) {
        this.service = service;
        this.templateService = templateService;
        this.eventTypeService = eventTypeService;
    }

    @QueryMapping
    public List<MailingDto> mailings(@Argument final Long revivalId, final Locale locale) {
        return service.list(revivalId, locale);
    }

    @QueryMapping
    public MailingDto mailing(@Argument final Long id, final Locale locale) {
        return service.get(id, locale);
    }

    @QueryMapping
    public List<MailingRecipientPreview> mailingAudiencePreview(@Argument final Long revivalId, @Argument final MailingAudienceInput audience,
                                                                final Locale locale) {
        return service.preview(revivalId, audience.toInput(), locale);
    }

    // --- Mailings ---------------------------------------------------------------------------------------------------

    @MutationMapping
    public MailingDto mailingCreate(@Argument final MailingInput input, final Locale locale) {
        return service.create(input.toInput(), locale);
    }

    @MutationMapping
    public MailingDto mailingUpdate(@Argument final Long id, @Argument final Long version, @Argument final MailingInput input, final Locale locale) {
        return service.update(id, version, input.toInput(), locale);
    }

    @MutationMapping
    public MailingDto mailingCancel(@Argument final Long id, @Argument final Long version, final Locale locale) {
        return service.cancel(id, version, locale);
    }

    @MutationMapping
    public MailingDto mailingSend(@Argument final Long id, @Argument final Long version, final Locale locale) {
        return service.send(id, version, locale);
    }

    @MutationMapping
    public MailingDto mailingRetry(@Argument final Long id, final Locale locale) {
        return service.retry(id, locale);
    }

    @SchemaMapping(typeName = "Mailing", field = "plannedFor")
    public @Nullable MailingPlanEntry plannedFor(final MailingDto mailing, final Locale locale) {
        return service.plannedFor(mailing, locale);
    }

    @SchemaMapping(typeName = "Revival", field = "mailingPlan")
    public List<MailingPlanEntry> mailingPlan(final RevivalDto revival, final Locale locale) {
        return service.plan(revival.id(), locale);
    }

    @QueryMapping
    public List<MailingTemplateDto> mailingTemplates(@Argument final @Nullable Boolean includeRetired) {
        return templateService.list(Boolean.TRUE.equals(includeRetired));
    }

    @MutationMapping
    public MailingTemplateDto mailingTemplateCreate(@Argument final MailingTemplateInput input) {
        return templateService.create(input.toDefinition());
    }

    @MutationMapping
    public MailingTemplateDto mailingTemplateUpdate(@Argument final Long id, @Argument final Long version, @Argument final MailingTemplateInput input) {
        return templateService.update(id, version, input.toDefinition());
    }

    // --- The plan's templates ---------------------------------------------------------------------------------------

    @MutationMapping
    public List<MailingTemplateDto> mailingTemplateMove(@Argument final Long id, @Argument final Long version, @Argument final int position) {
        return templateService.move(id, version, position);
    }

    @MutationMapping
    public MailingTemplateDto mailingTemplateRetire(@Argument final Long id, @Argument final Long version) {
        return templateService.retire(id, version);
    }

    @MutationMapping
    public MailingTemplateDto mailingTemplateRestore(@Argument final Long id, @Argument final Long version) {
        return templateService.restore(id, version);
    }

    @BatchMapping(typeName = "MailingTemplate", field = "eventType")
    public Map<MailingTemplateDto, EventTypeDto> eventType(final List<MailingTemplateDto> templates) {
        final Map<Long, EventTypeDto> types = eventTypeService.byIds(templates.stream().map(MailingTemplateDto::eventTypeId).filter(Objects::nonNull).toList());
        final Map<MailingTemplateDto, EventTypeDto> result = new HashMap<>();

        templates.stream().filter(template -> template.eventTypeId() != null && types.containsKey(template.eventTypeId()))
                .forEach(template -> result.put(template, types.get(template.eventTypeId())));
        return result;
    }

    public record MailingAudienceInput(MailingAudienceKind kind, @Nullable String positionId, @Nullable Long eventId) {

        MailingService.AudienceInput toInput() {
            return new MailingService.AudienceInput(kind, positionId, eventId);
        }
    }

    public record MailingInput(@Nullable Long revivalId, MailingAudienceInput audience, String subject, String body,
                               @Nullable Long templateId,
                               @Nullable Long eventId) {

        MailingService.Input toInput() {
            return new MailingService.Input(revivalId, audience.toInput(), subject, body, templateId, eventId);
        }
    }

    public record MailingTemplateInput(LocalizedTextInput name, LocalizedTextInput subject, LocalizedTextInput body,
                                       MailingAudienceKind audience,
                                       @Nullable Long eventTypeId, RelativeDeadlineInput due) {

        private static MailingTemplateService.Text text(final LocalizedTextInput input) {
            return new MailingTemplateService.Text(input.sv(), input.en());
        }

        MailingTemplateService.Definition toDefinition() {
            return new MailingTemplateService.Definition(text(name), text(subject), text(body), audience, eventTypeId,
                    RelativeDeadline.of(due.kind(), due.amount(), due.weekday()));
        }
    }
}
