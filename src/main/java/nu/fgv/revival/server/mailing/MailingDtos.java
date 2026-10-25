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

package nu.fgv.revival.server.mailing;

import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.spex.SpexareDirectory;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The GraphQL types of mailings and the mailing plan (mailing.graphqls). Recipients by name only, never an address.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class MailingDtos {

    private MailingDtos() {
    }

    public record MailingAudience(MailingAudienceKind kind, SpexareDirectory.@Nullable Position position,
                                  @Nullable EventDto event) {
    }

    public record MailingRecipientDto(String displayName, MailingRecipientStatus status) {
    }

    public record MailingRecipientPreview(String displayName, boolean hasAddress) {
    }

    /**
     * A mailing; {@code plannedFor} is resolved from the template and plan event ids.
     */
    public record MailingDto(Long id, Long revivalId, MailingAudience audience, String subject, String body,
                             MailingStatus status,
                             @Nullable Long templateId, @Nullable Long planEventId, @Nullable PersonRefDto createdBy,
                             Instant createdAt,
                             @Nullable PersonRefDto sentBy, @Nullable Instant sentAt,
                             List<MailingRecipientDto> recipients, boolean canSend,
                             Long version) {
    }

    public record MailingTemplateDto(Long id, GuideVersionView.Text name, GuideVersionView.Text subject,
                                     GuideVersionView.Text body,
                                     MailingAudienceKind audience, @Nullable Long eventTypeId, RelativeDeadline due,
                                     int position, boolean retired,
                                     Long version) {

        static MailingTemplateDto of(final MailingTemplate template) {
            return new MailingTemplateDto(template.getId(), GuideVersionView.Text.plain(template.getNameSv(), template.getNameEn()),
                    GuideVersionView.Text.plain(template.getSubjectSv(), template.getSubjectEn()),
                    new GuideVersionView.Text(template.getBodySv(), template.getBodyEn(), template.getBodySv(), template.getBodyEn()),
                    template.getAudienceKind(), template.getEventTypeId(), template.getDue(), template.getPosition(), template.isRetired(),
                    template.getVersion() == null ? 0L : template.getVersion());
        }
    }

    public record MailingPlanEntry(MailingTemplateDto template, @Nullable EventDto event, LocalDate dueDate,
                                   @Nullable MailingDto mailing,
                                   boolean due) {
    }
}
