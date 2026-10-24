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

package nu.fgv.revival.server.closing;

/**
 * What one step of the sync writes to Spexregister, in the order a run executes them per participant.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum SyncOperation {

    /**
     * The revival itself ({@code spexRevivalCreate}), once, before any participant.
     */
    REVIVAL,
    /**
     * {@code activityCreate} and {@code spexActivityCreate} for the revival.
     */
    PARTICIPATION,
    /**
     * {@code taskActivityCreate} for the participant's position.
     */
    TASK,
    /**
     * {@code actorCreate} with the role and vocal part.
     */
    ACTOR,
    /**
     * {@code membershipCreate} for the year, when the revival paid it.
     */
    MEMBERSHIP
}
