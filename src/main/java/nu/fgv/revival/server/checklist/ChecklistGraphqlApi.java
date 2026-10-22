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


package nu.fgv.revival.server.checklist;

import nu.fgv.revival.server.checklist.template.ChecklistTemplateSummaryDto;
import nu.fgv.revival.server.decisionlog.LogEntryService;
import nu.fgv.revival.server.decisionlog.LogEntrySummaryDto;
import nu.fgv.revival.server.guide.GuideService;
import nu.fgv.revival.server.guide.GuideVersionStatus;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.revival.RevivalDto;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checklists over GraphQL (contracts/schema.graphqls). A thin adapter: every rule is in {@link ChecklistService}.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Controller
public class ChecklistGraphqlApi {

    private final ChecklistService service;
    private final LogEntryService logEntryService;
    private final GuideService guideService;

    public ChecklistGraphqlApi(final ChecklistService service, final LogEntryService logEntryService, final GuideService guideService) {
        this.service = service;
        this.logEntryService = logEntryService;
        this.guideService = guideService;
    }

    public record ChecklistItemCreateInput(Long checklistId, String text, @Nullable OwnerInputDto owner, @Nullable LocalDate deadline,
                                           @Nullable String comment, @Nullable String guideItemKey) {
    }

    public record ChecklistItemUpdateInput(Long id, Long version, @Nullable String text, @Nullable OwnerInputDto owner, @Nullable Boolean clearOwner,
                                           @Nullable LocalDate deadline, @Nullable Boolean clearDeadline, @Nullable String comment,
                                           @Nullable Boolean clearComment) {
    }

    public record EventChecklistCreateInput(Long revivalId, String name, @Nullable LocalDate date, @Nullable Long templateId) {
    }

    public record EventChecklistUpdateInput(Long id, Long version, @Nullable String name, @Nullable LocalDate date, @Nullable Boolean clearDate) {
    }

    public record ChecklistAddFromTemplateInput(Long checklistId, Long version, @Nullable Long templateId) {
    }

    public record ChecklistItemSetStateInput(Long id, Long version, ChecklistItemState state, @Nullable String reason) {
    }

    @QueryMapping
    public List<ChecklistSummaryDto> checklists(@Argument final Long revivalId, @Argument final @Nullable Boolean includeCancelled) {
        return service.list(revivalId, Boolean.TRUE.equals(includeCancelled));
    }

    @QueryMapping
    public ChecklistDto checklist(@Argument final Long id, final Locale locale) {
        return service.get(id, locale);
    }

    @QueryMapping
    public List<ChecklistItemDto> checklistItems(@Argument final Long revivalId, @Argument final @Nullable ChecklistItemFilterDto filter,
                                                 final Locale locale) {
        return service.items(revivalId, filter, locale);
    }

    @QueryMapping
    public ChapterRemovalImpactDto guideChapterRemovalImpact(@Argument final String chapterKey) {
        return service.chapterRemovalImpact(chapterKey);
    }

    @SchemaMapping(typeName = "Revival")
    public List<ChecklistRemovalDto> guideVersionChangeImpact(final RevivalDto revival, @Argument final Long toId) {
        final GuideVersionView target = guideService.findById(toId);

        if (target.status() != GuideVersionStatus.PUBLISHED) {
            throw new BadRequestException("error.guide.version.notPublished");
        }
        return service.removalsOnVersionChange(revival.id(), target);
    }

    @MutationMapping
    public ChecklistDto eventChecklistCreate(@Argument final EventChecklistCreateInput input, final Locale locale) {
        return service.createEventChecklist(new ChecklistService.NewEventChecklist(input.revivalId(), input.name(), input.date(), input.templateId()),
                locale);
    }

    @MutationMapping
    public ChecklistDto eventChecklistUpdate(@Argument final EventChecklistUpdateInput input, final Locale locale) {
        return service.updateEventChecklist(new ChecklistService.EventChecklistChange(input.id(), input.version(), input.name(), input.date(),
                Boolean.TRUE.equals(input.clearDate())), locale);
    }

    @MutationMapping
    public ChecklistDto eventChecklistCancel(@Argument final Long id, @Argument final Long version, final Locale locale) {
        return service.cancelEventChecklist(id, version, locale);
    }

    @MutationMapping
    public ChecklistDto checklistAddFromTemplate(@Argument final ChecklistAddFromTemplateInput input, final Locale locale) {
        return service.addFromTemplate(input.checklistId(), input.version(), input.templateId(), locale);
    }

    @MutationMapping
    public ChecklistItemDto checklistItemCreate(@Argument final ChecklistItemCreateInput input, final Locale locale) {
        return service.createItem(new ChecklistService.NewItem(input.checklistId(), input.text(), input.owner(), input.deadline(), input.comment(),
                input.guideItemKey()), locale);
    }

    @MutationMapping
    public ChecklistItemDto checklistItemUpdate(@Argument final ChecklistItemUpdateInput input, final Locale locale) {
        return service.updateItem(new ChecklistService.ItemChange(input.id(), input.version(), input.text(), input.owner(),
                Boolean.TRUE.equals(input.clearOwner()), input.deadline(), Boolean.TRUE.equals(input.clearDeadline()), input.comment(),
                Boolean.TRUE.equals(input.clearComment())), locale);
    }

    @MutationMapping
    public ChecklistItemDto checklistItemSetState(@Argument final ChecklistItemSetStateInput input, final Locale locale) {
        return service.setState(input.id(), input.version(), input.state(), input.reason(), locale);
    }

    @SchemaMapping(typeName = "Checklist")
    public @Nullable ChecklistTemplateSummaryDto template(final ChecklistDto checklist) {
        return service.template(checklist);
    }

    @SchemaMapping(typeName = "Checklist")
    public List<ChecklistTemplateSummaryDto> templatesAvailable(final ChecklistDto checklist) {
        return service.templatesAvailable(checklist);
    }

    @BatchMapping(typeName = "ChecklistItem", field = "logEntries")
    public Map<ChecklistItemDto, List<LogEntrySummaryDto>> logEntries(final List<ChecklistItemDto> items) {
        return items.stream().collect(Collectors.toMap(Function.identity(),
                item -> logEntryService.linkedTo(item.id()).stream().map(LogEntrySummaryDto::of).toList(), (first, second) -> first));
    }
}
