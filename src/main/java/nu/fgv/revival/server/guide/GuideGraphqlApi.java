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

package nu.fgv.revival.server.guide;

import nu.fgv.revival.server.person.PersonNames;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Locale;

/**
 * No authorization here: the services decide (constitution II).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Controller
public class GuideGraphqlApi {

    private final GuideService service;
    private final GuideDraftService draftService;
    private final GuideVersionService versionService;
    private final PersonNames personNames;

    public GuideGraphqlApi(final GuideService service, final GuideDraftService draftService, final GuideVersionService versionService,
                           final PersonNames personNames) {
        this.service = service;
        this.draftService = draftService;
        this.versionService = versionService;
        this.personNames = personNames;
    }

    public record GuideDraftPublishInput(Long id, Long version, @Nullable String note) {
    }

    @QueryMapping
    public List<GuideVersionSummaryDto> guideVersions() {
        return service.findPublished();
    }

    @QueryMapping
    public @Nullable GuideVersionView guideLatestVersion() {
        return service.findLatest().orElse(null);
    }

    @QueryMapping
    public GuideVersionView guideVersion(@Argument final Long id) {
        return service.findById(id);
    }

    @QueryMapping
    public List<GuideSearch.Hit> guideSearch(@Argument final @Nullable Long versionId, @Argument final String query,
                                             @Argument final @Nullable Integer first) {
        return service.search(versionId, query, first == null ? 50 : first).hits();
    }

    @QueryMapping
    public @Nullable GuideDraftDto guideDraft() {
        return service.draft();
    }

    @MutationMapping
    public GuideVersionView guideDraftPublish(@Argument final GuideDraftPublishInput input) {
        return draftService.publish(input.id(), input.version(), input.note());
    }

    @MutationMapping
    public void guideVersionDelete(@Argument final Long id, @Argument final Long version) {
        versionService.delete(id, version);
    }

    @MutationMapping
    public void guideDraftDiscard(@Argument final Long id, @Argument final Long version) {
        draftService.discard(id, version);
    }

    @QueryMapping
    public GuideChanges.Result guideChanges(@Argument final Long fromId, @Argument final Long toId) {
        return service.changes(fromId, toId);
    }

    public record LocalizedTextInput(@Nullable String sv, @Nullable String en) {
    }

    public record GuideIntroductionInput(Long versionId, Long version, LocalizedTextInput introduction) {
    }

    public record GuideChapterCreateInput(Long versionId, @Nullable Integer position, LocalizedTextInput title) {
    }

    public record GuideChapterInput(Long id, Long version, LocalizedTextInput title) {
    }

    public record GuideChapterMoveInput(Long id, Long version, int position) {
    }

    public record GuideSectionCreateInput(Long chapterId, @Nullable Integer position, LocalizedTextInput title) {
    }

    public record GuideSectionUpdateInput(Long id, Long version, LocalizedTextInput title) {
    }

    public record GuideSectionMoveInput(Long id, Long version, Long chapterId, int position) {
    }

    public record GuideItemCreateInput(Long chapterId, @Nullable Long sectionId, @Nullable Integer position, String type, LocalizedTextInput text) {
    }

    public record GuideItemUpdateInput(Long id, Long version, String type, LocalizedTextInput text) {
    }

    public record GuideItemMoveInput(Long id, Long version, Long chapterId, @Nullable Long sectionId, int position) {
    }

    @MutationMapping
    public @Nullable GuideDraftDto guideDraftStart() {
        draftService.start();
        return service.draft();
    }

    @MutationMapping
    public GuideVersionView guideIntroductionUpdate(@Argument final GuideIntroductionInput input) {
        return service.findById(draftService.updateIntroduction(input.versionId(), input.version(), input.introduction().sv(), input.introduction().en()));
    }

    @MutationMapping
    public GuideVersionView.Chapter guideChapterCreate(@Argument final GuideChapterCreateInput input) {
        return service.draftChapter(draftService.createChapter(input.versionId(), input.position(), input.title().sv(), input.title().en()));
    }

    @MutationMapping
    public GuideVersionView guideChapterMove(@Argument final GuideChapterMoveInput input) {
        return service.findById(draftService.moveChapter(input.id(), input.version(), input.position()));
    }

    @MutationMapping
    public void guideChapterRemove(@Argument final Long id, @Argument final Long version) {
        draftService.removeChapter(id, version);
    }

    @MutationMapping
    public GuideVersionView.Chapter guideChapterUpdate(@Argument final GuideChapterInput input) {
        draftService.updateChapter(input.id(), input.version(), input.title().sv(), input.title().en());
        return service.draftChapter(input.id());
    }

    @MutationMapping
    public GuideVersionView.Section guideSectionCreate(@Argument final GuideSectionCreateInput input) {
        return service.draftSection(draftService.createSection(input.chapterId(), input.position(), input.title().sv(), input.title().en()));
    }

    @MutationMapping
    public GuideVersionView.Section guideSectionUpdate(@Argument final GuideSectionUpdateInput input) {
        return service.draftSection(draftService.updateSection(input.id(), input.version(), input.title().sv(), input.title().en()));
    }

    @MutationMapping
    public GuideVersionView.Chapter guideSectionMove(@Argument final GuideSectionMoveInput input) {
        return service.draftChapter(draftService.moveSection(input.id(), input.version(), input.chapterId(), input.position()));
    }

    @MutationMapping
    public void guideSectionRemove(@Argument final Long id, @Argument final Long version) {
        draftService.removeSection(id, version);
    }

    @MutationMapping
    public GuideVersionView.Item guideItemCreate(@Argument final GuideItemCreateInput input) {
        return service.draftItem(draftService.createItem(input.chapterId(), input.sectionId(), input.position(), input.type(),
                input.text().sv(), input.text().en()));
    }

    @MutationMapping
    public GuideVersionView.Item guideItemUpdate(@Argument final GuideItemUpdateInput input) {
        return service.draftItem(draftService.updateItem(input.id(), input.version(), input.type(), input.text().sv(), input.text().en()));
    }

    @MutationMapping
    public GuideVersionView.Chapter guideItemMove(@Argument final GuideItemMoveInput input) {
        return service.draftChapter(draftService.moveItem(input.id(), input.version(), input.chapterId(), input.sectionId(), input.position()));
    }

    @MutationMapping
    public void guideItemRemove(@Argument final Long id, @Argument final Long version) {
        draftService.removeItem(id, version);
    }

    @SchemaMapping(typeName = "GuideVersion", field = "publishedBy")
    public @Nullable String publishedBy(final GuideVersionView version, final Locale locale) {
        return version.publishedBy() == null ? null : personNames.byIds(List.of(version.publishedBy()), locale).get(version.publishedBy());
    }

    @SchemaMapping(typeName = "GuideVersionSummary", field = "publishedBy")
    public @Nullable String summaryPublishedBy(final GuideVersionSummaryDto version, final Locale locale) {
        return version.publishedBy() == null ? null : personNames.byIds(List.of(version.publishedBy()), locale).get(version.publishedBy());
    }

    @SchemaMapping(typeName = "GuideVersionSummary", field = "revivalCount")
    public int revivalCount(final GuideVersionSummaryDto version) {
        return versionService.usage(version.id()).revivalCount();
    }

    @SchemaMapping(typeName = "GuideVersionSummary", field = "suggestionCount")
    public int suggestionCount(final GuideVersionSummaryDto version) {
        return versionService.usage(version.id()).suggestionCount();
    }

    @SchemaMapping(typeName = "GuideVersionSummary", field = "deletable")
    public boolean deletable(final GuideVersionSummaryDto version) {
        return versionService.usage(version.id()).deletable();
    }

    @SchemaMapping(typeName = "GuideVersion", field = "basedOn")
    public @Nullable GuideVersionSummaryDto basedOn(final GuideVersionView version) {
        return service.summary(version.basedOnId());
    }
}
