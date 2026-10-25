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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Email subjects stay on one line, whatever people typed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class MailSubjectsTest {

    @Test
    void joinsLinesSoNoHeaderCanBeAdded() {
        assertThat(MailSubjects.oneLine("Möte\r\nBcc: someone@example.org")).isEqualTo("Möte Bcc: someone@example.org");
        assertThat(MailSubjects.oneLine("Ett\nTvå Tre\n")).isEqualTo("Ett Två Tre");
    }

    @Test
    void keepsASubjectOnOneLine() {
        assertThat(MailSubjects.oneLine("Protokoll från styrgruppsmötet")).isEqualTo("Protokoll från styrgruppsmötet");
    }
}
