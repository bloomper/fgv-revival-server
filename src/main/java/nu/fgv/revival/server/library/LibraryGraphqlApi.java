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
package nu.fgv.revival.server.library;

import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The reference library over GraphQL (library.graphqls), and the entries guide items link to.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class LibraryGraphqlApi {

    private final LibraryService service;

    public LibraryGraphqlApi(final LibraryService service) {
        this.service = service;
    }

    private static LibraryService.Definition definition(final LibraryEntryInput input) {
        return new LibraryService.Definition(input.kind(), text(input.name()), text(input.description()), text(input.pros()),
                text(input.cons()), text(input.booking()), text(input.prices()), text(input.contact()), input.url());
    }

    private static LibraryService.@Nullable Text text(final @Nullable LocalizedTextInput input) {
        return input == null ? null : new LibraryService.Text(input.sv(), input.en());
    }

    @QueryMapping
    public List<LibraryEntryDto> libraryEntries(@Argument final @Nullable Boolean includeRetired) {
        return service.list(Boolean.TRUE.equals(includeRetired));
    }

    @QueryMapping
    public LibraryEntryDto libraryEntry(@Argument final Long id) {
        return service.get(id);
    }

    @MutationMapping
    public LibraryEntryDto libraryEntryCreate(@Argument final LibraryEntryInput input) {
        return service.create(definition(input));
    }

    @MutationMapping
    public LibraryEntryDto libraryEntryUpdate(@Argument final Long id, @Argument final Long version, @Argument final LibraryEntryInput input) {
        return service.update(id, version, definition(input));
    }

    @MutationMapping
    public LibraryEntryDto libraryEntryRetire(@Argument final Long id, @Argument final Long version) {
        return service.retire(id, version);
    }

    @MutationMapping
    public LibraryEntryDto libraryEntryRestore(@Argument final Long id, @Argument final Long version) {
        return service.restore(id, version);
    }

    /**
     * The entries each item links to, by kind and name; none for callers who do not read the library.
     */
    @BatchMapping(typeName = "GuideItem", field = "libraryEntries")
    public Map<GuideVersionView.Item, List<LibraryEntryDto>> libraryEntries(final List<GuideVersionView.Item> items) {
        final Map<GuideVersionView.Item, List<LibraryEntryDto>> result = new HashMap<>();

        if (!service.canRead()) {
            items.forEach(item -> result.put(item, List.of()));
            return result;
        }

        final Map<Long, List<Long>> links = service.linksOf(items.stream().map(GuideVersionView.Item::id).toList());
        final Map<Long, LibraryEntryDto> entries = service.byIds(links.values().stream().flatMap(Collection::stream).toList());

        items.forEach(item -> result.put(item, links.getOrDefault(item.id(), List.of()).stream()
                .map(entries::get)
                .filter(Objects::nonNull)
                .sorted(LibraryService.DTO_ORDER)
                .toList()));
        return result;
    }

    public record LibraryEntryInput(LibraryEntryKind kind, LocalizedTextInput name, @Nullable LocalizedTextInput description,
                                    @Nullable LocalizedTextInput pros, @Nullable LocalizedTextInput cons,
                                    @Nullable LocalizedTextInput booking, @Nullable LocalizedTextInput prices,
                                    @Nullable LocalizedTextInput contact, @Nullable String url) {
    }
}
