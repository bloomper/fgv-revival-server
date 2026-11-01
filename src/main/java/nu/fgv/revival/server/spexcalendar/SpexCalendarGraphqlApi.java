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
package nu.fgv.revival.server.spexcalendar;

import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The current Chalmersspex's show dates over GraphQL (spex-calendar.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SpexCalendarGraphqlApi {

    private final ExternalIds ids;

    private final SpexCalendarService service;

    public SpexCalendarGraphqlApi(final SpexCalendarService service, final ExternalIds ids) {
        this.ids = ids;
        this.service = service;
    }

    @QueryMapping
    public List<SpexCalendarDateDto> spexCalendarDates(@Argument final @Nullable LocalDate from, @Argument final @Nullable LocalDate to) {
        return service.list(from, to);
    }

    @MutationMapping
    public SpexCalendarDateDto spexCalendarDateCreate(@Argument final SpexCalendarDateInput input) {
        return service.create(input.date(), input.label());
    }

    @MutationMapping
    public SpexCalendarDateDto spexCalendarDateUpdate(@Argument final UUID id, @Argument final Long version, @Argument final SpexCalendarDateInput input) {
        return service.update(ids.idOf(SpexCalendarDate.class, id), version, input.date(), input.label());
    }

    @MutationMapping
    public void spexCalendarDateDelete(@Argument final UUID id, @Argument final Long version) {
        service.delete(ids.idOf(SpexCalendarDate.class, id), version);
    }

    public record SpexCalendarDateInput(LocalDate date, String label) {
    }
}
