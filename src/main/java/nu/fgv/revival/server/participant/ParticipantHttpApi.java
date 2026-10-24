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

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;

/**
 * The participant's Swish QR code as an image, which GraphQL does not suit (constitution II). A thin adapter: the
 * service decides.
 *
 * @author Anders Jacobsson
 * @since 0.7
 */
@RestController
public class ParticipantHttpApi {

    static final MediaType SVG = MediaType.valueOf("image/svg+xml");

    private final ParticipationService participation;

    public ParticipantHttpApi(final ParticipationService participation) {
        this.participation = participation;
    }

    /**
     * 200 with the SVG, 404 unless the caller is a participant of the revival, it is {@code OPEN} and has a Swish number,
     * and they owe an unpaid fee; 503 when Swish is unavailable. Sandboxed like the poster, since an SVG opened on its own
     * could otherwise run script.
     */
    @GetMapping("/api/revivals/{id}/swish-qr")
    public ResponseEntity<byte[]> swishQr(@PathVariable final Long id, final Locale locale) {
        return ResponseEntity.ok()
                .contentType(SVG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(participation.swishQr(id, locale));
    }
}
