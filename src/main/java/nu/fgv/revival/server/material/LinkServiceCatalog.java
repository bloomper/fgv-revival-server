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
package nu.fgv.revival.server.material;

import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.catalog.CatalogService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The link services the super admin keeps, with their hosts and home pages. A host belongs to at most one
 * active service, so a link is recognised unambiguously. A service a material holds is retired, not deleted.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class LinkServiceCatalog extends CatalogService<LinkService> {

    private final LinkServiceRepository repository;

    public LinkServiceCatalog(final LinkServiceRepository repository, final CallerService callerService, final AccessPolicy accessPolicy,
                              final Clock clock) {
        super(repository, LinkService.class, "linkService", callerService, accessPolicy, clock);
        this.repository = repository;
    }

    private static Set<String> validHosts(final @Nullable List<String> hosts) {
        final Set<String> valid = new LinkedHashSet<>();

        if (hosts != null) {
            hosts.forEach(host -> valid.add(Links.validHost(host)));
        }
        if (valid.size() > LinkService.MAX_HOSTS) {
            throw new BadRequestException("error.linkService.hosts.tooMany", LinkService.MAX_HOSTS);
        }
        return valid;
    }

    private static @Nullable String validHomeUrl(final @Nullable String homeUrl) {
        return homeUrl == null || homeUrl.isBlank() ? null : Links.requireHttps(homeUrl, LinkService.HOME_URL_MAX_LENGTH);
    }

    @Override
    protected Set<String> used() {
        return Set.copyOf(repository.findUsedIds());
    }

    @Transactional
    public LinkService create(final Names names, final @Nullable List<String> hosts, final @Nullable String homeUrl) {
        manageable();

        final Set<String> valid = validHosts(hosts);

        requireHostsFree(null, valid);

        final LinkService service = new LinkService();

        service.setHosts(new HashSet<>(valid));
        service.setHomeUrl(validHomeUrl(homeUrl));
        return create(service, names);
    }

    /**
     * Renames the service and replaces its hosts and home page.
     */
    @Transactional
    public LinkService update(final String id, final Long version, final Names names, final @Nullable List<String> hosts,
                              final @Nullable String homeUrl) {
        manageable();

        final LinkService service = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(LinkService.class, id));
        final Set<String> valid = validHosts(hosts);

        if (!service.isRetired()) {
            requireHostsFree(id, valid);
        }
        // The same managed entity that rename checks and saves, so the version moves on once
        service.getHosts().retainAll(valid);
        service.getHosts().addAll(valid);
        service.setHomeUrl(validHomeUrl(homeUrl));
        return rename(id, version, names);
    }

    /**
     * Refused while another active service holds one of its hosts.
     */
    @Override
    @Transactional
    public LinkService restore(final String id, final Long version) {
        manageable();

        final LinkService service = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(LinkService.class, id));

        requireHostsFree(id, service.getHosts());
        return super.restore(id, version);
    }

    /**
     * Every service, retired ones too, in list order. No caller check: for recognising links and showing what materials
     * hold.
     */
    @Transactional(readOnly = true)
    public List<LinkService> all() {
        return repository.findAllByOrderByPositionAscIdAsc();
    }

    /**
     * The active service a link's host belongs to, if any.
     */
    @Transactional(readOnly = true)
    public Optional<LinkService> recognise(final String host) {
        return Links.recognise(host, all());
    }

    private void requireHostsFree(final @Nullable String id, final Collection<String> hosts) {
        for (final LinkService other : repository.findAllByOrderByPositionAscIdAsc()) {
            if (other.getId().equals(id) || other.isRetired()) {
                continue;
            }
            for (final String host : hosts) {
                if (other.getHosts().contains(host)) {
                    throw new BadRequestException("error.linkService.host.taken", host, other.getNameSv());
                }
            }
        }
    }
}
