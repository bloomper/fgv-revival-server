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

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amounts of money: Swedish kronor with at most two decimals (öre), as GraphQL's {@code Amount} carries them.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
public final class Amounts {

    public static final int SCALE = 2;

    private Amounts() {
    }

    /**
     * The amount with two decimals, or null when it has more than two after trailing zeros are dropped.
     */
    public static @Nullable BigDecimal exact(final BigDecimal amount) {
        final BigDecimal stripped = amount.stripTrailingZeros();

        if (stripped.scale() > SCALE) {
            return null;
        }
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * An amount written as text, e.g. {@code "1200"}, {@code "-35.50"} or {@code "1 200,50"}, or null when it is not one
     * or has more than two decimals.
     */
    public static @Nullable BigDecimal parse(final @Nullable String text) {
        if (text == null) {
            return null;
        }

        final String cleaned = text.strip().replace(" ", "").replace(" ", "").replace(',', '.');

        if (cleaned.isEmpty() || !cleaned.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)")) {
            return null;
        }
        return exact(new BigDecimal(cleaned));
    }

    /**
     * Without exponent, and without decimals when there are no öre: {@code "1200"}, {@code "1200.50"}.
     */
    public static String format(final BigDecimal amount) {
        final BigDecimal rounded = amount.setScale(SCALE, RoundingMode.HALF_UP);

        return rounded.remainder(BigDecimal.ONE).signum() == 0
                ? rounded.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : rounded.toPlainString();
    }

    /**
     * Rounded to öre, half up.
     */
    public static BigDecimal round(final BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * {@code quantity × unitAmount}, rounded to öre, half up.
     */
    public static BigDecimal times(final BigDecimal quantity, final BigDecimal unitAmount) {
        return round(quantity.multiply(unitAmount));
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal orZero(final @Nullable BigDecimal amount) {
        return amount == null ? zero() : amount;
    }
}
