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

import com.sun.net.httpserver.HttpServer;
import nu.fgv.revival.server.util.error.SwishUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Swish QR code against a local stub of Swish's API (M6-FR-19), and the message it carries.
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
class SwishQrClientTest {

    private final AtomicReference<String> received = new AtomicReference<>();
    private HttpServer stub;

    @BeforeEach
    void setUp() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/ok", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            final byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "image/svg+xml");
            exchange.sendResponseHeaders(200, svg.length);
            exchange.getResponseBody().write(svg);
            exchange.close();
        });
        stub.createContext("/down", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        stub.start();
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
    }

    private String url(final String path) {
        return "http://localhost:%d%s".formatted(stub.getAddress().getPort(), path);
    }

    @Test
    void thePrefilledCodeIsFetchedAsSvgWithNothingEditable() {
        final byte[] svg = new SwishQrClient(url("/ok")).svg("1231234567", new BigDecimal("1200.00"), "Ali Baba 2028 Anna Andersson");

        assertThat(new String(svg, StandardCharsets.UTF_8)).startsWith("<svg");
        assertThat(received.get())
                .contains("\"format\":\"svg\"")
                .contains("\"payee\":{\"value\":\"1231234567\",\"editable\":false}")
                .contains("\"amount\":{\"value\":1200.00,\"editable\":false}")
                .contains("\"message\":{\"value\":\"Ali Baba 2028 Anna Andersson\",\"editable\":false}")
                .contains("\"size\":300");
    }

    @Test
    void aFailureIsSwishUnavailable() {
        assertThatThrownBy(() -> new SwishQrClient(url("/down")).svg("1231234567", BigDecimal.TEN, "x"))
                .isInstanceOf(SwishUnavailableException.class);
    }

    @Test
    void theMessageKeepsSwishsCharactersAndFitsFiftyOfThem() {
        assertThat(SwishMessage.of("Ali Baba", 2028, "Åsa", "Öberg")).isEqualTo("Ali Baba 2028 Åsa Öberg");
        assertThat(SwishMessage.of("Café \"Noir\"", 2028, "José", "D'Arc")).isEqualTo("Cafe Noir 2028 Jose DArc");
        assertThat(SwishMessage.of("Spex!", 2028, "A", "B")).isEqualTo("Spex! 2028 A B");
        assertThat(SwishMessage.of("Den långa titeln som aldrig tar slut", 2028, "Maximiliana", "Andersson-Lindqvist"))
                .hasSize(SwishMessage.MAX_LENGTH);
        assertThat(SwishMessage.of("Ali  Baba", 2028, "Anna\tMaria", "X")).isEqualTo("Ali Baba 2028 Anna Maria X");
    }
}
