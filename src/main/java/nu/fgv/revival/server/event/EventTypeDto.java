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


package nu.fgv.revival.server.event;

import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * GraphQL's {@code EventType}. The checklist template is resolved from its id.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
public record EventTypeDto(Long id,
                           GuideVersionView.Text name,
                           GuideVersionView.@Nullable Text description,
                           GuideVersionView.@Nullable Text rooms,
                           GuideVersionView.@Nullable Text catering,
                           @Nullable Integer offsetMinDays,
                           @Nullable Integer offsetMaxDays,
                           int days,
                           boolean revivalPays,
                           @Nullable Long checklistTemplateId,
                           int position,
                           boolean retired,
                           Long version) {

    public static EventTypeDto of(final EventType type) {
        return new EventTypeDto(type.getId(), GuideVersionView.Text.plain(type.getNameSv(), type.getNameEn()),
                text(type.getDescriptionSv(), type.getDescriptionEn()), text(type.getRoomsSv(), type.getRoomsEn()),
                text(type.getCateringSv(), type.getCateringEn()), type.getOffsetMinDays(), type.getOffsetMaxDays(), type.getDays(),
                type.isRevivalPays(), type.getChecklistTemplateId(), type.getPosition(), type.isRetired(),
                type.getVersion() == null ? 0L : type.getVersion());
    }

    /**
     * The name in the language, Swedish when there is no English one.
     */
    public String name(final Locale locale) {
        return "en".equals(locale.getLanguage()) && name.en() != null ? name.en() : name.sv();
    }

    private static GuideVersionView.@Nullable Text text(final @Nullable String sv, final @Nullable String en) {
        return sv == null ? null : GuideVersionView.Text.plain(sv, en);
    }
}
