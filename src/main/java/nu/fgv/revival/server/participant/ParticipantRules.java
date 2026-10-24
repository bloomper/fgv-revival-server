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

import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.Amounts;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import static org.springframework.util.StringUtils.hasText;

/**
 * The checks on what people type for participants, so the services and their tests agree.
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
final class ParticipantRules {

    static final BigDecimal MAX_AMOUNT = new BigDecimal("100000");
    static final int ADDRESS_MAX_LENGTH = 255;
    static final int POSTAL_CODE_MAX_LENGTH = 20;
    static final int CITY_MAX_LENGTH = 100;
    static final String WHATSAPP_PREFIX = "https://chat.whatsapp.com/";

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9 ()-]+");
    private static final Pattern SWISH = Pattern.compile("\\d{10}");
    private static final Pattern WHATSAPP = Pattern.compile("https://chat\\.whatsapp\\.com/[A-Za-z0-9]+");

    private ParticipantRules() {
    }

    static @Nullable String trimmed(final @Nullable String value) {
        return hasText(value) ? value.strip() : null;
    }

    /**
     * A first or last name: 1–100 characters after trimming.
     */
    static String name(final @Nullable String name) {
        final String trimmed = trimmed(name);

        if (trimmed == null || trimmed.length() > PendingPerson.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.participant.name", PendingPerson.NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * An email address in lower case, or null when blank.
     */
    static @Nullable String email(final @Nullable String email) {
        final String trimmed = trimmed(email);

        if (trimmed == null) {
            return null;
        }

        final String normalized = CallerService.normalizedEmail(trimmed);

        if (normalized.length() > PendingPerson.EMAIL_MAX_LENGTH || !EMAIL.matcher(normalized).matches()) {
            throw new BadRequestException("error.participant.email");
        }
        return normalized;
    }

    /**
     * Whether the text looks like an email address, as a referral's contact.
     */
    static boolean looksLikeEmail(final String text) {
        return EMAIL.matcher(text.strip()).matches();
    }

    /**
     * A phone number: digits with an optional leading +, spaces, dashes and brackets, at least five digits, at most 50
     * characters; null when blank.
     */
    static @Nullable String phone(final @Nullable String phone) {
        final String trimmed = trimmed(phone);

        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() > PendingPerson.PHONE_MAX_LENGTH || !PHONE.matcher(trimmed).matches()
                || trimmed.chars().filter(Character::isDigit).count() < 5) {
            throw new BadRequestException("error.participant.phone");
        }
        return trimmed;
    }

    static @Nullable String text(final @Nullable String value, final int max, final String code) {
        final String trimmed = trimmed(value);

        if (trimmed != null && trimmed.length() > max) {
            throw new BadRequestException(code, max);
        }
        return trimmed;
    }

    /**
     * An amount in kronor, 0–100000 with at most two decimals, or null.
     */
    static @Nullable BigDecimal amount(final @Nullable BigDecimal amount) {
        if (amount == null) {
            return null;
        }

        final BigDecimal exact = Amounts.exact(amount);

        if (exact == null || exact.signum() < 0 || exact.compareTo(MAX_AMOUNT) > 0) {
            throw new BadRequestException("error.participant.amount", 0, Amounts.format(MAX_AMOUNT));
        }
        return exact;
    }

    /**
     * A Swish number, without spaces and dashes: ten digits, a 123-number among them. Null when blank.
     */
    static @Nullable String swishNumber(final @Nullable String number) {
        final String trimmed = trimmed(number);

        if (trimmed == null) {
            return null;
        }

        final String digits = trimmed.replace(" ", "").replace("-", "");

        if (!SWISH.matcher(digits).matches()) {
            throw new BadRequestException("error.participant.settings.swish");
        }
        return digits;
    }

    static @Nullable String whatsappLink(final @Nullable String link) {
        final String trimmed = trimmed(link);

        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() > ParticipationSettings.WHATSAPP_LINK_MAX_LENGTH || !WHATSAPP.matcher(trimmed).matches()) {
            throw new BadRequestException("error.participant.settings.whatsapp");
        }
        return trimmed;
    }

    /**
     * A referral's name split at the last space into first and last name; a single word is a first name, with "—" as
     * last name for the steering group to fix.
     */
    static String[] splitName(final String name) {
        final String trimmed = name.strip().replaceAll("\\s+", " ");
        final int space = trimmed.lastIndexOf(' ');

        return space < 0 ? new String[]{trimmed, "—"} : new String[]{trimmed.substring(0, space), trimmed.substring(space + 1)};
    }
}
