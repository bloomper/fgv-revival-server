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

package nu.fgv.revival.server.util.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import nu.fgv.revival.server.util.Amounts;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The {@code Instant} and {@code Void} scalars, as in Spexregister, and {@code Amount} for money.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
public final class CustomScalars {

    public static final GraphQLScalarType INSTANT = GraphQLScalarType.newScalar()
            .name("Instant")
            .description("An instant in UTC, ISO 8601")
            .coercing(new Coercing<java.time.Instant, String>() {
                @Override
                public String serialize(final Object dataFetcherResult, final GraphQLContext graphQLContext, final Locale locale) {
                    if (dataFetcherResult instanceof final java.time.Instant instant) {
                        return DateTimeFormatter.ISO_INSTANT.format(instant);
                    }
                    throw new CoercingSerializeException("Expected an Instant");
                }

                @Override
                public java.time.Instant parseValue(final Object input, final GraphQLContext graphQLContext, final Locale locale) {
                    try {
                        return java.time.Instant.parse((String) input);
                    } catch (final ClassCastException | DateTimeParseException e) {
                        throw new CoercingParseValueException("Invalid input", e);
                    }
                }

                @Override
                public java.time.Instant parseLiteral(final Value<?> input, final CoercedVariables variables, final GraphQLContext graphQLContext, final Locale locale) {
                    try {
                        return java.time.Instant.parse(((StringValue) input).getValue());
                    } catch (final ClassCastException | DateTimeParseException e) {
                        throw new CoercingParseLiteralException("Invalid input", e);
                    }
                }
            })
            .build();

    public static final GraphQLScalarType VOID = GraphQLScalarType.newScalar()
            .name("Void")
            .description("Represents the null value")
            .coercing(new Coercing<Void, Void>() {
                @Override
                public Void serialize(final Object dataFetcherResult, final GraphQLContext graphQLContext, final Locale locale) {
                    return null;
                }

                @Override
                public Void parseValue(final Object input, final GraphQLContext graphQLContext, final Locale locale) {
                    return null;
                }

                @Override
                public Void parseLiteral(final Value<?> input, final CoercedVariables variables, final GraphQLContext graphQLContext, final Locale locale) {
                    return null;
                }
            })
            .build();

    /**
     * Kronor with at most two decimals, written as a string without exponent ({@code "1200"}, {@code "-35.50"}). Input
     * may be a string or a number; more than two decimals is refused.
     */
    public static final GraphQLScalarType AMOUNT = GraphQLScalarType.newScalar()
            .name("Amount")
            .description("Swedish kronor with at most two decimals")
            .coercing(new Coercing<BigDecimal, String>() {
                @Override
                public String serialize(final Object dataFetcherResult, final GraphQLContext graphQLContext, final Locale locale) {
                    if (dataFetcherResult instanceof final BigDecimal amount) {
                        return Amounts.format(amount);
                    }
                    throw new CoercingSerializeException("Expected a BigDecimal");
                }

                @Override
                public BigDecimal parseValue(final Object input, final GraphQLContext graphQLContext, final Locale locale) {
                    final BigDecimal amount = amount(input);

                    if (amount == null) {
                        throw new CoercingParseValueException("Expected an amount with at most two decimals");
                    }
                    return amount;
                }

                @Override
                public BigDecimal parseLiteral(final Value<?> input, final CoercedVariables variables, final GraphQLContext graphQLContext, final Locale locale) {
                    final BigDecimal amount = switch (input) {
                        case final StringValue value -> Amounts.parse(value.getValue());
                        case final IntValue value -> Amounts.exact(new BigDecimal(value.getValue()));
                        case final FloatValue value -> Amounts.exact(value.getValue());
                        default -> null;
                    };

                    if (amount == null) {
                        throw new CoercingParseLiteralException("Expected an amount with at most two decimals");
                    }
                    return amount;
                }

                private static @Nullable BigDecimal amount(final Object input) {
                    return switch (input) {
                        case final String text -> Amounts.parse(text);
                        case final BigDecimal number -> Amounts.exact(number);
                        case final BigInteger number -> Amounts.exact(new BigDecimal(number));
                        case final Integer number -> Amounts.exact(BigDecimal.valueOf(number));
                        case final Long number -> Amounts.exact(BigDecimal.valueOf(number));
                        case final Double number when Double.isFinite(number) -> Amounts.exact(BigDecimal.valueOf(number));
                        case final Float number when Float.isFinite(number) -> Amounts.exact(new BigDecimal(number.toString()));
                        default -> null;
                    };
                }
            })
            .build();

    private CustomScalars() {
    }
}
