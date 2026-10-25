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


package nu.fgv.revival.server.decisionlog;

import nu.fgv.revival.server.revival.RevivalCreated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Row numbers per revival, consecutive and never reused.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class LogEntryCounters {

    private final LogEntryCounterRepository repository;

    public LogEntryCounters(final LogEntryCounterRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void revivalCreated(final RevivalCreated event) {
        ensure(event.revivalId());
    }

    /**
     * Creates the revival's counter if it has none, e.g. for revivals made by the sample data import.
     */
    public void ensure(final Long revivalId) {
        if (repository.findForUpdate(revivalId).isEmpty()) {
            repository.save(new LogEntryCounter(revivalId));
        }
    }

    /**
     * The counter, locked until the transaction ends.
     */
    LogEntryCounter locked(final Long revivalId) {
        return repository.findForUpdate(revivalId).orElseGet(() -> repository.save(new LogEntryCounter(revivalId)));
    }

    /**
     * The next number, under the counter's lock.
     */
    int next(final Long revivalId) {
        return locked(revivalId).next();
    }
}
