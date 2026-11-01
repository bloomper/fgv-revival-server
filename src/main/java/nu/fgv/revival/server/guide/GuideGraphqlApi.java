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

import nu.fgv.revival.server.library.LibraryEntry;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * No authorization here: the services decide.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class GuideGraphqlApi {

    private final ExternalIds ids;

    private final GuideService service;
    private final GuideDraftService draftService;
    private final GuideVersionService versionService;
    private final PersonNames personNames;

    public GuideGraphqlApi(final GuideService service, final GuideDraftService draftService, final GuideVersionService versionService,
                           final PersonNames personNames,
                           final ExternalIds ids) {
        this.ids = ids;
        this.service = service;
        this.draftService = draftService;
        this.versionService = versionService;
        this.personNames = personNames;
    }

    @QueryMapping
    public boolean guideReadable() {
        return service.canRead();
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
    public GuideVersionView guideVersion(@Argument final UUID id) {
        return service.findById(ids.idOf(GuideVersion.class, id));
    }

    @QueryMapping
    public List<GuideSearch.Hit> guideSearch(@Argument final @Nullable UUID versionId, @Argument final String query,
                                             @Argument final @Nullable Integer first) {
        return service.search(ids.idOfNullable(GuideVersion.class, versionId), query, first == null ? 50 : first).hits();
    }

    @QueryMapping
    public @Nullable GuideDraftDto guideDraft() {
        return service.draft();
    }

    @MutationMapping
    public GuideVersionView guideDraftPublish(@Argument final GuideDraftPublishInput input) {
        return draftService.publish(ids.idOf(GuideVersion.class, input.id()), input.version(), input.note());
    }

    @MutationMapping
    public void guideVersionDelete(@Argument final UUID id, @Argument final Long version) {
        versionService.delete(ids.idOf(GuideVersion.class, id), version);
    }

    @MutationMapping
    public void guideDraftDiscard(@Argument final UUID id, @Argument final Long version) {
        draftService.discard(ids.idOf(GuideVersion.class, id), version);
    }

    @QueryMapping
    public GuideChanges.Result guideChanges(@Argument final UUID fromId, @Argument final UUID toId) {
        return service.changes(ids.idOf(GuideVersion.class, fromId), ids.idOf(GuideVersion.class, toId));
    }

    @MutationMapping
    public @Nullable GuideDraftDto guideDraftStart() {
        draftService.start();
        return service.draft();
    }

    @MutationMapping
    public GuideVersionView guideIntroductionUpdate(@Argument final GuideIntroductionInput input) {
        return service.findById(draftService.updateIntroduction(ids.idOf(GuideVersion.class, input.versionId()), input.version(), input.introduction().sv(), input.introduction().en()));
    }

    @MutationMapping
    public GuideVersionView.Chapter guideChapterCreate(@Argument final GuideChapterCreateInput input) {
        return service.draftChapter(draftService.createChapter(ids.idOf(GuideVersion.class, input.versionId()), input.position(), input.title().sv(), input.title().en()));
    }

    @MutationMapping
    public GuideVersionView guideChapterMove(@Argument final GuideChapterMoveInput input) {
        return service.findById(draftService.moveChapter(ids.idOf(GuideChapter.class, input.id()), input.version(), input.position()));
    }

    @MutationMapping
    public void guideChapterRemove(@Argument final UUID id, @Argument final Long version) {
        draftService.removeChapter(ids.idOf(GuideChapter.class, id), version);
    }

    @MutationMapping
    public GuideVersionView.Chapter guideChapterUpdate(@Argument final GuideChapterInput input) {
        draftService.updateChapter(ids.idOf(GuideChapter.class, input.id()), input.version(), input.title().sv(), input.title().en());
        return service.draftChapter(ids.idOf(GuideChapter.class, input.id()));
    }

    @MutationMapping
    public GuideVersionView.Section guideSectionCreate(@Argument final GuideSectionCreateInput input) {
        return service.draftSection(draftService.createSection(ids.idOf(GuideChapter.class, input.chapterId()), input.position(), input.title().sv(), input.title().en()));
    }

    @MutationMapping
    public GuideVersionView.Section guideSectionUpdate(@Argument final GuideSectionUpdateInput input) {
        return service.draftSection(draftService.updateSection(ids.idOf(GuideSection.class, input.id()), input.version(), input.title().sv(), input.title().en()));
    }

    @MutationMapping
    public GuideVersionView.Chapter guideSectionMove(@Argument final GuideSectionMoveInput input) {
        return service.draftChapter(draftService.moveSection(ids.idOf(GuideSection.class, input.id()), input.version(), ids.idOf(GuideChapter.class, input.chapterId()), input.position()));
    }

    @MutationMapping
    public void guideSectionRemove(@Argument final UUID id, @Argument final Long version) {
        draftService.removeSection(ids.idOf(GuideSection.class, id), version);
    }

    @MutationMapping
    public GuideVersionView.Item guideItemCreate(@Argument final GuideItemCreateInput input) {
        return service.draftItem(draftService.createItem(ids.idOf(GuideChapter.class, input.chapterId()), ids.idOfNullable(GuideSection.class, input.sectionId()), input.position(), input.type(),
                input.text().sv(), input.text().en(), ids.idsOfNullable(LibraryEntry.class, input.libraryEntryIds())));
    }

    @MutationMapping
    public GuideVersionView.Item guideItemUpdate(@Argument final GuideItemUpdateInput input) {
        return service.draftItem(draftService.updateItem(ids.idOf(GuideItem.class, input.id()), input.version(), input.type(), input.text().sv(), input.text().en(),
                ids.idsOfNullable(LibraryEntry.class, input.libraryEntryIds())));
    }

    @MutationMapping
    public GuideVersionView.Chapter guideItemMove(@Argument final GuideItemMoveInput input) {
        return service.draftChapter(draftService.moveItem(ids.idOf(GuideItem.class, input.id()), input.version(), ids.idOf(GuideChapter.class, input.chapterId()), ids.idOfNullable(GuideSection.class, input.sectionId()), input.position()));
    }

    @MutationMapping
    public void guideItemRemove(@Argument final UUID id, @Argument final Long version) {
        draftService.removeItem(ids.idOf(GuideItem.class, id), version);
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
        return versionService.usage(ids.idOf(GuideVersion.class, version.id())).revivalCount();
    }

    @SchemaMapping(typeName = "GuideVersionSummary", field = "suggestionCount")
    public int suggestionCount(final GuideVersionSummaryDto version) {
        return versionService.usage(ids.idOf(GuideVersion.class, version.id())).suggestionCount();
    }

    @SchemaMapping(typeName = "GuideVersionSummary", field = "deletable")
    public boolean deletable(final GuideVersionSummaryDto version) {
        return versionService.usage(ids.idOf(GuideVersion.class, version.id())).deletable();
    }

    @SchemaMapping(typeName = "GuideVersion", field = "basedOn")
    public @Nullable GuideVersionSummaryDto basedOn(final GuideVersionView version) {
        return service.summary(ids.idOfNullable(GuideVersion.class, version.basedOnId()));
    }

    public record GuideDraftPublishInput(UUID id, Long version, @Nullable String note) {
    }

    public record LocalizedTextInput(@Nullable String sv, @Nullable String en) {
    }

    public record GuideIntroductionInput(UUID versionId, Long version, LocalizedTextInput introduction) {
    }

    public record GuideChapterCreateInput(UUID versionId, @Nullable Integer position, LocalizedTextInput title) {
    }

    public record GuideChapterInput(UUID id, Long version, LocalizedTextInput title) {
    }

    public record GuideChapterMoveInput(UUID id, Long version, int position) {
    }

    public record GuideSectionCreateInput(UUID chapterId, @Nullable Integer position, LocalizedTextInput title) {
    }

    public record GuideSectionUpdateInput(UUID id, Long version, LocalizedTextInput title) {
    }

    public record GuideSectionMoveInput(UUID id, Long version, UUID chapterId, int position) {
    }

    public record GuideItemCreateInput(UUID chapterId, @Nullable UUID sectionId, @Nullable Integer position,
                                       String type, LocalizedTextInput text, @Nullable List<UUID> libraryEntryIds) {
    }

    public record GuideItemUpdateInput(UUID id, Long version, String type, LocalizedTextInput text, @Nullable List<UUID> libraryEntryIds) {
    }

    public record GuideItemMoveInput(UUID id, Long version, UUID chapterId, @Nullable UUID sectionId, int position) {
    }
}
