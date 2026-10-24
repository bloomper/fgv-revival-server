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

/**
 * GraphQL's {@code GuideItemType}.
 *
 * @author Anders Jacobsson
 * @since 0.4
 */
public record GuideItemTypeDto(String id, GuideItemKind kind, GuideVersionView.Text name, int position, boolean retired,
                               Long version) {

    public static GuideItemTypeDto of(final GuideItemType type) {
        return new GuideItemTypeDto(type.getId(), type.getKind(), GuideVersionView.Text.plain(type.getNameSv(), type.getNameEn()),
                type.getPosition(), type.isRetired(), type.getVersion() == null ? 0L : type.getVersion());
    }
}
