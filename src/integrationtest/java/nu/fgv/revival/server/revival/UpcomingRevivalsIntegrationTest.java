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

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The revivals coming up, as everyone with access reads them on the start page.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class UpcomingRevivalsIntegrationTest extends AbstractIntegrationTest {

    private static final String UPCOMING = "{ upcomingRevivals { id title year premiereDate shows { date time venue } } }";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Stockholm"));

    private void premiere(final long revival, final String status, final LocalDate premiere) {
        jdbcClient.sql("UPDATE revival SET status = ?, premiere_date = ? WHERE id = ?").params(status, premiere, revival).update();
    }

    @Test
    void everyoneSeesTheAnnouncedRevivalsComingUpSoonestFirst() {
        final long later = createRevival(ALI_BABA, 2029);
        final long sooner = createRevival(NOBEL, 2028);
        final long playing = createRevival(SNORRE, 2027);
        final long proposed = createRevival(ALI_BABA, 2030);
        final long over = createRevival(NOBEL, 2026);
        final long noPremiere = createRevival(SNORRE, 2031);

        premiere(later, "ORGANISING", TODAY.plusDays(200));
        premiere(sooner, "OPEN", TODAY.plusDays(30));
        // Its premiere has passed, but it still plays
        premiere(playing, "OPEN", TODAY.minusDays(2));
        jdbcClient.sql("INSERT INTO revival_show (revival_id, show_date, venue) VALUES (?, ?, 'RunAn')").params(playing, TODAY.plusDays(1)).update();
        premiere(proposed, "PROPOSED", TODAY.plusDays(100));
        premiere(over, "OPEN", TODAY.minusDays(10));
        jdbcClient.sql("UPDATE revival SET status = 'ORGANISING' WHERE id = ?").param(noPremiere).update();

        for (final String user : new String[]{PLAIN_USER, PARTICIPANT, STEERING_GROUP, OBSERVER}) {
            execute(user, UPCOMING, Map.of())
                    .path("upcomingRevivals[*].id").entityList(Long.class).containsExactly(playing, sooner, later)
                    .path("upcomingRevivals[0].shows[0].venue").entity(String.class).isEqualTo("RunAn")
                    .path("upcomingRevivals[1].year").entity(Integer.class).isEqualTo(2028)
                    .path("upcomingRevivals[1].title").entity(String.class).satisfies(title -> assertThat(title).endsWith("2028"));
        }
    }
}
