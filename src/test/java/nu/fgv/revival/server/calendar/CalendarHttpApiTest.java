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

import nu.fgv.revival.server.util.AbstractApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest(CalendarHttpApi.class)
class CalendarHttpApiTest extends AbstractApiTest {

    @MockitoBean
    private CalendarFeedService service;

    @Test
    void servesTheFeedAsPrivateCalendarText() throws Exception {
        final String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-CALNAME:Föreställning\r\nEND:VCALENDAR\r\n";
        when(service.render("token", Locale.ENGLISH)).thenReturn(Optional.of(ics));

        mockMvc
                .perform(get("/api/calendar/{token}.ics", "token").queryParam("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/calendar;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=900, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(ics.getBytes(StandardCharsets.UTF_8)))
                .andDo(document(
                        "calendar-feed-get",
                        pathParameters(parameterWithName("token").description("The secret of the calendar link")),
                        queryParameters(parameterWithName("lang").description("`en` for English; anything else gives Swedish").optional()),
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("`text/calendar`"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("Private, for 15 minutes"),
                                headerWithName("X-Content-Type-Options").description("`nosniff`")
                        ),
                        noSecurity()
                ));
    }

    @Test
    void englishOnlyWhenAskedFor() throws Exception {
        when(service.render("token", Locale.ENGLISH)).thenReturn(Optional.of("BEGIN:VCALENDAR\r\n"));
        when(service.render("token", Locale.of("sv"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/calendar/{token}.ics", "token").queryParam("lang", "en")).andExpect(status().isOk());
        mockMvc.perform(get("/api/calendar/{token}.ics", "token").queryParam("lang", "de")).andExpect(status().isNotFound());
    }

    @Test
    void anythingTheServiceRefusesIsNotFound() throws Exception {
        when(service.render(anyString(), any())).thenReturn(Optional.empty());

        mockMvc
                .perform(get("/api/calendar/{token}.ics", "token"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]));
    }
}
