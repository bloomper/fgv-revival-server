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

import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Reference figures from earlier revivals over GraphQL.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ReferenceRevivalGraphqlApi {

    private final ExternalIds ids;

    private final ReferenceRevivalService service;

    public ReferenceRevivalGraphqlApi(final ReferenceRevivalService service, final ExternalIds ids) {
        this.ids = ids;
        this.service = service;
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
    public ReferenceRevivalDto referenceRevival(@Argument final UUID id) {
        return service.get(ids.idOf(ReferenceRevival.class, id));
    }

    @MutationMapping
    public ReferenceRevivalDto referenceRevivalCreate(@Argument final ReferenceRevivalInput input) {
        return service.create(definition(input));
    }

    @MutationMapping
    public ReferenceRevivalDto referenceRevivalUpdate(@Argument final UUID id, @Argument final Long version, @Argument final ReferenceRevivalInput input) {
        return service.update(ids.idOf(ReferenceRevival.class, id), version, definition(input));
    }

    @MutationMapping
    public void referenceRevivalDelete(@Argument final UUID id, @Argument final Long version) {
        service.delete(ids.idOf(ReferenceRevival.class, id), version);
    }

    public record ReferenceFigureInput(String categoryId, @Nullable BigDecimal budgeted, @Nullable BigDecimal actual) {
    }

    public record ReferenceRevivalInput(String label, Integer year, @Nullable Integer participants,
                                        @Nullable String comment,
                                        @Nullable List<ReferenceFigureInput> figures) {
    }
}
