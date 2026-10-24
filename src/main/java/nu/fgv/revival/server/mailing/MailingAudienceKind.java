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

package nu.fgv.revival.server.mailing;

/**
 * Whom a mailing reaches, resolved when it is sent (M6B-FR-8).
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
public enum MailingAudienceKind {

    /**
     * Participants who answered yes or maybe.
     */
    PARTICIPANTS,
    /**
     * Participants who answered yes.
     */
    CONFIRMED,
    /**
     * The directeurs and the steering group.
     */
    STEERING_GROUP,
    /**
     * Participants who answered yes or maybe, with a position.
     */
    POSITION,
    /**
     * Participants and helpers available on a day of an event, or holding a shift in it.
     */
    EVENT_ATTENDEES,
    /**
     * Helpers who did not answer no.
     */
    HELPERS,
    /**
     * Invited participants and helpers who have not answered.
     */
    NON_RESPONDERS
}
