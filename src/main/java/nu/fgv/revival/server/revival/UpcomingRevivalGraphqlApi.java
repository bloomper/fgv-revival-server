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
package nu.fgv.revival.server.revival;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Locale;

/**
 * The revivals coming up over GraphQL (revival.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class UpcomingRevivalGraphqlApi {

    private final UpcomingRevivals upcomingRevivals;

    public UpcomingRevivalGraphqlApi(final UpcomingRevivals upcomingRevivals) {
        this.upcomingRevivals = upcomingRevivals;
    }

    @QueryMapping
    public List<UpcomingRevivalDto> upcomingRevivals(final Locale locale) {
        return upcomingRevivals.list(locale);
    }
}
