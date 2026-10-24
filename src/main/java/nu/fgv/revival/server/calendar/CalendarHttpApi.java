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


package nu.fgv.revival.server.calendar;

import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * The calendar feed behind a secret link (M4-FR-6), for calendar apps through the web, which proxies it. No token: the
 * link is the credential. A thin adapter: {@link CalendarFeedService} decides.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@RestController
public class CalendarHttpApi {

    static final MediaType TEXT_CALENDAR = new MediaType("text", "calendar", StandardCharsets.UTF_8);

    private final CalendarFeedService service;

    public CalendarHttpApi(final CalendarFeedService service) {
        this.service = service;
    }

    /**
     * 200 with the feed, in Swedish unless {@code lang=en}, since calendar apps send no useful Accept-Language. 404 for
     * a malformed, unknown or revoked token, and when the link's person may no longer have it.
     */
    @GetMapping("/api/calendar/{token}.ics")
    public ResponseEntity<byte[]> feed(@PathVariable final String token, @RequestParam(required = false) final @Nullable String lang) {
        return service.render(token, "en".equals(lang) ? Locale.ENGLISH : Locale.of("sv"))
                .map(ics -> ResponseEntity.ok()
                        .contentType(TEXT_CALENDAR)
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(15)).cachePrivate())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(ics.getBytes(StandardCharsets.UTF_8)))
                .orElseGet(() -> ResponseEntity.notFound().header("X-Content-Type-Options", "nosniff").build());
    }
}
