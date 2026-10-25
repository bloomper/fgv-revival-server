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
package nu.fgv.revival.server.member;

import nu.fgv.revival.server.checklist.ChecklistItemDto;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItemDto;
import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import nu.fgv.revival.server.util.catalog.CatalogService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The areas of responsibility, and every field that shows one: rows store the id, GraphQL returns the entry.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class CapabilityGraphqlApi {

    private final CapabilityService service;

    public CapabilityGraphqlApi(final CapabilityService service) {
        this.service = service;
    }

    private static CatalogService.Names names(final LocalizedTextInput input) {
        return new CatalogService.Names(input.sv() == null ? "" : input.sv(), input.en());
    }

    @QueryMapping
    public List<CapabilityDto> capabilities(@Argument final @Nullable Boolean includeRetired) {
        return service.list(Boolean.TRUE.equals(includeRetired)).stream().map(CapabilityDto::of).toList();
    }

    @MutationMapping
    public CapabilityDto capabilityCreate(@Argument final CapabilityCreateInput input) {
        return CapabilityDto.of(service.create(names(input.name())));
    }

    @MutationMapping
    public CapabilityDto capabilityUpdate(@Argument final CapabilityUpdateInput input) {
        return CapabilityDto.of(service.rename(input.id(), input.version(), names(input.name())));
    }

    @MutationMapping
    public List<CapabilityDto> capabilityMove(@Argument final String id, @Argument final Long version, @Argument final int position) {
        return service.move(id, version, position).stream().map(CapabilityDto::of).toList();
    }

    @MutationMapping
    public CapabilityDto capabilityRetire(@Argument final String id, @Argument final Long version) {
        return CapabilityDto.of(service.retire(id, version));
    }

    @MutationMapping
    public CapabilityDto capabilityRestore(@Argument final String id, @Argument final Long version) {
        return CapabilityDto.of(service.restore(id, version));
    }

    @MutationMapping
    public void capabilityDelete(@Argument final String id, @Argument final Long version) {
        service.delete(id, version);
    }

    @BatchMapping(typeName = "Capability", field = "inUse")
    public Map<CapabilityDto, Boolean> inUse(final List<CapabilityDto> capabilities) {
        final Map<String, Boolean> used = service.inUse(capabilities.stream().map(CapabilityDto::id).toList());

        return capabilities.stream().distinct().collect(Collectors.toMap(Function.identity(), capability -> used.get(capability.id())));
    }

    @BatchMapping(typeName = "RevivalMember", field = "capabilities")
    public Map<RevivalMemberDto, List<CapabilityDto>> memberCapabilities(final List<RevivalMemberDto> members) {
        return held(members, RevivalMemberDto::capabilities);
    }

    @BatchMapping(typeName = "RevivalRoleAssignment", field = "capabilities")
    public Map<RevivalRoleAssignmentDto, List<CapabilityDto>> assignmentCapabilities(final List<RevivalRoleAssignmentDto> assignments) {
        return held(assignments, RevivalRoleAssignmentDto::capabilities);
    }

    @BatchMapping(typeName = "ChecklistItem", field = "defaultCapability")
    public Map<ChecklistItemDto, CapabilityDto> checklistItemCapability(final List<ChecklistItemDto> items) {
        return hint(items, ChecklistItemDto::defaultCapability);
    }

    @BatchMapping(typeName = "ChecklistTemplateItem", field = "defaultCapability")
    public Map<ChecklistTemplateItemDto, CapabilityDto> templateItemCapability(final List<ChecklistTemplateItemDto> items) {
        return hint(items, ChecklistTemplateItemDto::defaultCapability);
    }

    private <T> Map<T, List<CapabilityDto>> held(final List<T> rows, final Function<T, Collection<String>> ids) {
        final Map<String, CapabilityDto> found = service.resolve(rows.stream().flatMap(row -> ids.apply(row).stream()).toList()).stream()
                .collect(Collectors.toMap(CapabilityDto::id, Function.identity()));
        final Map<T, List<CapabilityDto>> result = new HashMap<>();

        rows.forEach(row -> result.put(row, ids.apply(row).stream().map(found::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(CapabilityDto::position)).toList()));
        return result;
    }

    private <T> Map<T, CapabilityDto> hint(final List<T> rows, final Function<T, @Nullable String> id) {
        final Map<String, CapabilityDto> found = service.resolve(rows.stream().map(id).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(CapabilityDto::id, Function.identity()));
        final Map<T, CapabilityDto> result = new HashMap<>();

        rows.forEach(row -> {
            final String key = id.apply(row);

            if (key != null && found.containsKey(key)) {
                result.put(row, found.get(key));
            }
        });
        return result;
    }

    public record CapabilityCreateInput(LocalizedTextInput name) {
    }

    public record CapabilityUpdateInput(String id, Long version, LocalizedTextInput name) {
    }
}
