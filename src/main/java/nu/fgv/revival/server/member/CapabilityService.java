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
package nu.fgv.revival.server.member;

import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.catalog.CatalogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The areas of responsibility the super admin keeps.
 *
 * @author Anders Jacobsson
 * @since 0.4
 */
@Service
public class CapabilityService extends CatalogService<Capability> {

    private final CapabilityRepository repository;

    public CapabilityService(final CapabilityRepository repository, final CallerService callerService, final AccessPolicy accessPolicy,
                             final Clock clock) {
        super(repository, Capability.class, "capability", callerService, accessPolicy, clock);
        this.repository = repository;
    }

    @Override
    protected Set<String> used() {
        return Set.copyOf(repository.findUsedIds());
    }

    @Transactional
    public Capability create(final Names names) {
        return create(new Capability(), names);
    }

    /**
     * The capabilities with these ids, in list order, as GraphQL shows what a row holds.
     */
    @Transactional(readOnly = true)
    public List<CapabilityDto> resolve(final Collection<String> ids) {
        final Map<String, Capability> found = byId(ids);

        return ids.stream().distinct().map(found::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Capability::getPosition))
                .map(CapabilityDto::of)
                .toList();
    }
}
