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

package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.mailing.MailingAudienceKind;
import nu.fgv.revival.server.mailing.MailingDtos.MailingDto;
import nu.fgv.revival.server.mailing.MailingService;
import nu.fgv.revival.server.mailing.MailingStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Drafting a mailing over MCP (M6B-FR-14). The tool only drafts: a person reviews and sends it in the web
 * (constitution IV, product spec 5.8).
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Component
public class MailingMcpTools {

    private final MailingService mailingService;
    private final McpToolSupport support;

    public MailingMcpTools(final MailingService mailingService, final McpToolSupport support) {
        this.mailingService = mailingService;
        this.support = support;
    }

    @McpTool(name = "draft_mailing",
            description = "Draft a mailing to a revival's participants, helpers or steering group, with a subject and a Markdown text. It is only a draft: someone with the communications area reviews and sends it in Nyuppsättning. The audience is PARTICIPANTS (yes or maybe), CONFIRMED (yes), STEERING_GROUP, POSITION (with positionId), EVENT_ATTENDEES (with eventId), HELPERS or NON_RESPONDERS.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public DraftedMailing draftMailing(
            @McpToolParam(description = "A revival you can work in") final Long revivalId,
            @McpToolParam(description = "1–200 characters") final String subject,
            @McpToolParam(description = "Markdown: paragraphs, lists, bold, italics and links. 1–20,000 characters") final String body,
            @McpToolParam(description = "PARTICIPANTS, CONFIRMED, STEERING_GROUP, POSITION, EVENT_ATTENDEES, HELPERS or NON_RESPONDERS") final MailingAudienceKind audience,
            @McpToolParam(required = false, description = "POSITION: the position's id from Spexregister's task list") final @Nullable String positionId,
            @McpToolParam(required = false, description = "EVENT_ATTENDEES: the event's id") final @Nullable Long eventId) {
        return support.call("draft_mailing", () -> DraftedMailing.of(mailingService.create(new MailingService.Input(revivalId,
                new MailingService.AudienceInput(audience, positionId, eventId), subject, body, null, null), Locale.ENGLISH)));
    }

    public record DraftedMailing(Long id, Long revivalId, MailingAudienceKind audience, String subject, MailingStatus status, String note) {

        static DraftedMailing of(final MailingDto mailing) {
            return new DraftedMailing(mailing.id(), mailing.revivalId(), mailing.audience().kind(), mailing.subject(), mailing.status(),
                    "Drafted, not sent. Review and send it in Nyuppsättning.");
        }
    }
}
