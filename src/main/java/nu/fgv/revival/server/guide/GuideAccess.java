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

package nu.fgv.revival.server.guide;

import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads the caller's revivals for {@link AccessPolicy}'s guide rules, which keeps the policy free of repositories
 * (research R10).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Component
public class GuideAccess {

    private final AccessPolicy accessPolicy;
    private final RevivalRepository revivalRepository;

    public GuideAccess(final AccessPolicy accessPolicy, final RevivalRepository revivalRepository) {
        this.accessPolicy = accessPolicy;
        this.revivalRepository = revivalRepository;
    }

    private List<Revival> revivals(final Caller caller) {
        if (caller.isSuperAdmin() || caller.isObserver() || caller.revivalRoles().isEmpty()) {
            return List.of();
        }
        return revivalRepository.findAll(accessPolicy.visibilityPredicate(caller));
    }

    public boolean canRead(final Caller caller) {
        return accessPolicy.canReadGuide(caller, revivals(caller));
    }

    public void requireRead(final Caller caller) {
        accessPolicy.requireReadGuide(caller, revivals(caller));
    }

    /**
     * The draft only for super admins, published versions for everyone who reads the guide. Anything else is
     * indistinguishable from a version that does not exist.
     */
    public void requireReadable(final Caller caller, final GuideVersionView version) {
        final boolean readable = version.draft() ? accessPolicy.canManageGuide(caller) : canRead(caller);

        if (!readable) {
            throw new ResourceNotFoundException(GuideVersion.class, version.id());
        }
    }
}
