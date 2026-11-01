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


package nu.fgv.revival.server.meeting;

import nu.fgv.revival.server.decisionlog.LogEntryDto;
import nu.fgv.revival.server.decisionlog.LogEntryFilterDto;
import nu.fgv.revival.server.decisionlog.LogEntryService;
import nu.fgv.revival.server.decisionlog.LogEntrySummaryDto;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.util.jpa.ExternalIds;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Meetings over GraphQL.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class MeetingGraphqlApi {

    private final ExternalIds ids;

    private final MeetingService service;
    private final MinutesEmailService emailService;
    private final LogEntryService logEntryService;

    public MeetingGraphqlApi(final MeetingService service, final MinutesEmailService emailService, final LogEntryService logEntryService, final ExternalIds ids) {
        this.ids = ids;
        this.service = service;
        this.emailService = emailService;
        this.logEntryService = logEntryService;
    }

    @QueryMapping
    public List<MeetingSummaryDto> meetings(@Argument final UUID revivalId) {
        return service.list(ids.idOf(Revival.class, revivalId));
    }

    @QueryMapping
    public MeetingDto meeting(@Argument final UUID id, final Locale locale) {
        return service.get(ids.idOf(Meeting.class, id), locale);
    }

    @MutationMapping
    public MeetingDto meetingCreate(@Argument final MeetingCreateInput input, final Locale locale) {
        return service.create(new MeetingService.Plan(ids.idOf(Revival.class, input.revivalId()), input.date(), input.title(), ids.idsOfNullable(Person.class, input.attendeeIds()),
                input.othersPresent(), input.agendaPoints()), locale);
    }

    @MutationMapping
    public MeetingDto meetingUpdate(@Argument final MeetingUpdateInput input, final Locale locale) {
        return service.update(new MeetingService.Change(ids.idOf(Meeting.class, input.id()), input.version(), input.date(), input.title(), ids.idsOfNullable(Person.class, input.attendeeIds()),
                input.othersPresent(), input.agendaPoints()), locale);
    }

    @MutationMapping
    public MeetingDto meetingStart(@Argument final UUID id, @Argument final Long version, final Locale locale) {
        return service.start(ids.idOf(Meeting.class, id), version, locale);
    }

    @MutationMapping
    public MeetingDto meetingClose(@Argument final UUID id, @Argument final Long version, final Locale locale) {
        return service.close(ids.idOf(Meeting.class, id), version, locale);
    }

    @MutationMapping
    public MeetingDto meetingCancel(@Argument final UUID id, @Argument final Long version, final Locale locale) {
        return service.cancel(ids.idOf(Meeting.class, id), version, locale);
    }

    @MutationMapping
    public MinutesEmailDto minutesEmailSend(@Argument final MinutesEmailSendInput input, final Locale locale) {
        return emailService.send(ids.idOf(Meeting.class, input.meetingId()), input.language(), locale);
    }

    @MutationMapping
    public MinutesEmailDto minutesEmailRetry(@Argument final UUID emailId, final Locale locale) {
        return emailService.retry(ids.idOf(MinutesEmail.class, emailId), locale);
    }

    @SchemaMapping(typeName = "Meeting", field = "openEntries")
    public List<LogEntryDto> openEntries(final MeetingDto meeting, final Locale locale) {
        if (meeting.status() != MeetingStatus.PLANNED && meeting.status() != MeetingStatus.IN_PROGRESS) {
            return List.of();
        }
        return logEntryService.list(ids.idOf(Revival.class, meeting.revivalId()), new LogEntryFilterDto(null, true, null, null, null, null, null), locale);
    }

    @SchemaMapping(typeName = "Meeting", field = "entries")
    public List<LogEntryDto> entries(final MeetingDto meeting, final Locale locale) {
        return logEntryService.list(ids.idOf(Revival.class, meeting.revivalId()), new LogEntryFilterDto(null, null, null, null, meeting.id(), null, null), locale);
    }

    @SchemaMapping(typeName = "Meeting", field = "emails")
    public List<MinutesEmailDto> emails(final MeetingDto meeting, final Locale locale) {
        return emailService.list(ids.idOf(Meeting.class, meeting.id()), locale);
    }

    @SchemaMapping(typeName = "Meeting", field = "canEmailMinutes")
    public boolean canEmailMinutes(final MeetingDto meeting) {
        return emailService.canEmail(ids.idOf(Revival.class, meeting.revivalId()), meeting.status());
    }

    @SchemaMapping(typeName = "Meeting", field = "blockingEntries")
    public List<LogEntrySummaryDto> blockingEntries(final MeetingDto meeting) {
        return service.blocking(ids.idOf(Meeting.class, meeting.id()));
    }

    public record MeetingCreateInput(UUID revivalId, LocalDate date, @Nullable String title,
                                     @Nullable List<UUID> attendeeIds,
                                     @Nullable String othersPresent, @Nullable List<String> agendaPoints) {
    }

    public record MeetingUpdateInput(UUID id, Long version, @Nullable LocalDate date, @Nullable String title,
                                     @Nullable List<UUID> attendeeIds,
                                     @Nullable String othersPresent, @Nullable List<String> agendaPoints) {
    }

    public record MinutesEmailSendInput(UUID meetingId, String language) {
    }
}
