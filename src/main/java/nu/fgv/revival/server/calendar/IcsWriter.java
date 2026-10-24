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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes an iCalendar feed (RFC 5545) by hand, so no library is needed for this one shape: CRLF line endings, lines
 * folded at 75 octets without splitting a UTF-8 character, text escaped, and times in UTC.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
final class IcsWriter {

    static final String PRODUCT = "-//FGV//Nyuppsättning//SV";
    static final int LINE_OCTETS = 75;

    private static final String CRLF = "\r\n";
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private IcsWriter() {
    }

    static String write(final String name, final String timeZone, final List<CalendarEntry> entries) {
        final StringBuilder ics = new StringBuilder();

        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:" + PRODUCT);
        line(ics, "CALSCALE:GREGORIAN");
        line(ics, "METHOD:PUBLISH");
        line(ics, "X-WR-CALNAME:" + text(name));
        line(ics, "X-WR-TIMEZONE:" + timeZone);
        for (final CalendarEntry entry : entries) {
            line(ics, "BEGIN:VEVENT");
            line(ics, "UID:" + entry.uid());
            line(ics, "DTSTAMP:" + UTC.format(entry.stamp()));
            switch (entry.when()) {
                case CalendarEntry.AllDay(final LocalDate first, final LocalDate last) -> {
                    line(ics, "DTSTART;VALUE=DATE:" + DATE.format(first));
                    line(ics, "DTEND;VALUE=DATE:" + DATE.format(last.plusDays(1)));
                }
                case CalendarEntry.Timed(final Instant start, final Instant end) -> {
                    line(ics, "DTSTART:" + UTC.format(start));
                    line(ics, "DTEND:" + UTC.format(end));
                }
            }
            line(ics, "SUMMARY:" + text(entry.summary()));
            optional(ics, "LOCATION:", entry.location());
            optional(ics, "DESCRIPTION:", entry.description());
            line(ics, "END:VEVENT");
        }
        line(ics, "END:VCALENDAR");
        return ics.toString();
    }

    private static void optional(final StringBuilder ics, final String property, final @Nullable String value) {
        if (value != null && !value.isBlank()) {
            line(ics, property + text(value));
        }
    }

    /**
     * A TEXT value: backslashes, semicolons, commas and line breaks escaped, other control characters left out.
     */
    static String text(final String value) {
        final StringBuilder escaped = new StringBuilder(value.length());
        final String normalised = value.replace("\r\n", "\n").replace('\r', '\n');

        for (int index = 0; index < normalised.length(); index++) {
            final char c = normalised.charAt(index);

            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case ';' -> escaped.append("\\;");
                case ',' -> escaped.append("\\,");
                case '\n' -> escaped.append("\\n");
                default -> {
                    if (c >= ' ' && c != 0x7f) {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Appends the content line, folded so that no line is longer than {@value #LINE_OCTETS} octets: each continuation
     * starts with a space, and a line never ends inside a UTF-8 character or a surrogate pair.
     */
    static void line(final StringBuilder ics, final String content) {
        int octets = 0;
        int index = 0;

        while (index < content.length()) {
            final int codePoint = content.codePointAt(index);
            final int length = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;

            if (octets + length > LINE_OCTETS) {
                ics.append(CRLF).append(' ');
                octets = 1;
            }
            ics.appendCodePoint(codePoint);
            octets += length;
            index += Character.charCount(codePoint);
        }
        ics.append(CRLF);
    }
}
