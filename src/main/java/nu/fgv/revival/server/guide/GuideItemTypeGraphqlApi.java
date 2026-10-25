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

import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import nu.fgv.revival.server.guide.suggestion.GuideSuggestionDto;
import nu.fgv.revival.server.util.catalog.CatalogService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The guide item types, and every field that shows one: rows store the id, GraphQL returns the entry.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class GuideItemTypeGraphqlApi {

    private final GuideItemTypeService service;

    public GuideItemTypeGraphqlApi(final GuideItemTypeService service) {
        this.service = service;
    }

    private static CatalogService.Names names(final LocalizedTextInput input) {
        return new CatalogService.Names(input.sv() == null ? "" : input.sv(), input.en());
    }

    @QueryMapping
    public List<GuideItemTypeDto> guideItemTypes(@Argument final @Nullable Boolean includeRetired) {
        return service.list(Boolean.TRUE.equals(includeRetired)).stream().map(GuideItemTypeDto::of).toList();
    }

    @MutationMapping
    public GuideItemTypeDto guideItemTypeCreate(@Argument final GuideItemTypeCreateInput input) {
        return GuideItemTypeDto.of(service.create(input.kind(), names(input.name())));
    }

    @MutationMapping
    public GuideItemTypeDto guideItemTypeUpdate(@Argument final GuideItemTypeUpdateInput input) {
        return GuideItemTypeDto.of(service.rename(input.id(), input.version(), names(input.name())));
    }

    @MutationMapping
    public List<GuideItemTypeDto> guideItemTypeMove(@Argument final String id, @Argument final Long version, @Argument final int position) {
        return service.move(id, version, position).stream().map(GuideItemTypeDto::of).toList();
    }

    @MutationMapping
    public GuideItemTypeDto guideItemTypeRetire(@Argument final String id, @Argument final Long version) {
        return GuideItemTypeDto.of(service.retire(id, version));
    }

    @MutationMapping
    public GuideItemTypeDto guideItemTypeRestore(@Argument final String id, @Argument final Long version) {
        return GuideItemTypeDto.of(service.restore(id, version));
    }

    @MutationMapping
    public void guideItemTypeDelete(@Argument final String id, @Argument final Long version) {
        service.delete(id, version);
    }

    @BatchMapping(typeName = "GuideItemType", field = "inUse")
    public Map<GuideItemTypeDto, Boolean> inUse(final List<GuideItemTypeDto> types) {
        final Map<String, Boolean> used = service.inUse(types.stream().map(GuideItemTypeDto::id).toList());

        return types.stream().distinct().collect(Collectors.toMap(Function.identity(), type -> used.get(type.id())));
    }

    @BatchMapping(typeName = "GuideItem", field = "type")
    public Map<GuideVersionView.Item, GuideItemTypeDto> itemType(final List<GuideVersionView.Item> items) {
        return types(items, GuideVersionView.Item::type);
    }

    @BatchMapping(typeName = "GuideItemSnapshot", field = "type")
    public Map<GuideChanges.ItemSnapshot, GuideItemTypeDto> snapshotType(final List<GuideChanges.ItemSnapshot> snapshots) {
        return types(snapshots, GuideChanges.ItemSnapshot::type);
    }

    @BatchMapping(typeName = "GuideSearchHit", field = "itemType")
    public Map<GuideSearch.Hit, GuideItemTypeDto> hitType(final List<GuideSearch.Hit> hits) {
        return types(hits, GuideSearch.Hit::itemType);
    }

    @BatchMapping(typeName = "GuideSuggestion", field = "proposedType")
    public Map<GuideSuggestionDto, GuideItemTypeDto> proposedType(final List<GuideSuggestionDto> suggestions) {
        return types(suggestions, GuideSuggestionDto::proposedType);
    }

    private <T> Map<T, GuideItemTypeDto> types(final List<T> rows, final Function<T, @Nullable String> id) {
        final Map<String, GuideItemType> found = service.byId(rows.stream().map(id).filter(Objects::nonNull).toList());
        final Map<T, GuideItemTypeDto> result = new HashMap<>();

        rows.forEach(row -> {
            final String key = id.apply(row);

            if (key != null && found.containsKey(key)) {
                result.put(row, GuideItemTypeDto.of(found.get(key)));
            }
        });
        return result;
    }

    public record GuideItemTypeCreateInput(GuideItemKind kind, LocalizedTextInput name) {
    }

    public record GuideItemTypeUpdateInput(String id, Long version, LocalizedTextInput name) {
    }
}
