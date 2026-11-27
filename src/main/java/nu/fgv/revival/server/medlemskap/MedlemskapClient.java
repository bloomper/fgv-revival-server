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

package nu.fgv.revival.server.medlemskap;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * Medlemskap's GraphQL API, as its {@code REVIVAL} role allows: the year's standard fees, one payment request for the
 * participants whose membership the revival pays, and whether that request has been paid. The request carries a key,
 * so asking again (a sync that is resumed) gives the request that was made. Logs no names.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class MedlemskapClient {

    private static final String FEE_LEVELS = """
            query FeeLevels($year: Int) {
                revivalFeeLevels(year: $year) { id year amount }
            }""";
    private static final String REQUEST_FIELDS = """
            requestId reference amount paid status included existing alreadyMembers alreadyRequested
            payee { swishNumber bankgiro bankAccount name }""";
    private static final String CREATE = """
            mutation Create($payerLabel: String!, $spexareIds: [ID!]!, $feeLevelId: ID!, $key: String, $actingPerson: String) {
                createRevivalPaymentRequest(payerLabel: $payerLabel, spexareIds: $spexareIds, feeLevelId: $feeLevelId, key: $key, actingPerson: $actingPerson) { %s }
            }""".formatted(REQUEST_FIELDS);
    private static final String STATUS = """
            query Status($id: ID!) {
                revivalPaymentRequest(id: $id) { %s }
            }""".formatted(REQUEST_FIELDS);

    private final HttpSyncGraphQlClient graphQlClient;

    public MedlemskapClient(@Qualifier("medlemskapGraphQlClient") final HttpSyncGraphQlClient graphQlClient) {
        this.graphQlClient = graphQlClient;
    }

    public record FeeLevel(String id, @Nullable Integer year, int amount) {
    }

    public record Payee(@Nullable String swishNumber, @Nullable String bankgiro, @Nullable String bankAccount,
                        @Nullable String name) {
    }

    public record PaymentRequest(String requestId, String reference, int amount, int paid, String status, int included,
                                 boolean existing,
                                 List<String> alreadyMembers, List<String> alreadyRequested, Payee payee) {
    }

    /**
     * The standard fees for the year.
     */
    public List<FeeLevel> feeLevels(final int year) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(FEE_LEVELS).variable("year", year).executeSync();

            requireValid(response);
            return response.field("revivalFeeLevels").toEntityList(FeeLevel.class);
        });
    }

    /**
     * One request that covers the fee for these spexare. Asking again with the same key gives the same request.
     *
     * @param actingPerson the person on whose behalf it is asked, for Medlemskap's audit log
     */
    public PaymentRequest request(final String key, final String payerLabel, final List<String> spexareIds, final String feeLevelId,
                                  final String actingPerson) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(CREATE)
                    .variable("payerLabel", payerLabel)
                    .variable("spexareIds", spexareIds)
                    .variable("feeLevelId", feeLevelId)
                    .variable("key", key)
                    .variable("actingPerson", actingPerson)
                    .executeSync();

            requireValid(response);
            return entity(response, "createRevivalPaymentRequest");
        });
    }

    /**
     * Where a request made earlier stands.
     */
    public PaymentRequest status(final String requestId) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(STATUS).variable("id", requestId).executeSync();

            requireValid(response);
            return entity(response, "revivalPaymentRequest");
        });
    }

    private static PaymentRequest entity(final ClientGraphQlResponse response, final String field) {
        final PaymentRequest request = response.field(field).toEntity(PaymentRequest.class);

        if (request == null) {
            throw new MedlemskapCallException("No %s in response".formatted(field), MedlemskapCallException.REFUSED);
        }
        return request;
    }

    private static void requireValid(final ClientGraphQlResponse response) {
        if (!response.getErrors().isEmpty()) {
            final boolean notFound = response.getErrors().stream().allMatch(error -> "NOT_FOUND".equals(String.valueOf(error.getErrorType())));

            throw new MedlemskapCallException("Response has errors", notFound ? MedlemskapCallException.NOT_FOUND : MedlemskapCallException.REFUSED);
        }
        if (!response.isValid()) {
            throw new MedlemskapCallException("Response has no data", MedlemskapCallException.UNAVAILABLE);
        }
    }

    private <T> T call(final Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (final MedlemskapCallException e) {
            throw e;
        } catch (final RuntimeException e) {
            log.warn("Medlemskap call failed: {}", e.getClass().getSimpleName());
            throw new MedlemskapCallException("Medlemskap could not be reached", MedlemskapCallException.UNAVAILABLE);
        }
    }

    /**
     * A call that did not succeed, with the reason a sync step records.
     */
    public static class MedlemskapCallException extends RuntimeException {

        public static final String UNAVAILABLE = "MEDLEMSKAP_UNAVAILABLE";
        public static final String REFUSED = "REFUSED";
        public static final String NOT_FOUND = "NOT_FOUND";

        private final String code;

        MedlemskapCallException(final String message, final String code) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
