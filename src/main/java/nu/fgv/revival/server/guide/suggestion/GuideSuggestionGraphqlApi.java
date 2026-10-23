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

package nu.fgv.revival.server.guide.suggestion;

import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.RevivalTitles;
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
public class GuideSuggestionGraphqlApi {

    private final GuideSuggestionService service;
    private final PersonNames personNames;
    private final RevivalTitles titles;

    public GuideSuggestionGraphqlApi(final GuideSuggestionService service, final PersonNames personNames, final RevivalTitles titles) {
        this.service = service;
        this.personNames = personNames;
        this.titles = titles;
    }

    public record GuideSuggestionCreateInput(Long revivalId, Long versionId, GuideSuggestionKind kind, @Nullable String itemKey,
                                             @Nullable String chapterKey, @Nullable String sectionKey, @Nullable String proposedType,
                                             @Nullable String proposedTextSv, @Nullable String proposedTextEn, @Nullable String reason) {
    }

    public record LocalizedTextInput(@Nullable String sv, @Nullable String en) {
    }

    public record GuideSuggestionResolutionInput(@Nullable Boolean keep, @Nullable String type, @Nullable LocalizedTextInput text) {
    }

    public record GuideSuggestionAcceptInput(Long id, Long version, @Nullable String note, @Nullable GuideSuggestionResolutionInput resolution) {
    }

    public record GuideSuggestionRejectInput(Long id, Long version, @Nullable String reason) {
    }

    @QueryMapping
    public List<GuideSuggestionDto> guideSuggestions(@Argument final @Nullable List<GuideSuggestionStatus> status, @Argument final @Nullable Long revivalId) {
        return service.findVisible(status, revivalId);
    }

    @MutationMapping
    public GuideSuggestionDto guideSuggestionCreate(@Argument final GuideSuggestionCreateInput input) {
        return service.create(new GuideSuggestionService.Proposal(input.revivalId(), input.versionId(), input.kind(), input.itemKey(), input.chapterKey(),
                input.sectionKey(), input.proposedType(), input.proposedTextSv(), input.proposedTextEn(), input.reason()));
    }

    @MutationMapping
    public GuideSuggestionDto guideSuggestionAccept(@Argument final GuideSuggestionAcceptInput input) {
        final GuideSuggestionResolutionInput resolution = input.resolution();

        return service.accept(input.id(), input.version(), input.note(), resolution == null ? null : new GuideSuggestionService.Resolution(
                Boolean.TRUE.equals(resolution.keep()), resolution.type(),
                resolution.text() == null ? null : resolution.text().sv(), resolution.text() == null ? null : resolution.text().en()));
    }

    @MutationMapping
    public GuideSuggestionDto guideSuggestionReject(@Argument final GuideSuggestionRejectInput input) {
        return service.reject(input.id(), input.version(), input.reason());
    }

    @MutationMapping
    public GuideSuggestionDto guideSuggestionWithdraw(@Argument final Long id, @Argument final Long version) {
        return service.withdraw(id, version);
    }

    @SchemaMapping(typeName = "GuideSuggestionRevival", field = "title")
    public String revivalTitle(final GuideSuggestionDto.Revival revival, final Locale locale) {
        return titles.of(revival.spexId(), revival.year(), locale);
    }

    @SchemaMapping(typeName = "GuideSuggestion", field = "author")
    public @Nullable String author(final GuideSuggestionDto suggestion, final Locale locale) {
        return personNames.byIds(List.of(suggestion.authorId()), locale).get(suggestion.authorId());
    }

    @SchemaMapping(typeName = "GuideSuggestion", field = "decidedBy")
    public @Nullable String decidedBy(final GuideSuggestionDto suggestion, final Locale locale) {
        return suggestion.decidedById() == null ? null : personNames.byIds(List.of(suggestion.decidedById()), locale).get(suggestion.decidedById());
    }
}
