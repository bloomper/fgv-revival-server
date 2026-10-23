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

import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * {@code firstName} and {@code lastName} are used only when a Keycloak user is created for the address.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
public record RevivalMemberAddDto(Long revivalId,
                                  String email,
                                  RevivalRole role,
                                  @Nullable Set<String> capabilities,
                                  @Nullable String firstName,
                                  @Nullable String lastName) {
}
