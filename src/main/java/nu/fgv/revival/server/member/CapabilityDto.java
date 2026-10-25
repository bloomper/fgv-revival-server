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

import nu.fgv.revival.server.guide.GuideVersionView;

/**
 * GraphQL's {@code Capability}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record CapabilityDto(String id, GuideVersionView.Text name, int position, boolean retired, Long version) {

    public static CapabilityDto of(final Capability capability) {
        return new CapabilityDto(capability.getId(), GuideVersionView.Text.plain(capability.getNameSv(), capability.getNameEn()),
                capability.getPosition(), capability.isRetired(), capability.getVersion() == null ? 0L : capability.getVersion());
    }
}
