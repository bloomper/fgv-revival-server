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

import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * GraphQL's {@code LinkService}. Hosts in alphabetical order.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record LinkServiceDto(String id, GuideVersionView.Text name, List<String> hosts, @Nullable String homeUrl,
                             int position, boolean retired,
                             Long version) {

    public static LinkServiceDto of(final LinkService service) {
        return new LinkServiceDto(service.getId(), GuideVersionView.Text.plain(service.getNameSv(), service.getNameEn()),
                service.getHosts().stream().sorted().toList(), service.getHomeUrl(), service.getPosition(), service.isRetired(),
                service.getVersion() == null ? 0L : service.getVersion());
    }
}
