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

import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * GraphQL's {@code ApplicationSettings}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record ApplicationSettingsDto(GuideVersionView.@Nullable Text terms, @Nullable BigDecimal defaultParticipationFee, Long version) {

    static ApplicationSettingsDto of(final ApplicationSettings settings) {
        return new ApplicationSettingsDto(settings.getTermsSv() == null ? null : GuideVersionView.Text.plain(settings.getTermsSv(), settings.getTermsEn()),
                settings.getDefaultParticipationFee(), settings.getVersion() == null ? 0L : settings.getVersion());
    }
}
