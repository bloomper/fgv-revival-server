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
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The budget categories, and every field that shows one: rows store the id, GraphQL returns the entry. A thin adapter:
 * every rule is in {@link BudgetCategoryService}.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Controller
public class BudgetCategoryGraphqlApi {

    private final BudgetCategoryService service;

    public BudgetCategoryGraphqlApi(final BudgetCategoryService service) {
        this.service = service;
    }

    public record BudgetCategoryCreateInput(BudgetCategoryKind kind, @Nullable BudgetCategoryRole role, LocalizedTextInput name) {
    }

    public record BudgetCategoryUpdateInput(String id, Long version, LocalizedTextInput name, @Nullable BudgetCategoryRole role) {
    }

    private static CatalogService.Names names(final LocalizedTextInput input) {
        return new CatalogService.Names(input.sv() == null ? "" : input.sv(), input.en());
    }

    @QueryMapping
    public List<BudgetCategoryDto> budgetCategories(@Argument final @Nullable Boolean includeRetired) {
        return service.list(Boolean.TRUE.equals(includeRetired)).stream().map(BudgetCategoryDto::of).toList();
    }

    @MutationMapping
    public BudgetCategoryDto budgetCategoryCreate(@Argument final BudgetCategoryCreateInput input) {
        return BudgetCategoryDto.of(service.create(input.kind(), input.role(), names(input.name())));
    }

    @MutationMapping
    public BudgetCategoryDto budgetCategoryUpdate(@Argument final BudgetCategoryUpdateInput input) {
        return BudgetCategoryDto.of(service.update(input.id(), input.version(), names(input.name()), input.role()));
    }

    @MutationMapping
    public List<BudgetCategoryDto> budgetCategoryMove(@Argument final String id, @Argument final Long version, @Argument final int position) {
        return service.move(id, version, position).stream().map(BudgetCategoryDto::of).toList();
    }

    @MutationMapping
    public BudgetCategoryDto budgetCategoryRetire(@Argument final String id, @Argument final Long version) {
        return BudgetCategoryDto.of(service.retire(id, version));
    }

    @MutationMapping
    public BudgetCategoryDto budgetCategoryRestore(@Argument final String id, @Argument final Long version) {
        return BudgetCategoryDto.of(service.restore(id, version));
    }

    @MutationMapping
    public void budgetCategoryDelete(@Argument final String id, @Argument final Long version) {
        service.delete(id, version);
    }

    @BatchMapping(typeName = "BudgetCategory", field = "inUse")
    public Map<BudgetCategoryDto, Boolean> inUse(final List<BudgetCategoryDto> categories) {
        final Map<String, Boolean> used = service.inUse(categories.stream().map(BudgetCategoryDto::id).toList());

        return categories.stream().distinct().collect(Collectors.toMap(Function.identity(), category -> used.get(category.id())));
    }

    @BatchMapping(typeName = "BudgetLine", field = "category")
    public Map<BudgetDtos.Line, BudgetCategoryDto> lineCategory(final List<BudgetDtos.Line> lines) {
        return categories(lines, BudgetDtos.Line::categoryId);
    }

    @BatchMapping(typeName = "Transaction", field = "category")
    public Map<BudgetDtos.Transaction, BudgetCategoryDto> transactionCategory(final List<BudgetDtos.Transaction> transactions) {
        return categories(transactions, BudgetDtos.Transaction::categoryId);
    }

    @BatchMapping(typeName = "EventTypeBudgetLine", field = "category")
    public Map<EventTypeBudgetLineDto, BudgetCategoryDto> typeLineCategory(final List<EventTypeBudgetLineDto> lines) {
        return categories(lines, EventTypeBudgetLineDto::categoryId);
    }

    @BatchMapping(typeName = "ReferenceFigure", field = "category")
    public Map<ReferenceRevivalDto.Figure, BudgetCategoryDto> figureCategory(final List<ReferenceRevivalDto.Figure> figures) {
        return categories(figures, ReferenceRevivalDto.Figure::categoryId);
    }

    private <T> Map<T, BudgetCategoryDto> categories(final List<T> rows, final Function<T, String> id) {
        final Map<String, BudgetCategoryDto> found = service.dtos(rows.stream().map(id).toList());
        final Map<T, BudgetCategoryDto> result = new HashMap<>();

        rows.forEach(row -> {
            final BudgetCategoryDto category = found.get(id.apply(row));

            if (category != null) {
                result.put(row, category);
            }
        });
        return result;
    }
}
