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

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Declares only what event types need, so it has no delete method.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface EventTypeRepository extends Repository<EventType, Long> {

    EventType save(EventType type);

    EventType saveAndFlush(EventType type);

    void flush();

    Optional<EventType> findById(Long id);

    List<EventType> findAllById(Iterable<Long> ids);

    List<EventType> findAllByOrderByPositionAscIdAsc();

    Optional<EventType> findByNameSv(String nameSv);
}
