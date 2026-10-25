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

package nu.fgv.revival.server.participant;

import java.text.Normalizer;
import java.util.Set;

/**
 * The message of a Swish payment for a participant's fee, e.g. "Ali Baba 2028 Anna Andersson": it identifies the
 * participant to whoever checks the account. Swish allows letters, åäö among them, digits, spaces
 * and {@code !?(),.-:;}, at most 50 characters; other letters lose their accents, anything else is left out.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
final class SwishMessage {

    static final int MAX_LENGTH = 50;

    private static final Set<Character> ALLOWED_SYMBOLS = Set.of(' ', '!', '?', '(', ')', ',', '.', '-', ':', ';');
    private static final String SWEDISH_LETTERS = "åäöÅÄÖ";

    private SwishMessage() {
    }

    static String of(final String spexTitle, final int year, final String firstName, final String lastName) {
        final String raw = "%s %d %s %s".formatted(spexTitle, year, firstName, lastName);
        final StringBuilder message = new StringBuilder();

        for (int index = 0; index < raw.length(); index++) {
            final char character = raw.charAt(index);

            if (allowed(character)) {
                message.append(character);
            } else if (Character.isWhitespace(character)) {
                message.append(' ');
            } else {
                final String base = Normalizer.normalize(String.valueOf(character), Normalizer.Form.NFD).replaceAll("\\p{M}", "");

                if (base.length() == 1 && allowed(base.charAt(0))) {
                    message.append(base);
                }
            }
        }

        final String collapsed = message.toString().replaceAll(" +", " ").strip();

        return collapsed.length() <= MAX_LENGTH ? collapsed : collapsed.substring(0, MAX_LENGTH).strip();
    }

    private static boolean allowed(final char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9')
                || SWEDISH_LETTERS.indexOf(character) >= 0 || ALLOWED_SYMBOLS.contains(character);
    }
}
