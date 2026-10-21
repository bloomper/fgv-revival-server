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

import nu.fgv.revival.server.revival.RevivalStatus;

import java.util.List;

/**
 * What the caller may do with a revival, computed by {@link AccessPolicy} so the web never computes it (research R13).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
public record RevivalPermissionsDto(boolean canEditCore,
                                    boolean canEditDetails,
                                    List<RevivalStatus> allowedStatuses,
                                    boolean canManageDirecteurs,
                                    boolean canManageSteeringGroup) {
}
