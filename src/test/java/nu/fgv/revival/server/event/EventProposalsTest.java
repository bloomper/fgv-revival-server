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
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dates proposed from the premiere (M4-FR-7), with the seeded timeline and a premiere on Thursday 10 February 2028.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
class EventProposalsTest {

    private static final LocalDate PREMIERE = LocalDate.of(2028, 2, 10);

    private static EventTypeDto type(final long id, final String name, final @Nullable Integer min, final @Nullable Integer max, final int days) {
        return new EventTypeDto(id, GuideVersionView.Text.plain(name, null), null, null, null, min, max, days, false, null, (int) id, false, 0L);
    }

    private static final List<EventTypeDto> TIMELINE = List.of(
            type(1, "AW", null, null, 1),
            type(2, "Kickoff", -120, -60, 1),
            type(3, "Rephelg 1", -70, -56, 2),
            type(4, "Rephelg 2", -42, -35, 2),
            type(5, "Rephelg 3", -21, -14, 2),
            type(6, "Föreställningshelg", 0, 0, 3),
            type(7, "Kortafton", 7, 90, 1));

    @Test
    void everyTypeWithOffsetsIsProposedInDateOrder() {
        final List<EventProposalDto> proposals = EventProposals.propose(PREMIERE, TIMELINE, Set.of());

        assertThat(proposals).extracting(proposal -> proposal.type().id()).containsExactly(2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(proposals).extracting(EventProposalDto::startDate).isSorted();
    }

    @Test
    void aOneDayEventStartsInTheMiddleRoundedTowardTheEarliest() {
        final EventProposalDto kickoff = EventProposals.propose(PREMIERE, TIMELINE.get(1));

        assertThat(kickoff.earliest()).isEqualTo(LocalDate.of(2027, 10, 13));
        assertThat(kickoff.latest()).isEqualTo(LocalDate.of(2027, 12, 12));
        assertThat(kickoff.startDate()).isEqualTo(LocalDate.of(2027, 11, 12));
        assertThat(kickoff.endDate()).isEqualTo(kickoff.startDate());
        assertThat(EventProposals.propose(PREMIERE, type(9, "Udda", -3, 0, 1)).startDate()).isEqualTo(PREMIERE.minusDays(2));
    }

    @Test
    void aWeekendMovesToTheNearestStartEndingOnASunday() {
        final EventProposalDto first = EventProposals.propose(PREMIERE, TIMELINE.get(2));
        final EventProposalDto second = EventProposals.propose(PREMIERE, TIMELINE.get(3));
        final EventProposalDto third = EventProposals.propose(PREMIERE, TIMELINE.get(4));

        assertThat(first.startDate()).isEqualTo(LocalDate.of(2027, 12, 11));
        assertThat(first.endDate()).isEqualTo(LocalDate.of(2027, 12, 12));
        // The middle is a Sunday itself; a weekend ending on one starts the day before
        assertThat(second.startDate()).isEqualTo(LocalDate.of(2028, 1, 1));
        assertThat(third.startDate()).isEqualTo(LocalDate.of(2028, 1, 22));
        for (final EventProposalDto proposal : List.of(first, second, third)) {
            assertThat(proposal.endDate().getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
            assertThat(proposal.startDate()).isBetween(proposal.earliest(), proposal.latest());
        }
    }

    @Test
    void aFixedOffsetIsNotMovedAndTheEndFollowsTheDays() {
        final EventProposalDto show = EventProposals.propose(PREMIERE, TIMELINE.get(5));

        assertThat(show.startDate()).isEqualTo(PREMIERE);
        assertThat(show.endDate()).isEqualTo(PREMIERE.plusDays(2));
        assertThat(show.earliest()).isEqualTo(PREMIERE);
        assertThat(show.latest()).isEqualTo(PREMIERE);
    }

    @Test
    void aRangeWithoutASundayEndKeepsTheMiddle() {
        // Monday to Wednesday before the premiere: no two-day event in it ends on a Sunday
        final EventProposalDto proposal = EventProposals.propose(PREMIERE, type(9, "Kort", -3, -1, 2));

        assertThat(proposal.startDate()).isEqualTo(PREMIERE.minusDays(2));
        assertThat(proposal.endDate()).isEqualTo(PREMIERE.minusDays(1));
    }

    @Test
    void typesWithAnEventOrRetiredOrWithoutOffsetsAreNotProposed() {
        final EventTypeDto retired = new EventTypeDto(8L, GuideVersionView.Text.plain("Gammal", null), null, null, null, -10, -5, 1, false, null, 8,
                true, 0L);
        final List<EventTypeDto> types = new ArrayList<>(TIMELINE);

        types.add(retired);
        assertThat(EventProposals.propose(PREMIERE, types, Set.of(3L, 6L))).extracting(proposal -> proposal.type().id())
                .containsExactly(2L, 4L, 5L, 7L);
    }

    @Test
    void nothingIsProposedWithoutAPremiere() {
        assertThat(EventProposals.propose(null, TIMELINE, Set.of())).isEmpty();
    }
}
