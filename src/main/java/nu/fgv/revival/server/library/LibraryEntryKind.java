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
package nu.fgv.revival.server.library;

/**
 * What a reference library entry describes.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum LibraryEntryKind {
    /**
     * A venue: pros, cons, how to book it and what earlier revivals paid.
     */
    VENUE,
    /**
     * A ticket service: pros, cons and prices.
     */
    TICKET_SERVICE,
    /**
     * What a role in a revival does, e.g. directeur, casseur or production manager.
     */
    ROLE_DESCRIPTION,
    /**
     * A role to contact that changes every year, e.g. "this year's directeur of Chalmersspexet". Never a named person.
     */
    CONTACT_ROLE
}
