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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 0.5
 */
@ExtendWith(MockitoExtension.class)
class CalendarHttpApiTest {

    @Mock
    private CalendarFeedService service;

    @InjectMocks
    private CalendarHttpApi api;

    @Test
    void servesTheFeedAsPrivateCalendarText() {
        when(service.render("token", Locale.of("sv"))).thenReturn(Optional.of("BEGIN:VCALENDAR\r\nX-WR-CALNAME:Föreställning\r\n"));

        final ResponseEntity<byte[]> response = api.feed("token", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("text/calendar;charset=UTF-8"));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=900, private");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).isEqualTo("BEGIN:VCALENDAR\r\nX-WR-CALNAME:Föreställning\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void englishOnlyWhenAskedFor() {
        when(service.render("token", Locale.ENGLISH)).thenReturn(Optional.of("BEGIN:VCALENDAR\r\n"));
        when(service.render("token", Locale.of("sv"))).thenReturn(Optional.empty());

        assertThat(api.feed("token", "en").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.feed("token", "de").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anythingTheServiceRefusesIsNotFound() {
        when(service.render(anyString(), any())).thenReturn(Optional.empty());

        assertThat(api.feed("token", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.feed("token", null).getBody()).isNull();
    }
}
