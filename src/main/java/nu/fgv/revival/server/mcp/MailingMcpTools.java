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
import nu.fgv.revival.server.checklist.RelativeDeadline;
import nu.fgv.revival.server.checklist.RelativeDeadlineKind;
import nu.fgv.revival.server.mailing.MailingDtos.MailingDto;
import nu.fgv.revival.server.mailing.MailingDtos.MailingTemplateDto;
import nu.fgv.revival.server.mailing.MailingTemplate;
import nu.fgv.revival.server.mailing.MailingTemplateService;
import nu.fgv.revival.server.mailing.MailingService;
import nu.fgv.revival.server.mailing.MailingStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Drafting a mailing over MCP. The tool only drafts: a person reviews and sends it in the web.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class MailingMcpTools {

    private final MailingService mailingService;
    private final MailingTemplateService templateService;
    private final McpToolSupport support;

    public MailingMcpTools(final MailingService mailingService, final MailingTemplateService templateService, final McpToolSupport support) {
        this.mailingService = mailingService;
        this.templateService = templateService;
        this.support = support;
    }

    private MailingTemplateDto template(final Long templateId) {
        return McpToolSupport.byId(templateService.list(true), MailingTemplateDto::id, templateId, MailingTemplate.class);
    }

    @McpTool(name = "list_mailings",
            description = "List a revival's mailings, drafts and sent ones: audience, subject, status, how many it went to and when it was sent. Never the recipients.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<Mailing> listMailings(@McpToolParam(description = "A revival whose mailings you can read") final Long revivalId) {
        return support.call("list_mailings", () -> new McpToolSupport.ListResult<>(mailingService.list(revivalId, Locale.ENGLISH).stream()
                .map(Mailing::of).toList()));
    }

    @McpTool(name = "get_mailing",
            description = "Get one mailing with its Markdown text. Never the recipients.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Mailing getMailing(@McpToolParam(description = "The mailing, by id from list_mailings") final Long mailingId) {
        return support.call("get_mailing", () -> Mailing.of(mailingService.get(mailingId, Locale.ENGLISH)));
    }

    @McpTool(name = "update_mailing_draft",
            description = "Change a draft mailing's audience, subject or text. Give every field; get_mailing shows the current ones. It stays a draft: it is sent in Nyuppsättning.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Mailing updateMailingDraft(
            @McpToolParam(description = "The draft, by id") final Long mailingId,
            @McpToolParam(description = "1–200 characters") final String subject,
            @McpToolParam(description = "Markdown, as in draft_mailing") final String body,
            @McpToolParam(description = "As in draft_mailing") final MailingAudienceKind audience,
            @McpToolParam(required = false, description = "POSITION: the position's id") final @Nullable String positionId,
            @McpToolParam(required = false, description = "EVENT_ATTENDEES: the event's id") final @Nullable Long eventId) {
        return support.call("update_mailing_draft", () -> {
            final MailingDto mailing = mailingService.get(mailingId, Locale.ENGLISH);

            return Mailing.of(mailingService.update(mailingId, mailing.version(), new MailingService.Input(mailing.revivalId(),
                    new MailingService.AudienceInput(audience, positionId, eventId), subject, body, mailing.templateId(), mailing.planEventId()), Locale.ENGLISH));
        });
    }

    @McpTool(name = "cancel_mailing",
            description = "Cancel a draft mailing that will not be sent. It stays in the list; mailings are never deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Mailing cancelMailing(@McpToolParam(description = "The draft, by id") final Long mailingId) {
        return support.call("cancel_mailing", () -> Mailing.of(mailingService.cancel(mailingId, mailingService.get(mailingId, Locale.ENGLISH).version(),
                Locale.ENGLISH)));
    }

    @McpTool(name = "get_mailing_plan",
            description = "Get a revival's mailing plan: each template the plan proposes, the event it follows, when it is due, and the mailing drafted from it, if any.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<PlanEntry> getMailingPlan(@McpToolParam(description = "A revival whose mailings you can read") final Long revivalId) {
        return support.call("get_mailing_plan", () -> new McpToolSupport.ListResult<>(mailingService.plan(revivalId, Locale.ENGLISH).stream()
                .map(entry -> new PlanEntry(entry.template().id(), ChecklistMcpTools.Text.of(entry.template().name()),
                        entry.event() == null ? null : entry.event().id(), entry.event() == null ? null : entry.event().title(), entry.dueDate(), entry.due(),
                        entry.mailing() == null ? null : Mailing.of(entry.mailing())))
                .toList()));
    }

    @McpTool(name = "list_mailing_templates",
            description = "List the mailing plan's templates: name, subject and text in Swedish and English, audience, the event type it follows and when it is due.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<MailingTemplateDto> listMailingTemplates(
            @McpToolParam(required = false, description = "Also retired ones") final @Nullable Boolean includeRetired) {
        return support.call("list_mailing_templates", () -> new McpToolSupport.ListResult<>(templateService.list(Boolean.TRUE.equals(includeRetired))));
    }

    @McpTool(name = "create_mailing_template",
            description = "Super admins: add a template to the mailing plan. It is due relative to an event of its type, or to the premiere without one.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public MailingTemplateDto createMailingTemplate(@McpToolParam(description = "The template") final TemplateInput template) {
        return support.call("create_mailing_template", () -> templateService.create(template.definition()));
    }

    @McpTool(name = "update_mailing_template",
            description = "Super admins: replace a mailing template, and optionally move it to a position in the plan (counting from 0).",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public MailingTemplateDto updateMailingTemplate(
            @McpToolParam(description = "The template, by id") final Long templateId,
            @McpToolParam(required = false, description = "The whole template, as in create_mailing_template") final @Nullable TemplateInput template,
            @McpToolParam(required = false, description = "Its place in the plan, counting from 0") final @Nullable Integer position) {
        return support.call("update_mailing_template", () -> {
            MailingTemplateDto current = template(templateId);

            if (template != null) {
                current = templateService.update(templateId, current.version(), template.definition());
            }
            if (position != null) {
                final Long version = current.version();

                current = McpToolSupport.byId(templateService.move(templateId, version, position), MailingTemplateDto::id, templateId, MailingTemplate.class);
            }
            return current;
        });
    }

    @McpTool(name = "set_mailing_template_retired",
            description = "Super admins: retire a mailing template so the plan no longer proposes it, or restore it.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public MailingTemplateDto setMailingTemplateRetired(
            @McpToolParam(description = "The template, by id") final Long templateId,
            @McpToolParam(description = "true to retire, false to restore") final Boolean retired) {
        return support.call("set_mailing_template_retired", () -> {
            final Long version = template(templateId).version();

            return retired ? templateService.retire(templateId, version) : templateService.restore(templateId, version);
        });
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

    /**
     * One entry of the mailing plan.
     */
    public record PlanEntry(Long templateId, ChecklistMcpTools.Text template, @Nullable Long eventId, @Nullable String event,
                            java.time.LocalDate dueDate, boolean due, @Nullable Mailing mailing) {
    }

    /**
     * A mailing without its recipients: how many it went to, only.
     */
    public record Mailing(Long id, Long revivalId, MailingAudienceKind audience, @Nullable String positionId, @Nullable Long eventId, String subject,
                          String body, MailingStatus status, int recipients, @Nullable Instant sentAt, @Nullable Long templateId) {

        static Mailing of(final MailingDto mailing) {
            return new Mailing(mailing.id(), mailing.revivalId(), mailing.audience().kind(),
                    mailing.audience().position() == null ? null : mailing.audience().position().id(),
                    mailing.audience().event() == null ? null : mailing.audience().event().id(), mailing.subject(), mailing.body(), mailing.status(),
                    mailing.recipients().size(), mailing.sentAt(), mailing.templateId());
        }
    }

    /**
     * When a template is due: a number of DAYS or WORKING_DAYS from the event or premiere (negative before it), or a
     * weekday the week before.
     */
    public record Due(RelativeDeadlineKind kind, @Nullable Integer amount, @Nullable DayOfWeek weekday) {
    }

    /**
     * A mailing template as the tools take it; Swedish is required, English optional.
     */
    public record TemplateInput(String nameSv, @Nullable String nameEn, String subjectSv, @Nullable String subjectEn, String bodySv,
                                @Nullable String bodyEn, MailingAudienceKind audience, @Nullable Long eventTypeId, Due due) {

        MailingTemplateService.Definition definition() {
            return new MailingTemplateService.Definition(new MailingTemplateService.Text(nameSv, nameEn), new MailingTemplateService.Text(subjectSv, subjectEn),
                    new MailingTemplateService.Text(bodySv, bodyEn), audience, eventTypeId, RelativeDeadline.of(due.kind(), due.amount(), due.weekday()));
        }
    }

    public record DraftedMailing(Long id, Long revivalId, MailingAudienceKind audience, String subject,
                                 MailingStatus status, String note) {

        static DraftedMailing of(final MailingDto mailing) {
            return new DraftedMailing(mailing.id(), mailing.revivalId(), mailing.audience().kind(), mailing.subject(), mailing.status(),
                    "Drafted, not sent. Review and send it in Nyuppsättning.");
        }
    }
}
