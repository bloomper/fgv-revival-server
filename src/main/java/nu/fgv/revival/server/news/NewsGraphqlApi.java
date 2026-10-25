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
package nu.fgv.revival.server.news;

import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.List;

/**
 * News over GraphQL (news.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class NewsGraphqlApi {

    private final NewsService service;

    public NewsGraphqlApi(final NewsService service) {
        this.service = service;
    }

    @QueryMapping
    public List<NewsDto> news(@Argument final @Nullable Boolean all, @Argument final @Nullable Integer limit) {
        return service.list(Boolean.TRUE.equals(all), limit);
    }

    @MutationMapping
    public NewsDto newsCreate(@Argument final NewsInput input) {
        return service.create(input.subject(), input.text(), input.visibleFrom(), input.visibleTo());
    }

    @MutationMapping
    public NewsDto newsUpdate(@Argument final Long id, @Argument final Long version, @Argument final NewsInput input) {
        return service.update(id, version, input.subject(), input.text(), input.visibleFrom(), input.visibleTo());
    }

    @MutationMapping
    public void newsDelete(@Argument final Long id, @Argument final Long version) {
        service.delete(id, version);
    }

    public record NewsInput(String subject, String text, @Nullable LocalDate visibleFrom, @Nullable LocalDate visibleTo) {
    }
}
