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


package nu.fgv.revival.server.checklist.template;

import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.checklist.RelativeDeadlineKind;
import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Checklist templates over GraphQL.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ChecklistTemplateGraphqlApi {

    private final ExternalIds ids;

    private final ChecklistTemplateService service;

    public ChecklistTemplateGraphqlApi(final ChecklistTemplateService service, final ExternalIds ids) {
        this.ids = ids;
        this.service = service;
    }

    private static ChecklistTemplateService.@Nullable Texts texts(final @Nullable LocalizedTextInput input) {
        return input == null ? null : new ChecklistTemplateService.Texts(input.sv() == null ? "" : input.sv(), input.en());
    }

    @QueryMapping
    public List<ChecklistTemplateDto> checklistTemplates(@Argument final @Nullable Boolean includeRetired) {
        return service.list(Boolean.TRUE.equals(includeRetired));
    }

    @QueryMapping
    public ChecklistTemplateDto checklistTemplate(@Argument final UUID id) {
        return service.get(ids.idOf(ChecklistTemplate.class, id));
    }

    @MutationMapping
    public ChecklistTemplateDto checklistTemplateCreate(@Argument final ChecklistTemplateCreateInput input) {
        return service.create(new ChecklistTemplateService.NewTemplate(input.kind(), input.chapterKey(), texts(input.name())));
    }

    @MutationMapping
    public ChecklistTemplateDto checklistTemplateUpdate(@Argument final ChecklistTemplateUpdateInput input) {
        return service.update(ids.idOf(ChecklistTemplate.class, input.id()), input.version(), texts(input.name()));
    }

    @MutationMapping
    public ChecklistTemplateDto checklistTemplateRetire(@Argument final UUID id, @Argument final Long version) {
        return service.retire(ids.idOf(ChecklistTemplate.class, id), version);
    }

    @MutationMapping
    public ChecklistTemplateDto checklistTemplateRestore(@Argument final UUID id, @Argument final Long version) {
        return service.restore(ids.idOf(ChecklistTemplate.class, id), version);
    }

    @MutationMapping
    public ChecklistTemplateItemDto checklistTemplateItemCreate(@Argument final ChecklistTemplateItemCreateInput input) {
        return service.createItem(new ChecklistTemplateService.NewItem(ids.idOf(ChecklistTemplate.class, input.templateId()), input.position(),
                Objects.requireNonNull(texts(input.text())), input.guideItemKey(), input.defaultCapability(),
                input.deadline() == null ? null : input.deadline().toDeadline()));
    }

    @MutationMapping
    public ChecklistTemplateItemDto checklistTemplateItemUpdate(@Argument final ChecklistTemplateItemUpdateInput input) {
        return service.updateItem(new ChecklistTemplateService.ItemChange(ids.idOf(ChecklistTemplateItem.class, input.id()), input.version(), texts(input.text()), input.guideItemKey(),
                Boolean.TRUE.equals(input.clearGuideItem()), input.defaultCapability(), Boolean.TRUE.equals(input.clearDefaultCapability()),
                input.deadline() == null ? null : input.deadline().toDeadline(), Boolean.TRUE.equals(input.clearDeadline())));
    }

    @MutationMapping
    public ChecklistTemplateDto checklistTemplateItemMove(@Argument final UUID id, @Argument final Long version, @Argument final int position) {
        return service.moveItem(ids.idOf(ChecklistTemplateItem.class, id), version, position);
    }

    @MutationMapping
    public ChecklistTemplateDto checklistTemplateItemRetire(@Argument final UUID id, @Argument final Long version) {
        return service.retireItem(ids.idOf(ChecklistTemplateItem.class, id), version);
    }

    @MutationMapping
    public ChecklistTemplateDto checklistTemplateItemRestore(@Argument final UUID id, @Argument final Long version) {
        return service.restoreItem(ids.idOf(ChecklistTemplateItem.class, id), version);
    }

    @SchemaMapping(typeName = "ChecklistTemplate")
    public List<ChecklistTemplateItemDto> retiredItems(final ChecklistTemplateDto template) {
        return service.retiredItems(ids.idOf(ChecklistTemplate.class, template.id()));
    }

    @SchemaMapping(typeName = "ChecklistTemplate")
    public GuideVersionView.@Nullable Text chapterTitle(final ChecklistTemplateDto template) {
        return service.chapterTitle(template.chapterKey());
    }

    @SchemaMapping(typeName = "ChecklistTemplateSummary", field = "chapterTitle")
    public GuideVersionView.@Nullable Text summaryChapterTitle(final ChecklistTemplateSummaryDto template) {
        return service.chapterTitle(template.chapterKey());
    }

    @BatchMapping(typeName = "ChecklistTemplateItem", field = "guideItem")
    public Map<ChecklistTemplateItemDto, GuideVersionView.Item> guideItem(final List<ChecklistTemplateItemDto> items) {
        return service.guideItems(items);
    }

    public record RelativeDeadlineInput(RelativeDeadlineKind kind, @Nullable Integer amount,
                                        @Nullable DayOfWeek weekday) {

        RelativeDeadline toDeadline() {
            return RelativeDeadline.of(kind, amount, weekday);
        }
    }

    public record ChecklistTemplateCreateInput(ChecklistKind kind, @Nullable String chapterKey,
                                               @Nullable LocalizedTextInput name) {
    }

    public record ChecklistTemplateUpdateInput(UUID id, Long version, @Nullable LocalizedTextInput name) {
    }

    public record ChecklistTemplateItemCreateInput(UUID templateId, @Nullable Integer position, LocalizedTextInput text,
                                                   @Nullable String guideItemKey, @Nullable String defaultCapability,
                                                   @Nullable RelativeDeadlineInput deadline) {
    }

    public record ChecklistTemplateItemUpdateInput(UUID id, Long version, @Nullable LocalizedTextInput text,
                                                   @Nullable String guideItemKey,
                                                   @Nullable Boolean clearGuideItem, @Nullable String defaultCapability,
                                                   @Nullable Boolean clearDefaultCapability,
                                                   @Nullable RelativeDeadlineInput deadline,
                                                   @Nullable Boolean clearDeadline) {
    }
}
