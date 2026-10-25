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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hand-written iCalendar (RFC 5545): line endings, folding, escaping and the two kinds of entry.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class IcsWriterTest {

    private static final Instant STAMP = Instant.parse("2027-11-01T08:30:00Z");

    private static String unfold(final String ics) {
        return ics.replace("\r\n ", "");
    }

    @Test
    void everyLineEndsWithCrlf() {
        final String ics = IcsWriter.write("Ali Baba 2028", "Europe/Stockholm", List.of());

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//FGV//Nyuppsättning//SV\r\n").endsWith("END:VCALENDAR\r\n");
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
        assertThat(ics).contains("X-WR-CALNAME:Ali Baba 2028\r\n").contains("X-WR-TIMEZONE:Europe/Stockholm\r\n");
    }

    @Test
    void anAllDayEntryEndsTheDayAfterItsLastDay() {
        final String ics = IcsWriter.write("Kalender", "Europe/Stockholm", List.of(new CalendarEntry(CalendarEntry.uid("event", 12L), "Rephelg 1",
                "Kemi", null, new CalendarEntry.AllDay(LocalDate.of(2027, 12, 11), LocalDate.of(2027, 12, 12)), STAMP)));

        assertThat(ics).contains("BEGIN:VEVENT\r\nUID:event-12@nyuppsattning.fgv.nu\r\nDTSTAMP:20271101T083000Z\r\n"
                + "DTSTART;VALUE=DATE:20271211\r\nDTEND;VALUE=DATE:20271213\r\nSUMMARY:Rephelg 1\r\nLOCATION:Kemi\r\nEND:VEVENT\r\n");
        assertThat(ics).doesNotContain("DESCRIPTION");
    }

    @Test
    void aTimedEntryIsInUtc() {
        final String ics = IcsWriter.write("Kalender", "Europe/Stockholm", List.of(new CalendarEntry("show-3@nyuppsattning.fgv.nu", "Föreställning",
                null, "Premiär", new CalendarEntry.Timed(Instant.parse("2028-02-10T18:00:00Z"), Instant.parse("2028-02-10T20:30:00Z")), STAMP)));

        assertThat(ics).contains("DTSTART:20280210T180000Z\r\nDTEND:20280210T203000Z\r\n").contains("DESCRIPTION:Premiär\r\n");
    }

    @Test
    void textIsEscaped() {
        assertThat(IcsWriter.text("a\\b;c,d\ne\r\nf\rg\th")).isEqualTo("a\\\\b\\;c\\,d\\ne\\nf\\ngh");
    }

    @Test
    void longLinesAreFoldedAt75OctetsWithoutSplittingACharacter() {
        final String summary = "Förställningshelg med ö, å och ä i Kårhuset ".repeat(5) + "🎭";
        final String ics = IcsWriter.write("Kalender", "Europe/Stockholm", List.of(new CalendarEntry("event-1@nyuppsattning.fgv.nu", summary,
                null, null, new CalendarEntry.AllDay(LocalDate.of(2028, 2, 10), LocalDate.of(2028, 2, 10)), STAMP)));
        final List<String> lines = Arrays.asList(ics.split("\r\n"));

        assertThat(lines).allSatisfy(line -> assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75));
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith(" "));
        assertThat(ics).doesNotContain("�");
        assertThat(unfold(ics)).contains("SUMMARY:" + IcsWriter.text(summary) + "\r\n");
        for (final String line : lines) {
            assertThat(new String(line.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(line);
            assertThat(Character.isHighSurrogate(line.charAt(line.length() - 1))).isFalse();
        }
    }

    @Test
    void aLineOfExactly75OctetsIsNotFolded() {
        final StringBuilder ics = new StringBuilder();

        IcsWriter.line(ics, "X".repeat(75));
        assertThat(ics.toString()).isEqualTo("X".repeat(75) + "\r\n");

        final StringBuilder longer = new StringBuilder();

        IcsWriter.line(longer, "X".repeat(76));
        assertThat(longer.toString()).isEqualTo("X".repeat(75) + "\r\n X\r\n");
    }
}
