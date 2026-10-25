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


package nu.fgv.revival.server.checklist;

/**
 * How a template item's deadline is computed from a checklist's date.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum RelativeDeadlineKind {

    /**
     * A number of days; negative is before the date.
     */
    DAYS,
    /**
     * A number of Monday–Friday days; negative is before the date.
     */
    WORKING_DAYS,
    /**
     * A weekday of the ISO week before the date's week, e.g. "Monday the week before".
     */
    WEEKDAY_WEEK_BEFORE
}
