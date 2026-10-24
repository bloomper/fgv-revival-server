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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Some of the link services the database starts with (migration V8_0), for tests without one.
 *
 * @author Anders Jacobsson
 */
final class TestLinkServices {

    private TestLinkServices() {
    }

    static List<LinkService> seeded() {
        final List<LinkService> services = new ArrayList<>();

        services.add(service("SPEXFLIX", "Spexflix", 0));
        services.add(service("LATARKIV", "Låtarkiv", 1));
        services.add(service("GOOGLE_DRIVE", "Google Drive", 2, "drive.google.com", "docs.google.com"));
        services.add(service("YOUTUBE", "YouTube", 3, "youtube.com", "youtu.be"));
        services.add(service("ONEDRIVE", "OneDrive", 4, "onedrive.live.com", "1drv.ms", "sharepoint.com"));
        return services;
    }

    static LinkService service(final String id, final String name, final int position, final String... hosts) {
        final LinkService service = new LinkService();

        service.setId(id);
        service.setNameSv(name);
        service.setPosition(position);
        service.setHosts(new HashSet<>(Set.of(hosts)));
        service.setVersion(0L);
        return service;
    }
}
