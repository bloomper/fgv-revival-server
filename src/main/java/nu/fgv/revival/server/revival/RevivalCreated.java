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

/**
 * A revival was created. Published in the creating transaction, before it is pinned to a guide version, so listeners
 * can set up its log counter and phase checklists (M3 research R10).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public record RevivalCreated(Long revivalId) {
}
