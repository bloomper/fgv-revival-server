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


package nu.fgv.revival.server.decisionlog;

import nu.fgv.revival.server.decisionlog.LogWorkbookParser.ParsedLog;
import nu.fgv.revival.server.decisionlog.LogWorkbookParser.ParsedRow;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static nu.fgv.revival.server.decisionlog.LogWorkbookFixtures.aliBabaWith;
import static nu.fgv.revival.server.decisionlog.LogWorkbookFixtures.workbook;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Every rule, warning and refusal in contracts/import.md (FR-037–FR-041), on fictitious workbooks.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class LogWorkbookParserTest {

    private final LogWorkbookParser parser = new LogWorkbookParser();

    private static <K> Map<K, Long> count(final ParsedLog log, final Function<ParsedRow, K> key) {
        return log.rows().stream().collect(Collectors.groupingBy(key, Collectors.counting()));
    }

    private static String code(final BadRequestException e) {
        return e.getDetailMessageCode();
    }

    @Nested
    class AliBaba {

        @Test
        void readsThePilotsLayout() {
            final ParsedLog log = parser.parse(LogWorkbookFixtures.aliBaba());

            assertThat(log.layout()).isEqualTo(LogImportLayout.ALI_BABA);
            assertThat(log.sheet()).isEqualTo("Logg");
            assertThat(log.ignoredSheets()).containsExactly("Checklistor", "Så funkar det");
            assertThat(log.rows()).hasSize(12).extracting(ParsedRow::number).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
            assertThat(count(log, ParsedRow::type)).isEqualTo(Map.of(LogEntryType.DECISION, 7L, LogEntryType.TODO, 4L, LogEntryType.QUESTION, 1L));
            assertThat(count(log, ParsedRow::status)).isEqualTo(Map.of(LogEntryStatus.DONE, 7L, LogEntryStatus.OPEN, 5L));
            assertThat(log.rows().stream().filter(row -> row.deadline() != null)).hasSize(2);
            assertThat(log.owners()).containsExactly(Map.entry("Kim", 8), Map.entry("Robin", 4));
            assertThat(log.warnings()).isEmpty();
            assertThat(log.rows().getFirst()).isEqualTo(new ParsedRow(2, 1, LocalDate.of(2026, 5, 8), LogEntryType.DECISION,
                    LogEntryStatus.DONE, "Rad 1 i loggen", "Kim", null, null, null));
            assertThat(log.rows().get(2).comment()).isEqualTo("Kommentar 3");
        }

        @Test
        void findsTheHeaderInAnyColumnOrderWithinTheFirst20RowsIgnoringCase() {
            final byte[] file = workbook().sheet("Annat").row("Inget här")
                    .sheet("Loggen").row("Beslutsloggen").row()
                    .row("KOMMENTAR", "status", "Klart Senast", "VEM", "vad", "Typ", "Datum", "nr")
                    .row("k", "Väntar", null, "Kim", "Text", "fråga", LocalDate.of(2026, 5, 8), 3)
                    .bytes();
            final ParsedLog log = parser.parse(file);

            assertThat(log.sheet()).isEqualTo("Loggen");
            assertThat(log.ignoredSheets()).containsExactly("Annat");
            assertThat(log.rows()).singleElement().satisfies(row -> {
                assertThat(row.number()).isEqualTo(3);
                assertThat(row.type()).isEqualTo(LogEntryType.QUESTION);
                assertThat(row.status()).isEqualTo(LogEntryStatus.WAITING);
                assertThat(row.comment()).isEqualTo("k");
            });
        }

        @Test
        void mapsEveryStatus() {
            final ParsedLog log = parser.parse(aliBabaWith(
                    new Object[]{1, LocalDate.of(2026, 1, 1), "Beslut", "a", null, null, "Öppen", null},
                    new Object[]{2, LocalDate.of(2026, 1, 1), "Beslut", "b", null, null, "Klar", null},
                    new Object[]{3, LocalDate.of(2026, 1, 1), "Beslut", "c", null, null, "Väntar", null},
                    new Object[]{4, LocalDate.of(2026, 1, 1), "Beslut", "d", null, null, "Struken", null}).bytes());

            assertThat(log.rows()).extracting(ParsedRow::status)
                    .containsExactly(LogEntryStatus.OPEN, LogEntryStatus.DONE, LogEntryStatus.WAITING, LogEntryStatus.CANCELLED);
        }

        @Test
        void unknownTypesAndStatusesAreToDoAndOpenWithWarnings() {
            final ParsedLog log = parser.parse(aliBabaWith(
                    new Object[]{1, LocalDate.of(2026, 1, 1), "Info", "a", null, null, "Pågår", null},
                    new Object[]{2, LocalDate.of(2026, 1, 1), null, "b", null, null, null, null}).bytes());

            assertThat(log.rows()).extracting(ParsedRow::type, ParsedRow::status)
                    .containsExactly(tuple(LogEntryType.TODO, LogEntryStatus.OPEN), tuple(LogEntryType.TODO, LogEntryStatus.OPEN));
            assertThat(log.warnings()).extracting(LogImportWarning::code, LogImportWarning::sheetRow, LogImportWarning::detail).containsExactly(
                    tuple("UNKNOWN_TYPE", 2, "Info"), tuple("UNKNOWN_STATUS", 2, "Pågår"),
                    tuple("UNKNOWN_TYPE", 3, null), tuple("UNKNOWN_STATUS", 3, null));
        }

        @Test
        void textDatesInTwoFormatsAreDatesAndOtherTextGoesToTheComment() {
            final ParsedLog log = parser.parse(aliBabaWith(
                    new Object[]{1, "2026-05-08", "Beslut", "a", null, "22/1 2027", "Öppen", null},
                    new Object[]{2, "i maj", "Beslut", "b", null, "v 12", "Öppen", "Kom"}).bytes());

            assertThat(log.rows().getFirst().date()).isEqualTo(LocalDate.of(2026, 5, 8));
            assertThat(log.rows().getFirst().deadline()).isEqualTo(LocalDate.of(2027, 1, 22));
            assertThat(log.rows().get(1).date()).isNull();
            assertThat(log.rows().get(1).deadline()).isNull();
            assertThat(log.rows().get(1).comment()).isEqualTo("Kom\nDatum: i maj\nKlart senast: v 12");
            assertThat(log.warnings()).extracting(LogImportWarning::code, LogImportWarning::detail)
                    .containsExactly(tuple("DATE_NOT_A_DATE", "i maj"), tuple("DEADLINE_NOT_A_DATE", "v 12"));
        }

        @Test
        void aMissingDuplicateOrNonNumericNumberRefusesTheImportListingTheRows() {
            final byte[] file = aliBabaWith(
                    new Object[]{1, LocalDate.of(2026, 1, 1), "Beslut", "a", null, null, "Öppen", null},
                    new Object[]{1, LocalDate.of(2026, 1, 1), "Beslut", "b", null, null, "Öppen", null},
                    new Object[]{null, LocalDate.of(2026, 1, 1), "Beslut", "c", null, null, "Öppen", null},
                    new Object[]{"tre", LocalDate.of(2026, 1, 1), "Beslut", "d", null, null, "Öppen", null}).bytes();

            assertThatThrownBy(() -> parser.parse(file)).isInstanceOfSatisfying(BadRequestException.class, e -> {
                assertThat(code(e)).isEqualTo("error.log.import.numbers");
                assertThat(e.getDetailMessageArguments()).containsExactly("3, 4, 5");
            });
        }

        @Test
        void anEmptyTextIsImportedAsADashWithAWarning() {
            final ParsedLog log = parser.parse(aliBabaWith(new Object[]{1, LocalDate.of(2026, 1, 1), "Beslut", null, "Kim", null, "Klar", null}).bytes());

            assertThat(log.rows().getFirst().text()).isEqualTo("—");
            assertThat(log.warnings()).extracting(LogImportWarning::code).containsExactly("EMPTY_TEXT");
        }

        @Test
        void longTextsCommentsAndOwnersAreCutWithWarnings() {
            final ParsedLog log = parser.parse(aliBabaWith(new Object[]{1, LocalDate.of(2026, 1, 1), "Beslut", "x".repeat(5001),
                    "o".repeat(101), null, "Klar", "c".repeat(5001)}).bytes());
            final ParsedRow row = log.rows().getFirst();

            assertThat(row.text()).hasSize(5000);
            assertThat(row.comment()).hasSize(5000);
            assertThat(row.ownerName()).hasSize(100);
            assertThat(log.warnings()).extracting(LogImportWarning::code)
                    .containsExactlyInAnyOrder("TEXT_TRUNCATED", "COMMENT_TRUNCATED", "OWNER_TRUNCATED");
        }
    }

    @Nested
    class Nobel {

        @Test
        void readsNobelsLayout() {
            final ParsedLog log = parser.parse(LogWorkbookFixtures.nobel());

            assertThat(log.layout()).isEqualTo(LogImportLayout.NOBEL);
            assertThat(log.rows()).extracting(ParsedRow::number, ParsedRow::text, ParsedRow::status, ParsedRow::tag, ParsedRow::ownerName)
                    .containsExactly(
                            tuple(1, "Rephelg 1 bokning lokal: Kolla vilka lokaler vi har", LogEntryStatus.OPEN, "möte 5", "Kim"),
                            tuple(2, "Bokföring: Kolla när den går igång", LogEntryStatus.DONE, "möte 5", "Robin"),
                            tuple(3, "Utskick CIng: Underlag till nyhetsbrevet", LogEntryStatus.DONE, "möte 4", "Sam"),
                            tuple(4, "Ölstugan tullen", LogEntryStatus.OPEN, null, null),
                            tuple(5, "Matinékalas: Kolla lokaler för matinékalas", LogEntryStatus.OPEN, "möte 3", "kim"));
            assertThat(log.rows()).allSatisfy(row -> {
                assertThat(row.type()).isNull();
                assertThat(row.date()).isNull();
            });
            assertThat(log.rows().get(2).deadline()).isEqualTo(LocalDate.of(2026, 2, 3));
            assertThat(log.rows().get(4).comment()).isEqualTo("Deadline: v 12");
            assertThat(log.owners()).containsExactly(Map.entry("Kim", 2), Map.entry("Robin", 1), Map.entry("Sam", 1));
            assertThat(log.warnings()).extracting(LogImportWarning::code, LogImportWarning::sheetRow, LogImportWarning::detail).containsExactly(
                    tuple("HEADER_REPEATED", 6, null), tuple("DEADLINE_NOT_A_DATE", 8, "v 12"), tuple("EXTRA_COLUMNS", null, "I"));
        }
    }

    @Nested
    class Refusals {

        private void refused(final byte[] file, final String code) {
            assertThatThrownBy(() -> parser.parse(file)).isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(code(e)).isEqualTo(code));
        }

        @Test
        void notAnXlsxWorkbook() {
            refused("not excel".getBytes(StandardCharsets.UTF_8), "error.log.import.notExcel");
            refused(aliBabaWith(new Object[]{1, LocalDate.of(2026, 1, 1), "Beslut", "a", null, null, "Klar", null}).xls(), "error.log.import.notExcel");
        }

        @Test
        void noKnownLayout() {
            refused(workbook().sheet("Blad1").row("Nr", "Datum", "Vad").row(1, LocalDate.of(2026, 1, 1), "a").bytes(), "error.log.import.layout");
        }

        @Test
        void aHeaderBelowRow20IsNotFound() {
            final LogWorkbookFixtures.Builder builder = workbook().sheet("Logg");

            for (int index = 0; index < 20; index++) {
                builder.row("Titel " + index);
            }
            refused(builder.row(LogWorkbookFixtures.ALI_BABA_HEADER.toArray()).bytes(), "error.log.import.layout");
        }

        @Test
        void noRows() {
            refused(aliBabaWith().bytes(), "error.log.import.empty");
        }

        @Test
        void moreThan5000Rows() {
            final Object[][] rows = new Object[5001][];

            for (int index = 0; index < rows.length; index++) {
                rows[index] = new Object[]{index + 1, LocalDate.of(2026, 1, 1), "Beslut", "Rad", null, null, "Klar", null};
            }
            refused(aliBabaWith(rows).bytes(), "error.log.import.tooManyRows");
            assertThat(parser.parse(aliBabaWith(java.util.Arrays.copyOf(rows, 5000)).bytes()).rows()).hasSize(5000);
        }
    }
}
