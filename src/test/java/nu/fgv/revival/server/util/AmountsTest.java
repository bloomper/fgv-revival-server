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

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import nu.fgv.revival.server.util.graphql.CustomScalars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Amounts of money and the {@code Amount} scalar: at most two decimals, written without exponent.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
class AmountsTest {

    @SuppressWarnings("unchecked")
    private static final Coercing<BigDecimal, String> AMOUNT = (Coercing<BigDecimal, String>) CustomScalars.AMOUNT.getCoercing();
    private static final GraphQLContext CONTEXT = GraphQLContext.getDefault();

    @ParameterizedTest
    @CsvSource({
            "1200,       1200",
            "1200.5,     1200.50",
            "1200.50,    1200.50",
            "-35.5,      -35.50",
            "0,          0",
            "0.01,       0.01",
            "1E+3,       1000",
            "12345678.9, 12345678.90"
    })
    void amountsAreWrittenWithoutExponentAndWithÖreOnlyWhenThereAreAny(final String value, final String written) {
        assertThat(Amounts.format(new BigDecimal(value))).isEqualTo(written);
        assertThat(AMOUNT.serialize(new BigDecimal(value), CONTEXT, Locale.ROOT)).isEqualTo(written);
    }

    @ParameterizedTest
    @CsvSource({
            "1200,       1200.00",
            "' 1 200,50 ', 1200.50",
            "1200.5,     1200.50",
            "-35.50,     -35.50",
            "+7,         7.00",
            ".5,         0.50",
            "1.500,      1.50"
    })
    void textIsReadWithSpacesAndDecimalCommas(final String text, final String amount) {
        assertThat(Amounts.parse(text)).isEqualTo(new BigDecimal(amount));
        assertThat(AMOUNT.parseValue(text, CONTEXT, Locale.ROOT)).isEqualTo(new BigDecimal(amount));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "1.234", "1e3", "12,34,5", "1.2.3", "NaN", "--1"})
    void anythingElseIsRefused(final String text) {
        assertThat(Amounts.parse(text)).isNull();
        assertThatThrownBy(() -> AMOUNT.parseValue(text, CONTEXT, Locale.ROOT)).isInstanceOf(CoercingParseValueException.class);
    }

    @Test
    void numbersAreReadToo() {
        assertThat(AMOUNT.parseValue(1200, CONTEXT, Locale.ROOT)).isEqualTo(new BigDecimal("1200.00"));
        assertThat(AMOUNT.parseValue(5_000_000_000L, CONTEXT, Locale.ROOT)).isEqualTo(new BigDecimal("5000000000.00"));
        assertThat(AMOUNT.parseValue(12.5, CONTEXT, Locale.ROOT)).isEqualTo(new BigDecimal("12.50"));
        assertThat(AMOUNT.parseValue(new BigInteger("7"), CONTEXT, Locale.ROOT)).isEqualTo(new BigDecimal("7.00"));
        assertThat(AMOUNT.parseValue(new BigDecimal("0.10"), CONTEXT, Locale.ROOT)).isEqualTo(new BigDecimal("0.10"));
        assertThatThrownBy(() -> AMOUNT.parseValue(0.125, CONTEXT, Locale.ROOT)).isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> AMOUNT.parseValue(Double.NaN, CONTEXT, Locale.ROOT)).isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> AMOUNT.parseValue(true, CONTEXT, Locale.ROOT)).isInstanceOf(CoercingParseValueException.class);
    }

    @Test
    void literalsMayBeStringsOrNumbers() {
        assertThat(AMOUNT.parseLiteral(new StringValue("99.90"), CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isEqualTo(new BigDecimal("99.90"));
        assertThat(AMOUNT.parseLiteral(new IntValue(BigInteger.TEN), CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isEqualTo(new BigDecimal("10.00"));
        assertThat(AMOUNT.parseLiteral(new FloatValue(new BigDecimal("2.5")), CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isEqualTo(new BigDecimal("2.50"));
        assertThatThrownBy(() -> AMOUNT.parseLiteral(new FloatValue(new BigDecimal("2.555")), CoercedVariables.emptyVariables(), CONTEXT, Locale.ROOT))
                .isInstanceOf(CoercingParseLiteralException.class);
    }

    @Test
    void onlyBigDecimalsAreSerialized() {
        assertThatThrownBy(() -> AMOUNT.serialize("12", CONTEXT, Locale.ROOT)).isInstanceOf(CoercingSerializeException.class);
    }

    @Test
    void timesRoundsToÖreHalfUp() {
        assertThat(Amounts.times(new BigDecimal("3"), new BigDecimal("33.33"))).isEqualTo(new BigDecimal("99.99"));
        assertThat(Amounts.times(new BigDecimal("0.5"), new BigDecimal("0.05"))).isEqualTo(new BigDecimal("0.03"));
        assertThat(Amounts.times(new BigDecimal("2.25"), new BigDecimal("10.10"))).isEqualTo(new BigDecimal("22.73"));
    }
}
