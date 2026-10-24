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

import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reference figures from earlier revivals over GraphQL. A thin adapter: every rule is in
 * {@link ReferenceRevivalService}.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Controller
public class ReferenceRevivalGraphqlApi {

    private final ReferenceRevivalService service;

    public ReferenceRevivalGraphqlApi(final ReferenceRevivalService service) {
        this.service = service;
    }

    public record ReferenceFigureInput(String categoryId, @Nullable BigDecimal budgeted, @Nullable BigDecimal actual) {
    }

    public record ReferenceRevivalInput(String label, Integer year, @Nullable Integer participants, @Nullable String comment,
                                        @Nullable List<ReferenceFigureInput> figures) {
    }

    private static ReferenceRevivalService.Definition definition(final ReferenceRevivalInput input) {
        return new ReferenceRevivalService.Definition(input.label(), input.year(), input.participants(), input.comment(),
                input.figures() == null ? List.of() : input.figures().stream()
                        .map(figure -> new ReferenceRevivalService.FigureInput(figure.categoryId(), figure.budgeted(), figure.actual()))
                        .toList());
    }

    @QueryMapping
    public List<ReferenceRevivalDto> referenceRevivals() {
        return service.list();
    }

    @QueryMapping
    public ReferenceRevivalDto referenceRevival(@Argument final Long id) {
        return service.get(id);
    }

    @MutationMapping
    public ReferenceRevivalDto referenceRevivalCreate(@Argument final ReferenceRevivalInput input) {
        return service.create(definition(input));
    }

    @MutationMapping
    public ReferenceRevivalDto referenceRevivalUpdate(@Argument final Long id, @Argument final Long version, @Argument final ReferenceRevivalInput input) {
        return service.update(id, version, definition(input));
    }

    @MutationMapping
    public void referenceRevivalDelete(@Argument final Long id, @Argument final Long version) {
        service.delete(id, version);
    }
}
