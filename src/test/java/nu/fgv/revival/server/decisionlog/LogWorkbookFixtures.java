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

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fictitious Excel logs in the Ali Baba and Nobel layouts, built with POI (constitution V: the real files are never
 * committed). Shared by the parser, service and HTTP tests.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public final class LogWorkbookFixtures {

    public static final List<String> ALI_BABA_HEADER = List.of("Nr", "Datum", "Typ", "Vad", "Vem", "Klart senast", "Status", "Kommentar");
    public static final List<String> NOBEL_HEADER = List.of("Möte", "Beslutspunkt", "Beslut", "Ansvarig", "Deadline", "Klart");

    private LogWorkbookFixtures() {
    }

    /**
     * Sheets of rows; a cell is a String, a Number, a LocalDate (a date cell) or null.
     */
    public static final class Builder {

        private final List<String> names = new ArrayList<>();
        private final List<List<List<Object>>> sheets = new ArrayList<>();

        public Builder sheet(final String name) {
            names.add(name);
            sheets.add(new ArrayList<>());
            return this;
        }

        public Builder row(final Object... cells) {
            sheets.getLast().add(java.util.Arrays.asList(cells));
            return this;
        }

        public byte[] bytes() {
            return write(new XSSFWorkbook());
        }

        public byte[] xls() {
            return write(new HSSFWorkbook());
        }

        private byte[] write(final Workbook book) {
            try (book; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                final CellStyle dateStyle = book.createCellStyle();

                dateStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
                for (int index = 0; index < names.size(); index++) {
                    final Sheet sheet = book.createSheet(names.get(index));
                    final List<List<Object>> rows = sheets.get(index);

                    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                        final Row row = sheet.createRow(rowIndex);
                        final List<Object> cells = rows.get(rowIndex);

                        for (int column = 0; column < cells.size(); column++) {
                            final Object value = cells.get(column);

                            if (value == null) {
                                continue;
                            }

                            final Cell cell = row.createCell(column);

                            switch (value) {
                                case final Number number -> cell.setCellValue(number.doubleValue());
                                case final LocalDate date -> {
                                    cell.setCellValue(date);
                                    cell.setCellStyle(dateStyle);
                                }
                                default -> cell.setCellValue(value.toString());
                            }
                        }
                    }
                }
                book.write(out);
                return out.toByteArray();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static Builder workbook() {
        return new Builder();
    }

    /**
     * An Ali Baba log sheet with the header and the given rows.
     */
    public static Builder aliBabaWith(final Object[]... rows) {
        final Builder builder = workbook().sheet("Logg").row(ALI_BABA_HEADER.toArray());

        for (final Object[] row : rows) {
            builder.row(row);
        }
        return builder;
    }

    /**
     * The pilot's layout: 12 rows numbered 1–12, 7 decisions, 4 to-dos and 1 question; 7 done and 5 open; 2
     * deadlines; owners "Kim" (8 rows) and "Robin" (4). Matches contracts/import.md's expected result.
     */
    public static byte[] aliBaba() {
        final String[] types = {"Beslut", "Beslut", "Beslut", "Att göra", "Beslut", "Beslut", "Fråga", "Beslut", "Att göra", "Beslut", "Att göra", "Att göra"};
        final String[] statuses = {"Klar", "Klar", "Klar", "Klar", "Öppen", "Klar", "Öppen", "Klar", "Öppen", "Klar", "Öppen", "Öppen"};
        final String[] owners = {"Kim", "Kim", "Kim", "Kim", "Kim", "Robin", "Robin", "Robin", "Kim", "Robin", "Kim", "Kim"};
        final Builder builder = workbook().sheet("Logg").row(ALI_BABA_HEADER.toArray());

        for (int index = 0; index < 12; index++) {
            final int number = index + 1;
            final LocalDate date = number <= 2 ? LocalDate.of(2026, 5, 8) : number <= 7 ? LocalDate.of(2026, 5, 13) : LocalDate.of(2026, 10, 19);
            final LocalDate deadline = number == 9 ? LocalDate.of(2027, 1, 22) : number == 12 ? LocalDate.of(2026, 11, 3) : null;

            builder.row(number, date, types[index], "Rad " + number + " i loggen", owners[index], deadline, statuses[index],
                    number % 3 == 0 ? "Kommentar " + number : null);
        }
        return builder.sheet("Checklistor").row("Checklistor").row("Rephelg", "Vem", "Klart")
                .sheet("Så funkar det").row("Så funkar loggen")
                .bytes();
    }

    /**
     * Nobel's layout: a title row, the header from column B, numeric and text meeting numbers, a repeated header, a
     * topic-only row, a text deadline, "Ja"/"Klart" and empty in Klart, and a name to the right of the layout.
     */
    public static byte[] nobel() {
        return workbook().sheet("Blad1")
                .row(null, "Beslutsdagbok Nobel 2026")
                .row(null, "Möte", "Beslutspunkt", "Beslut", "Ansvarig", "Deadline", "Klart")
                .row(null, "5", "Rephelg 1 bokning lokal", "Kolla vilka lokaler vi har", "Kim", null, null, null, "Ylva")
                .row(null, 5, "Bokföring", "Kolla när den går igång", "Robin", null, "Ja")
                .row(null, "4", "Utskick CIng", "Underlag till nyhetsbrevet", "Sam", LocalDate.of(2026, 2, 3), "Klart")
                .row(null, "Möte", "Beslutspunkt", "Beslut", "Ansvarig", "Deadline", "Klart")
                .row(null, null, "Ölstugan tullen", null, null, null, null)
                .row(null, "3", "Matinékalas", "Kolla lokaler för matinékalas", "kim ", "v 12", null)
                .row()
                .bytes();
    }
}
