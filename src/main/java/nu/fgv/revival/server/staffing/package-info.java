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

/**
 * Availability per participant and event day, and staffing with shift slots (product spec 5.7, M6b): participants say
 * which event days they can come and sign up for slots; directeurs and steering group members with {@code STAFFING}
 * plan slots and assign people. A helper's paid shifts are a budgeted cost of the budget's helper category.
 */
@NullMarked
package nu.fgv.revival.server.staffing;

import org.jspecify.annotations.NullMarked;
