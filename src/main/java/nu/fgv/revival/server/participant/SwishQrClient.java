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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.util.error.SwishUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Swish's pre-filled QR code API (https://developer.swish.nu/api/qr-codes/v1#pre-filled-qr-code), which needs no
 * agreement: a QR code as SVG for a number, an amount and a message, none of them editable in the app. Every
 * failure is a {@link SwishUnavailableException}; neither the number nor the message is logged.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@Slf4j
public class SwishQrClient {

    static final int SIZE = 300;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String url;

    public SwishQrClient(@Value("${fgv-revival.swish.qr-url:https://mpc.getswish.net/qrg-swish/api/v1/prefilled}") final String url) {
        this.url = url;
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults()
                        .withConnectTimeout(TIMEOUT)
                        .withReadTimeout(TIMEOUT)))
                .build();
    }

    /**
     * The request body Swish expects.
     */
    static Map<String, Object> body(final String number, final BigDecimal amount, final String message) {
        final Map<String, Object> body = new LinkedHashMap<>();

        body.put("format", "svg");
        body.put("payee", field(number));
        body.put("amount", field(amount));
        body.put("message", field(message));
        body.put("size", SIZE);
        return body;
    }

    private static Map<String, Object> field(final Object value) {
        final Map<String, Object> field = new LinkedHashMap<>();

        field.put("value", value);
        field.put("editable", false);
        return field;
    }

    public byte[] svg(final String number, final BigDecimal amount, final String message) {
        try {
            final byte[] svg = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.valueOf("image/svg+xml"), MediaType.ALL)
                    .body(body(number, amount, message))
                    .retrieve()
                    .body(byte[].class);

            if (svg == null || svg.length == 0) {
                throw new SwishUnavailableException(null);
            }
            return svg;
        } catch (final SwishUnavailableException e) {
            throw e;
        } catch (final RuntimeException e) {
            log.warn("Swish's QR code service is unavailable: {}", e.getClass().getSimpleName());
            throw new SwishUnavailableException(e);
        }
    }
}
