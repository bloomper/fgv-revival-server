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

package nu.fgv.revival.server.util;

import java.util.regex.Pattern;

/**
 * Email subjects, which take text people typed: a meeting's title, a mailing's subject, a display name.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class MailSubjects {

    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n\\u0085\\u2028\\u2029]+");

    private MailSubjects() {
    }

    /**
     * The subject on one line, so no line break in it can start a header of its own.
     */
    public static String oneLine(final String subject) {
        return LINE_BREAKS.matcher(subject).replaceAll(" ").strip();
    }
}
