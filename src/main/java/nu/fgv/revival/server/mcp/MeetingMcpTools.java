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

import nu.fgv.revival.server.meeting.MeetingDto;
import nu.fgv.revival.server.meeting.MeetingService;
import nu.fgv.revival.server.meeting.MeetingSummaryDto;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A revival's steering group meetings over MCP. Attendees are directeurs and steering group members, given by name;
 * anyone else is free text. Sending the minutes by email stays in the web.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class MeetingMcpTools {

    private final MeetingService meetingService;
    private final McpToolSupport support;

    public MeetingMcpTools(final MeetingService meetingService, final McpToolSupport support) {
        this.meetingService = meetingService;
        this.support = support;
    }

    @McpTool(name = "list_meetings",
            description = "List a revival's steering group meetings, newest first: date, title and status (PLANNED, IN_PROGRESS, CLOSED or CANCELLED).",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<MeetingSummaryDto> listMeetings(@McpToolParam(description = "A revival you can see") final Long revivalId) {
        return support.call("list_meetings", () -> new McpToolSupport.ListResult<>(meetingService.list(revivalId)));
    }

    @McpTool(name = "get_meeting",
            description = "Get a meeting: its date, title, status, attendees, others present, agenda and, once closed, its minutes.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public MeetingDto getMeeting(@McpToolParam(description = "The meeting, by id from list_meetings") final Long meetingId) {
        return support.call("get_meeting", () -> meetingService.get(meetingId, Locale.ENGLISH));
    }

    @McpTool(name = "create_meeting",
            description = "Plan a steering group meeting: a date, an optional title, who attends, who else is there and the agenda.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public MeetingDto createMeeting(
            @McpToolParam(description = "A revival you can work in") final Long revivalId,
            @McpToolParam(description = "yyyy-MM-dd") final String date,
            @McpToolParam(required = false, description = "A title") final @Nullable String title,
            @McpToolParam(required = false, description = "Directeurs and steering group members attending, by display name or me") final @Nullable List<String> attendees,
            @McpToolParam(required = false, description = "Anyone else there, as free text") final @Nullable String othersPresent,
            @McpToolParam(required = false, description = "The agenda points in order") final @Nullable List<String> agenda) {
        return support.call("create_meeting", () -> meetingService.create(new MeetingService.Plan(revivalId, Objects.requireNonNull(McpToolSupport.date(date)),
                title, support.members(revivalId, attendees), othersPresent, agenda), Locale.ENGLISH));
    }

    @McpTool(name = "update_meeting",
            description = "Change a meeting's date, title, attendees, others present or agenda. Leave out what stays as it is; attendees and agenda replace the current ones.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public MeetingDto updateMeeting(
            @McpToolParam(description = "The meeting, by id") final Long meetingId,
            @McpToolParam(required = false, description = "yyyy-MM-dd") final @Nullable String date,
            @McpToolParam(required = false, description = "A title") final @Nullable String title,
            @McpToolParam(required = false, description = "Directeurs and steering group members attending, by display name or me") final @Nullable List<String> attendees,
            @McpToolParam(required = false, description = "Anyone else there, as free text") final @Nullable String othersPresent,
            @McpToolParam(required = false, description = "The agenda points in order") final @Nullable List<String> agenda) {
        return support.call("update_meeting", () -> {
            final MeetingDto meeting = meetingService.get(meetingId, Locale.ENGLISH);

            return meetingService.update(new MeetingService.Change(meetingId, meeting.version(), McpToolSupport.date(date), title,
                    support.members(meeting.revivalId(), attendees), othersPresent, agenda), Locale.ENGLISH);
        });
    }

    @McpTool(name = "change_meeting_state",
            description = "Start a planned meeting (rows created or changed while it is in progress are recorded in its minutes), close one in progress, or cancel a planned one. Meetings are never deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public MeetingDto changeMeetingState(
            @McpToolParam(description = "The meeting, by id") final Long meetingId,
            @McpToolParam(description = "START, CLOSE or CANCEL") final Action action) {
        return support.call("change_meeting_state", () -> {
            final MeetingDto meeting = meetingService.get(meetingId, Locale.ENGLISH);

            return switch (action) {
                case START -> meetingService.start(meetingId, meeting.version(), Locale.ENGLISH);
                case CLOSE -> meetingService.close(meetingId, meeting.version(), Locale.ENGLISH);
                case CANCEL -> meetingService.cancel(meetingId, meeting.version(), Locale.ENGLISH);
            };
        });
    }

    public enum Action {
        START, CLOSE, CANCEL
    }
}
