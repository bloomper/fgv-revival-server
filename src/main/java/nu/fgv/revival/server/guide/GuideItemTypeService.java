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
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.catalog.CatalogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;

/**
 * The guide item types the super admin keeps.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class GuideItemTypeService extends CatalogService<GuideItemType> {

    private final GuideItemTypeRepository repository;

    public GuideItemTypeService(final GuideItemTypeRepository repository, final CallerService callerService, final AccessPolicy accessPolicy,
                                final Clock clock) {
        super(repository, GuideItemType.class, "guideItemType", callerService, accessPolicy, clock);
        this.repository = repository;
    }

    @Override
    protected Set<String> used() {
        return Set.copyOf(repository.findUsedIds());
    }

    @Transactional
    public GuideItemType create(final GuideItemKind kind, final Names names) {
        final GuideItemType type = new GuideItemType();

        type.setKind(kind);
        return create(type, names);
    }
}
