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

package nu.fgv.revival.server.participant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Dietary needs are deleted outright: when cleared, and the day after the last show (product spec section 7).
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
public interface DietaryNeedRepository extends JpaRepository<DietaryNeed, Long> {

    List<DietaryNeed> findByParticipantIdIn(Collection<Long> participantIds);

    /**
     * Deletes the dietary needs of every revival whose last show was before the given day.
     */
    @Modifying
    @Query(value = """
            DELETE d FROM dietary_need d
            JOIN participant p ON p.id = d.participant_id
            WHERE p.revival_id IN (SELECT s.revival_id FROM revival_show s GROUP BY s.revival_id HAVING MAX(s.show_date) < :today)
            """, nativeQuery = true)
    int deleteAfterLastShow(@Param("today") LocalDate today);
}
