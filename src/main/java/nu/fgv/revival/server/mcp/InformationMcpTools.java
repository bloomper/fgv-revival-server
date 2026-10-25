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

import nu.fgv.revival.server.audit.AuditService;
import nu.fgv.revival.server.audit.AuditedType;
import nu.fgv.revival.server.audit.RevisionDto;
import nu.fgv.revival.server.news.News;
import nu.fgv.revival.server.news.NewsDto;
import nu.fgv.revival.server.news.NewsService;
import nu.fgv.revival.server.spexcalendar.SpexCalendarDate;
import nu.fgv.revival.server.spexcalendar.SpexCalendarDateDto;
import nu.fgv.revival.server.spexcalendar.SpexCalendarService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * News, the Chalmersspex's dates and a record's history over MCP. News and dates are deleted only in the web.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class InformationMcpTools {

    private final NewsService newsService;
    private final SpexCalendarService spexCalendarService;
    private final AuditService auditService;
    private final McpToolSupport support;

    public InformationMcpTools(final NewsService newsService, final SpexCalendarService spexCalendarService, final AuditService auditService,
                               final McpToolSupport support) {
        this.newsService = newsService;
        this.spexCalendarService = spexCalendarService;
        this.auditService = auditService;
        this.support = support;
    }

    @McpTool(name = "list_news",
            description = "List the news on the start page, newest first. Super admins can ask for all of it: drafts (no first day) and withdrawn news as well.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<NewsDto> listNews(
            @McpToolParam(required = false, description = "Super admins: drafts and withdrawn news too") final @Nullable Boolean all,
            @McpToolParam(required = false, description = "At most this many") final @Nullable Integer limit) {
        return support.call("list_news", () -> new McpToolSupport.ListResult<>(newsService.list(Boolean.TRUE.equals(all), limit)));
    }

    @McpTool(name = "create_news",
            description = "Super admins: write news for the start page. It is shown from the first day up to and including the last; without a first day it is a draft, without a last day it stays.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public NewsDto createNews(
            @McpToolParam(description = "1-255 characters") final String subject,
            @McpToolParam(description = "Plain text with line breaks, 1-10,000 characters") final String text,
            @McpToolParam(required = false, description = "The first day, yyyy-MM-dd") final @Nullable String visibleFrom,
            @McpToolParam(required = false, description = "The last day, yyyy-MM-dd") final @Nullable String visibleTo) {
        return support.call("create_news", () -> newsService.create(subject, text, McpToolSupport.date(visibleFrom), McpToolSupport.date(visibleTo)));
    }

    @McpTool(name = "update_news",
            description = "Super admins: change news. Give every field; list_news with all shows the current ones. Leave out a day to clear it.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public NewsDto updateNews(
            @McpToolParam(description = "The news, by id") final Long newsId,
            @McpToolParam(description = "1-255 characters") final String subject,
            @McpToolParam(description = "Plain text with line breaks") final String text,
            @McpToolParam(required = false, description = "The first day, yyyy-MM-dd") final @Nullable String visibleFrom,
            @McpToolParam(required = false, description = "The last day, yyyy-MM-dd") final @Nullable String visibleTo) {
        return support.call("update_news", () -> {
            final NewsDto news = McpToolSupport.byId(newsService.list(true, null), NewsDto::id, newsId, News.class);

            return newsService.update(newsId, news.version(), subject, text, McpToolSupport.date(visibleFrom), McpToolSupport.date(visibleTo));
        });
    }

    @McpTool(name = "list_spex_calendar_dates",
            description = "Super admins and observers: list the days the current Chalmersspex plays, so revivals can avoid them.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<SpexCalendarDateDto> listSpexCalendarDates(
            @McpToolParam(required = false, description = "From this day, yyyy-MM-dd") final @Nullable String from,
            @McpToolParam(required = false, description = "Up to this day, yyyy-MM-dd") final @Nullable String to) {
        return support.call("list_spex_calendar_dates", () -> new McpToolSupport.ListResult<>(spexCalendarService.list(McpToolSupport.date(from),
                McpToolSupport.date(to))));
    }

    @McpTool(name = "save_spex_calendar_date",
            description = "Super admins: add a day the current Chalmersspex plays, e.g. its premiere, or with an id change one.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public SpexCalendarDateDto saveSpexCalendarDate(
            @McpToolParam(description = "yyyy-MM-dd") final String date,
            @McpToolParam(description = "What, 1-100 characters, e.g. Chalmersspexet: premiär") final String label,
            @McpToolParam(required = false, description = "A date to change, by id") final @Nullable Long id) {
        return support.call("save_spex_calendar_date", () -> {
            final java.time.LocalDate day = Objects.requireNonNull(McpToolSupport.date(date));

            if (id == null) {
                return spexCalendarService.create(day, label);
            }

            final SpexCalendarDateDto current = McpToolSupport.byId(spexCalendarService.list(null, null), SpexCalendarDateDto::id, id, SpexCalendarDate.class);

            return spexCalendarService.update(id, current.version(), day, label);
        });
    }

    @McpTool(name = "get_history",
            description = "Get a record's history, newest first: who changed it by name, when, from where (WEB, MCP or SYSTEM), why, and what each field was before and after. LOG_ENTRY, CHECKLIST_ITEM, EVENT, BUDGET_LINE, BUDGET_TRANSACTION, MATERIAL or NEWS, by id. Whoever may read the record reads its history.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<RevisionDto> getHistory(
            @McpToolParam(description = "LOG_ENTRY, CHECKLIST_ITEM, EVENT, BUDGET_LINE, BUDGET_TRANSACTION, MATERIAL or NEWS") final AuditedType type,
            @McpToolParam(description = "The record's id") final Long id) {
        return support.call("get_history", () -> new McpToolSupport.ListResult<>(auditService.revisions(type, id, Locale.ENGLISH)));
    }
}
