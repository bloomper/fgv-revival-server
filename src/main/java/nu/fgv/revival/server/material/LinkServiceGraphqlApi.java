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
package nu.fgv.revival.server.material;

import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import nu.fgv.revival.server.util.catalog.CatalogService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The link services over GraphQL (material.graphqls). A thin adapter: every rule is in {@link LinkServiceCatalog}.
 *
 * @author Anders Jacobsson
 * @since 0.9
 */
@Controller
public class LinkServiceGraphqlApi {

    private final LinkServiceCatalog catalog;

    public LinkServiceGraphqlApi(final LinkServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @QueryMapping
    public List<LinkServiceDto> linkServices(@Argument final @Nullable Boolean includeRetired) {
        return catalog.list(Boolean.TRUE.equals(includeRetired)).stream().map(LinkServiceDto::of).toList();
    }

    @MutationMapping
    public LinkServiceDto linkServiceCreate(@Argument final LinkServiceInput input) {
        return LinkServiceDto.of(catalog.create(input.names(), input.hosts(), input.homeUrl()));
    }

    @MutationMapping
    public LinkServiceDto linkServiceUpdate(@Argument final String id, @Argument final Long version, @Argument final LinkServiceInput input) {
        return LinkServiceDto.of(catalog.update(id, version, input.names(), input.hosts(), input.homeUrl()));
    }

    @MutationMapping
    public List<LinkServiceDto> linkServiceMove(@Argument final String id, @Argument final Long version, @Argument final int position) {
        return catalog.move(id, version, position).stream().map(LinkServiceDto::of).toList();
    }

    @MutationMapping
    public LinkServiceDto linkServiceRetire(@Argument final String id, @Argument final Long version) {
        return LinkServiceDto.of(catalog.retire(id, version));
    }

    @MutationMapping
    public LinkServiceDto linkServiceRestore(@Argument final String id, @Argument final Long version) {
        return LinkServiceDto.of(catalog.restore(id, version));
    }

    @MutationMapping
    public void linkServiceDelete(@Argument final String id, @Argument final Long version) {
        catalog.delete(id, version);
    }

    @BatchMapping(typeName = "LinkService", field = "inUse")
    public Map<LinkServiceDto, Boolean> inUse(final List<LinkServiceDto> services) {
        final Map<String, Boolean> used = catalog.inUse(services.stream().map(LinkServiceDto::id).toList());

        return services.stream().distinct().collect(Collectors.toMap(Function.identity(), service -> used.get(service.id())));
    }

    public record LinkServiceInput(LocalizedTextInput name, @Nullable List<String> hosts, @Nullable String homeUrl) {

        CatalogService.Names names() {
            return new CatalogService.Names(name.sv() == null ? "" : name.sv(), name.en());
        }
    }
}
