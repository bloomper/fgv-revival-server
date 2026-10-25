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
package nu.fgv.revival.server.audit;

/**
 * How a changed value is written, so the web can show it as it shows the field elsewhere.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum FieldKind {

    /**
     * Free text, or a name the server has looked up.
     */
    TEXT,
    /**
     * {@code yyyy-mm-dd}.
     */
    DATE,
    /**
     * {@code hh:mm}.
     */
    TIME,
    /**
     * An ISO-8601 instant.
     */
    INSTANT,
    /**
     * A decimal string, as the {@code Amount} scalar.
     */
    AMOUNT,
    NUMBER,
    /**
     * {@code true} or {@code false}.
     */
    BOOLEAN,
    /**
     * The enum value's name, which the web translates.
     */
    ENUM,
    /**
     * The values, sorted and separated by ", ".
     */
    LIST
}
