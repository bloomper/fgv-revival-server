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
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import org.springframework.stereotype.Component;

/**
 * The checks every budget operation starts with: the revival is visible, and the caller reads or writes its budget.
 * A revival the caller cannot see is reported as missing.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
class BudgetAccess {

    private final RevivalRepository revivalRepository;
    private final AccessPolicy accessPolicy;

    BudgetAccess(final RevivalRepository revivalRepository, final AccessPolicy accessPolicy) {
        this.revivalRepository = revivalRepository;
        this.accessPolicy = accessPolicy;
    }

    Revival readable(final Caller caller, final Long revivalId) {
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireReadBudget(caller, revival);
        return revival;
    }

    Revival writable(final Caller caller, final Long revivalId) {
        final Revival revival = readable(caller, revivalId);

        accessPolicy.requireWriteBudget(caller, revival);
        return revival;
    }

    boolean canRead(final Caller caller, final Long revivalId) {
        return revivalRepository.findById(revivalId)
                .filter(revival -> accessPolicy.canSee(caller, revival) && accessPolicy.canReadBudget(caller, revival))
                .isPresent();
    }
}
