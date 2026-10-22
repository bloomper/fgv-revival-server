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

import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads an Excel decision log in the Ali Baba or Nobel layout (contracts/import.md, research R12). Stores nothing.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public final class LogWorkbookParser {

    public static final int MAX_ROWS = 5000;
    static final int HEADER_SEARCH_ROWS = 20;
    private static final int DETAIL_MAX_LENGTH = 100;
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("d/M yyyy"),
    };
    private static final Pattern WHOLE_NUMBER = Pattern.compile("\\d+(\\.0+)?");

    private static final List<String> ALI_BABA = List.of("nr", "datum", "typ", "vad", "vem", "klart senast", "status", "kommentar");
    private static final List<String> NOBEL = List.of("möte", "beslutspunkt", "beslut", "ansvarig", "deadline", "klart");
    private static final Map<String, LogEntryType> TYPES = Map.of("beslut", LogEntryType.DECISION, "att göra", LogEntryType.TODO, "fråga", LogEntryType.QUESTION);
    private static final Map<String, LogEntryStatus> STATUSES = Map.of(
            "öppen", LogEntryStatus.OPEN, "klar", LogEntryStatus.DONE, "väntar", LogEntryStatus.WAITING, "struken", LogEntryStatus.CANCELLED);

    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    /**
     * A row ready to import. {@code date} null means the import date; {@code type} null means the type chosen in the
     * preview (Nobel).
     */
    public record ParsedRow(int sheetRow, int number, @Nullable LocalDate date, @Nullable LogEntryType type, LogEntryStatus status,
                            String text, @Nullable String ownerName, @Nullable LocalDate deadline, @Nullable String comment,
                            @Nullable String tag) {
    }

    public record ParsedLog(LogImportLayout layout, String sheet, List<String> ignoredSheets, List<ParsedRow> rows,
                            Map<String, Integer> owners, List<LogImportWarning> warnings) {
    }

    public ParsedLog parse(final byte[] workbook) {
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
            return parse(book);
        } catch (final POIXMLException | IOException | IllegalArgumentException e) {
            // Not an .xlsx workbook, an .xls one (OLE2) included
            throw new BadRequestException("error.log.import.notExcel");
        }
    }

    private ParsedLog parse(final XSSFWorkbook book) {
        for (int index = 0; index < book.getNumberOfSheets(); index++) {
            final Sheet sheet = book.getSheetAt(index);
            final Optional<Header> header = header(sheet);

            if (header.isPresent()) {
                final List<String> ignored = new ArrayList<>();

                for (int other = 0; other < book.getNumberOfSheets(); other++) {
                    if (other != index) {
                        ignored.add(book.getSheetName(other));
                    }
                }
                return rows(sheet, header.get(), ignored);
            }
        }
        throw new BadRequestException("error.log.import.layout");
    }

    private record Header(LogImportLayout layout, int rowIndex, Map<String, Integer> columns) {

        int column(final String name) {
            return columns.get(name);
        }

        int lastColumn() {
            return columns.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }
    }

    private Optional<Header> header(final Sheet sheet) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SEARCH_ROWS - 1); rowIndex++) {
            final Row row = sheet.getRow(rowIndex);

            if (row == null) {
                continue;
            }

            final Map<String, Integer> columns = new HashMap<>();

            for (final Cell cell : row) {
                columns.putIfAbsent(text(cell).toLowerCase(Locale.ROOT), cell.getColumnIndex());
            }
            if (columns.keySet().containsAll(ALI_BABA)) {
                return Optional.of(new Header(LogImportLayout.ALI_BABA, rowIndex, select(columns, ALI_BABA)));
            }
            if (columns.keySet().containsAll(NOBEL)) {
                return Optional.of(new Header(LogImportLayout.NOBEL, rowIndex, select(columns, NOBEL)));
            }
        }
        return Optional.empty();
    }

    private static Map<String, Integer> select(final Map<String, Integer> columns, final List<String> names) {
        final Map<String, Integer> selected = new LinkedHashMap<>();

        names.forEach(name -> selected.put(name, columns.get(name)));
        return selected;
    }

    private ParsedLog rows(final Sheet sheet, final Header header, final List<String> ignored) {
        final List<LogImportWarning> warnings = new ArrayList<>();
        final List<ParsedRow> rows = new ArrayList<>();
        final Map<String, String> ownerNames = new LinkedHashMap<>();
        final Map<String, Integer> ownerCounts = new HashMap<>();
        final TreeSet<Integer> extraColumns = new TreeSet<>();
        final List<Integer> badNumbers = new ArrayList<>();
        final Map<Integer, Integer> numbersSeen = new HashMap<>();
        int count = 0;

        for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            final Row row = sheet.getRow(rowIndex);
            final int sheetRow = rowIndex + 1;

            if (row == null) {
                continue;
            }

            final Map<String, String> values = new HashMap<>();

            header.columns().forEach((name, column) -> values.put(name, text(row.getCell(column))));
            if (values.values().stream().allMatch(String::isEmpty)) {
                continue;
            }
            if (header.columns().keySet().stream().allMatch(name -> values.get(name).equalsIgnoreCase(name))) {
                warnings.add(new LogImportWarning("HEADER_REPEATED", sheetRow, null));
                continue;
            }
            for (final Cell cell : row) {
                if (cell.getColumnIndex() > header.lastColumn() && !text(cell).isEmpty()) {
                    extraColumns.add(cell.getColumnIndex());
                }
            }
            if (++count > MAX_ROWS) {
                throw new BadRequestException("error.log.import.tooManyRows", MAX_ROWS);
            }

            final ParsedRow parsed = header.layout() == LogImportLayout.ALI_BABA
                    ? aliBaba(row, header, values, sheetRow, warnings, badNumbers, numbersSeen)
                    : nobel(row, header, values, sheetRow, rows.size() + 1, warnings);

            if (parsed == null) {
                continue;
            }
            rows.add(parsed);
            if (parsed.ownerName() != null) {
                final String key = parsed.ownerName().toLowerCase(Locale.ROOT);

                ownerNames.putIfAbsent(key, parsed.ownerName());
                ownerCounts.merge(key, 1, Integer::sum);
            }
        }
        if (!badNumbers.isEmpty()) {
            throw new BadRequestException("error.log.import.numbers",
                    badNumbers.stream().sorted().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        if (rows.isEmpty()) {
            throw new BadRequestException("error.log.import.empty");
        }
        if (!extraColumns.isEmpty()) {
            warnings.add(new LogImportWarning("EXTRA_COLUMNS", null,
                    extraColumns.stream().map(CellReference::convertNumToColString).collect(Collectors.joining(", "))));
        }

        final Map<String, Integer> owners = new LinkedHashMap<>();

        ownerNames.forEach((key, name) -> owners.put(name, ownerCounts.get(key)));
        return new ParsedLog(header.layout(), sheet.getSheetName(), ignored, rows, owners, warnings);
    }

    private @Nullable ParsedRow aliBaba(final Row row, final Header header, final Map<String, String> values, final int sheetRow,
                                        final List<LogImportWarning> warnings, final List<Integer> badNumbers,
                                        final Map<Integer, Integer> numbersSeen) {
        final String nr = values.get("nr");
        final List<String> notes = new ArrayList<>();

        if (!WHOLE_NUMBER.matcher(nr).matches() || Double.parseDouble(nr) < 1) {
            badNumbers.add(sheetRow);
            return null;
        }

        final int number = (int) Double.parseDouble(nr);

        if (numbersSeen.putIfAbsent(number, sheetRow) != null) {
            badNumbers.add(sheetRow);
            return null;
        }

        final Cell dateCell = row.getCell(header.column("datum"));
        final @Nullable LocalDate date = date(dateCell);

        if (date == null) {
            warnings.add(new LogImportWarning("DATE_NOT_A_DATE", sheetRow, detail(values.get("datum"))));
            if (!values.get("datum").isEmpty()) {
                notes.add("Datum: " + values.get("datum"));
            }
        }

        final LogEntryType type = TYPES.get(values.get("typ").toLowerCase(Locale.ROOT));

        if (type == null) {
            warnings.add(new LogImportWarning("UNKNOWN_TYPE", sheetRow, detail(values.get("typ"))));
        }

        final LogEntryStatus status = STATUSES.get(values.get("status").toLowerCase(Locale.ROOT));

        if (status == null) {
            warnings.add(new LogImportWarning("UNKNOWN_STATUS", sheetRow, detail(values.get("status"))));
        }

        final @Nullable LocalDate deadline = deadline(row.getCell(header.column("klart senast")), values.get("klart senast"), "Klart senast",
                sheetRow, warnings, notes);

        return new ParsedRow(sheetRow, number, date, type == null ? LogEntryType.TODO : type, status == null ? LogEntryStatus.OPEN : status,
                text(values.get("vad"), sheetRow, warnings), owner(values.get("vem"), sheetRow, warnings), deadline,
                comment(values.get("kommentar"), notes, sheetRow, warnings), null);
    }

    private ParsedRow nobel(final Row row, final Header header, final Map<String, String> values, final int sheetRow, final int number,
                            final List<LogImportWarning> warnings) {
        final List<String> notes = new ArrayList<>();
        final String topic = values.get("beslutspunkt");
        final String decision = values.get("beslut");
        final String text = topic.isEmpty() || decision.isEmpty() ? topic + decision : topic + ": " + decision;
        final String meeting = values.get("möte");
        final @Nullable String tag = meeting.isEmpty() ? null
                : WHOLE_NUMBER.matcher(meeting).matches() ? "möte " + (int) Double.parseDouble(meeting)
                : meeting.length() > LogEntry.TAG_MAX_LENGTH ? meeting.substring(0, LogEntry.TAG_MAX_LENGTH) : meeting;
        final String done = values.get("klart").toLowerCase(Locale.ROOT);
        final @Nullable LocalDate deadline = deadline(row.getCell(header.column("deadline")), values.get("deadline"), "Deadline",
                sheetRow, warnings, notes);

        return new ParsedRow(sheetRow, number, null, null,
                done.equals("ja") || done.equals("klart") ? LogEntryStatus.DONE : LogEntryStatus.OPEN,
                text(text, sheetRow, warnings), owner(values.get("ansvarig"), sheetRow, warnings), deadline,
                comment("", notes, sheetRow, warnings), tag);
    }

    private static String text(final String text, final int sheetRow, final List<LogImportWarning> warnings) {
        if (text.isEmpty()) {
            warnings.add(new LogImportWarning("EMPTY_TEXT", sheetRow, null));
            return "—";
        }
        if (text.length() > LogEntry.TEXT_MAX_LENGTH) {
            warnings.add(new LogImportWarning("TEXT_TRUNCATED", sheetRow, null));
            return text.substring(0, LogEntry.TEXT_MAX_LENGTH);
        }
        return text;
    }

    private static @Nullable String owner(final String name, final int sheetRow, final List<LogImportWarning> warnings) {
        if (name.isEmpty()) {
            return null;
        }
        if (name.length() > Owner.NAME_MAX_LENGTH) {
            warnings.add(new LogImportWarning("OWNER_TRUNCATED", sheetRow, detail(name)));
            return name.substring(0, Owner.NAME_MAX_LENGTH).strip();
        }
        return name;
    }

    private static @Nullable String comment(final String comment, final List<String> notes, final int sheetRow, final List<LogImportWarning> warnings) {
        final String joined = Stream.concat(Stream.of(comment), notes.stream())
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining("\n"));

        if (joined.isEmpty()) {
            return null;
        }
        if (joined.length() > LogEntry.TEXT_MAX_LENGTH) {
            warnings.add(new LogImportWarning("COMMENT_TRUNCATED", sheetRow, null));
            return joined.substring(0, LogEntry.TEXT_MAX_LENGTH);
        }
        return joined;
    }

    private @Nullable LocalDate deadline(final @Nullable Cell cell, final String text, final String column, final int sheetRow,
                                         final List<LogImportWarning> warnings, final List<String> notes) {
        if (text.isEmpty()) {
            return null;
        }

        final @Nullable LocalDate date = date(cell);

        if (date == null) {
            warnings.add(new LogImportWarning("DEADLINE_NOT_A_DATE", sheetRow, detail(text)));
            notes.add(column + ": " + text);
        }
        return date;
    }

    private @Nullable LocalDate date(final @Nullable Cell cell) {
        if (cell == null) {
            return null;
        }

        final CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();

        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }

        final String text = text(cell);

        for (final DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format);
            } catch (final DateTimeParseException e) {
                // Try the next format
            }
        }
        return null;
    }

    private String text(final @Nullable Cell cell) {
        return cell == null ? "" : Objects.requireNonNullElse(formatter.formatCellValue(cell), "").strip();
    }

    private static @Nullable String detail(final String text) {
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > DETAIL_MAX_LENGTH ? text.substring(0, DETAIL_MAX_LENGTH) : text;
    }
}
