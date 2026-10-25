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

import nu.fgv.revival.server.util.error.BadRequestException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Records what is done to log rows in a meeting in progress. The decision log calls it, so it
 * depends on meetings only through this.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class MeetingRecorder {

    private final MeetingRepository meetingRepository;
    private final MeetingEntryChangeRepository changeRepository;
    private final Clock clock;

    public MeetingRecorder(final MeetingRepository meetingRepository, final MeetingEntryChangeRepository changeRepository, final Clock clock) {
        this.meetingRepository = meetingRepository;
        this.changeRepository = changeRepository;
        this.clock = clock;
    }

    /**
     * The meeting a row change is made in: in progress, in the row's revival.
     */
    public Meeting requireInProgress(final Long meetingId, final Long revivalId) {
        final Meeting meeting = meetingRepository.findById(meetingId)
                .filter(found -> found.getRevivalId().equals(revivalId))
                .orElseThrow(() -> new BadRequestException("error.meeting.otherRevival"));

        if (meeting.getStatus() != MeetingStatus.IN_PROGRESS) {
            throw new BadRequestException("error.meeting.notInProgress");
        }
        return meeting;
    }

    /**
     * Summaries of meetings rows refer to. Callers have checked access to the rows' revival.
     */
    public Map<Long, MeetingSummaryDto> summaries(final Collection<Long> meetingIds) {
        return meetingRepository.findAllById(meetingIds).stream()
                .collect(Collectors.toMap(Meeting::getId, MeetingSummaryDto::of));
    }

    public void record(final Long meetingId, final Long entryId, final Collection<MeetingEntryChangeKind> kinds, final Long personId) {
        final Instant now = clock.instant();

        kinds.forEach(kind -> changeRepository.save(new MeetingEntryChange(meetingId, entryId, kind, personId, now)));
    }
}
