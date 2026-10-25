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
package nu.fgv.revival.server.settings;

import nu.fgv.revival.server.guide.GuideGraphqlApi.LocalizedTextInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;

/**
 * Application settings over GraphQL (settings.graphqls).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ApplicationSettingsGraphqlApi {

    private final ApplicationSettingsService service;

    public ApplicationSettingsGraphqlApi(final ApplicationSettingsService service) {
        this.service = service;
    }

    @QueryMapping
    public ApplicationSettingsDto applicationSettings() {
        return service.get();
    }

    @MutationMapping
    public ApplicationSettingsDto applicationSettingsUpdate(@Argument final Long version, @Argument final ApplicationSettingsInput input) {
        return service.update(version, input.terms() == null ? null : input.terms().sv(), input.terms() == null ? null : input.terms().en(),
                input.defaultParticipationFee());
    }

    public record ApplicationSettingsInput(@Nullable LocalizedTextInput terms, @Nullable BigDecimal defaultParticipationFee) {
    }
}
