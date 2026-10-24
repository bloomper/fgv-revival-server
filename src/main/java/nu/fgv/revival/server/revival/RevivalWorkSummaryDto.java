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


package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.meeting.MeetingSummaryDto;
import org.jspecify.annotations.Nullable;

/**
 * GraphQL's {@code RevivalWorkSummary}: the counts shown on the revival page next to the log and checklists. The revival's id
 * resolves its upcoming events.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public record RevivalWorkSummaryDto(Long revivalId, int openEntries, int overdueEntries, int overdueChecklistItems, int openRequiredItems,
                                    @Nullable MeetingSummaryDto meetingInProgress) {
}
