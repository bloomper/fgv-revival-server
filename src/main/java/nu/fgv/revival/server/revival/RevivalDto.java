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

package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.member.RevivalPermissionsDto;
import nu.fgv.revival.server.spex.SpexDto;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A revival as GraphQL's {@code Revival}. {@code createdBy} and {@code lastModifiedBy} hold Keycloak subjects; the
 * GraphQL layer resolves them to display names.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
public record RevivalDto(Long id,
                         SpexDto spex,
                         Integer year,
                         @Nullable LocalDate premiereDate,
                         List<RevivalShowDto> shows,
                         RevivalStatus status,
                         @Nullable Long guideVersionId,
                         Long version,
                         String createdBy,
                         Instant createdAt,
                         @Nullable String lastModifiedBy,
                         @Nullable Instant lastModifiedAt,
                         @Nullable Instant anonymizedAt,
                         RevivalPermissionsDto permissions) {
}
