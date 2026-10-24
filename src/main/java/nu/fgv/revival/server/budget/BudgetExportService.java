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
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.budget.BudgetDtos.Line;
import nu.fgv.revival.server.budget.BudgetDtos.Settings;
import nu.fgv.revival.server.budget.BudgetDtos.Totals;
import nu.fgv.revival.server.budget.BudgetDtos.Transaction;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.event.EventService;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.util.Amounts;
import nu.fgv.revival.server.util.Locales;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A revival's budget as an Excel workbook (product spec 5.10, M5-FR-11): a summary, the budget per category, the
 * transactions with their receipts per category, and the final report. In Swedish or English; no contact details. The
 * helpers' compensation from the staffing is a line of its own in the helper category, never stored (M6B-FR-5).
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Service
public class BudgetExportService {

    private static final String AMOUNT_FORMAT = "#,##0.00";
    private static final String DATE_FORMAT = "yyyy-mm-dd";

    private final BudgetService budgetService;
    private final TransactionService transactionService;
    private final BudgetCategoryService categoryService;
    private final EventService eventService;
    private final OwnerViews ownerViews;
    private final RevivalTitles titles;
    private final CallerService callerService;
    private final BudgetAccess access;
    private final MessageSource messageSource;
    private final Clock clock;

    public BudgetExportService(final BudgetService budgetService,
                               final TransactionService transactionService,
                               final BudgetCategoryService categoryService,
                               final EventService eventService,
                               final OwnerViews ownerViews,
                               final RevivalTitles titles,
                               final CallerService callerService,
                               final BudgetAccess access,
                               final MessageSource messageSource,
                               final Clock clock) {
        this.budgetService = budgetService;
        this.transactionService = transactionService;
        this.categoryService = categoryService;
        this.eventService = eventService;
        this.ownerViews = ownerViews;
        this.titles = titles;
        this.callerService = callerService;
        this.access = access;
        this.messageSource = messageSource;
        this.clock = clock;
    }

    /**
     * {@code budget-<title>.xlsx} in ASCII letters, digits and hyphens, e.g. {@code budget-Ali-Baba-2028.xlsx}.
     */
    static String fileName(final String title) {
        final String ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "");

        return "budget" + (ascii.isEmpty() ? "" : "-" + ascii) + ".xlsx";
    }

    @Transactional(readOnly = true)
    public Export export(final Long revivalId, final @Nullable Locale requested) {
        final Locale locale = Locales.supported(requested);
        final Revival revival = access.readable(callerService.current(), revivalId);
        final String title = titles.of(revival, locale);
        final List<Transaction> transactions = transactionService.list(revivalId, false);
        final Data data = new Data(
                title,
                budgetService.settings(revivalId),
                budgetService.totals(revivalId),
                budgetService.lines(revivalId, false),
                transactions,
                categoryService.all(),
                eventService.list(revivalId, true).stream().collect(Collectors.toMap(EventDto::id, Function.identity())),
                payers(transactions, locale),
                Amounts.orZero(budgetService.helperCompensation(revivalId)),
                budgetService.helpersCategoryId());

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new Writer(workbook, locale, data).write();
            workbook.write(out);
            return new Export(fileName(title), out.toByteArray());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<Long, String> payers(final List<Transaction> transactions, final Locale locale) {
        final Map<OwnerViews.Ref, @Nullable OwnerDto> owners = ownerViews.toDtos(transactions.stream()
                .map(transaction -> new OwnerViews.Ref(transaction.revivalId(), transaction.payer())).collect(Collectors.toSet()), locale);
        final Map<Long, String> names = new HashMap<>();

        transactions.forEach(transaction -> {
            final OwnerDto owner = owners.get(new OwnerViews.Ref(transaction.revivalId(), transaction.payer()));
            final String name = owner == null ? null : owner.person() != null ? owner.person().displayName() : owner.name();

            if (name != null) {
                names.put(transaction.id(), name);
            }
        });
        return names;
    }

    /**
     * The workbook and its file name.
     */
    public record Export(String fileName, byte[] content) {
    }

    /**
     * @param helpers         the helpers' compensation for their shifts, a budgeted cost
     * @param helpersCategory the category it belongs to; null without an active helper category
     */
    private record Data(String title, Settings settings, Totals totals, List<Line> lines,
                        List<Transaction> transactions,
                        List<BudgetCategory> categories, Map<Long, EventDto> events, Map<Long, String> payers,
                        BigDecimal helpers,
                        @Nullable String helpersCategory) {

        boolean hasHelpers() {
            return helpers.signum() != 0;
        }

        /**
         * The categories with budget lines, and the helper category when there is a compensation.
         */
        Set<String> budgetedCategories(final Collection<String> withLines) {
            final Set<String> ids = new HashSet<>(withLines);

            if (hasHelpers() && helpersCategory != null) {
                ids.add(helpersCategory);
            }
            return ids;
        }
    }

    /**
     * Writes the four sheets.
     */
    private final class Writer {

        private final XSSFWorkbook workbook;
        private final Locale locale;
        private final Data data;
        private final CellStyle bold;
        private final CellStyle amount;
        private final CellStyle boldAmount;
        private final CellStyle date;

        private Writer(final XSSFWorkbook workbook, final Locale locale, final Data data) {
            this.workbook = workbook;
            this.locale = locale;
            this.data = data;

            final Font boldFont = workbook.createFont();

            boldFont.setBold(true);
            bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            amount = workbook.createCellStyle();
            amount.setDataFormat(workbook.createDataFormat().getFormat(AMOUNT_FORMAT));
            boldAmount = workbook.createCellStyle();
            boldAmount.setFont(boldFont);
            boldAmount.setDataFormat(workbook.createDataFormat().getFormat(AMOUNT_FORMAT));
            date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat(DATE_FORMAT));
        }

        private static void amountCell(final Row row, final int column, final BigDecimal value, final CellStyle style) {
            final Cell cell = row.createCell(column);

            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(style);
        }

        private static void text(final Row row, final int column, final String value, final @Nullable CellStyle style) {
            final Cell cell = row.createCell(column);

            cell.setCellValue(value);
            if (style != null) {
                cell.setCellStyle(style);
            }
        }

        private static BigDecimal sum(final List<BigDecimal> amounts) {
            return Amounts.round(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        /**
         * Fixed widths: sizing columns to their content needs fonts, which a server image may not have.
         */
        private static void widths(final Sheet sheet, final int columns) {
            sheet.setColumnWidth(0, 40 * 256);
            for (int column = 1; column < columns; column++) {
                sheet.setColumnWidth(column, 18 * 256);
            }
        }

        void write() {
            summary();
            budget();
            transactions();
            report();
        }

        // --- Helpers ------------------------------------------------------------------------------------------------

        private void summary() {
            final Sheet sheet = workbook.createSheet(message("budget.export.sheet.summary"));
            final Totals totals = data.totals();
            final Settings settings = data.settings();
            int row = 0;

            text(sheet.createRow(row++), 0, data.title(), bold);
            dateRow(sheet.createRow(row++), message("budget.export.generated"), LocalDate.now(clock));
            row++;
            final Row participants = sheet.createRow(row++);

            text(participants, 0, message("budget.export.participantsPlanned"), null);
            if (settings.participantsPlanned() != null) {
                participants.createCell(1).setCellValue(settings.participantsPlanned());
            }
            amountRow(sheet.createRow(row++), message("budget.export.participationFee"), settings.participationFee(), amount);
            row++;
            final Row header = sheet.createRow(row++);

            text(header, 1, message("budget.export.budgeted"), bold);
            text(header, 2, message("budget.export.actual"), bold);
            row = totalsRow(sheet, row, message("budget.export.income"), totals.budgetedIncome(), totals.actualIncome(), amount);
            row = totalsRow(sheet, row, message("budget.export.cost"), totals.budgetedCost(), totals.actualCost(), amount);
            row = totalsRow(sheet, row, message("budget.export.result"), totals.budgetedResult(), totals.actualResult(), boldAmount);
            row++;
            amountRow(sheet.createRow(row++), message("budget.export.loanAmount"), settings.loanAmount(), amount);
            dateRow(sheet.createRow(row++), message("budget.export.loanReceived"), settings.loanReceivedOn());
            dateRow(sheet.createRow(row), message("budget.export.loanRepaid"), settings.loanRepaidOn());
            widths(sheet, 3);
        }

        private void budget() {
            final Sheet sheet = workbook.createSheet(message("budget.export.sheet.budget"));
            final Map<String, List<Line>> lines = data.lines().stream().collect(Collectors.groupingBy(Line::categoryId));
            final Map<String, BigDecimal> actual = actualByCategory();
            int row = 0;

            final Row header = sheet.createRow(row++);

            List.of("budget.export.description", "budget.export.quantity", "budget.export.unitAmount", "budget.export.amount", "budget.export.event")
                    .forEach(key -> text(header, header.getPhysicalNumberOfCells(), message(key), bold));
            for (final BudgetCategoryKind kind : BudgetCategoryKind.values()) {
                row++;
                text(sheet.createRow(row++), 0, message(kind == BudgetCategoryKind.INCOME ? "budget.export.income" : "budget.export.cost"), bold);
                for (final BudgetCategory category : categories(kind, data.budgetedCategories(lines.keySet()), actual.keySet())) {
                    final List<Line> categoryLines = lines.getOrDefault(category.getId(), List.of());
                    final boolean helpers = data.hasHelpers() && category.getId().equals(data.helpersCategory());
                    final BigDecimal budgeted = sum(categoryLines.stream().map(Line::amount).toList()).add(helpers ? data.helpers() : BigDecimal.ZERO);
                    final BigDecimal categoryActual = actual.getOrDefault(category.getId(), Amounts.zero());

                    text(sheet.createRow(row++), 0, BudgetCategoryDto.of(category).name(locale), bold);
                    for (final Line line : categoryLines) {
                        final Row lineRow = sheet.createRow(row++);
                        final EventDto event = line.eventId() == null ? null : data.events().get(line.eventId());

                        text(lineRow, 0, line.description(), null);
                        lineRow.createCell(1).setCellValue(line.quantity());
                        amountCell(lineRow, 2, line.unitAmount(), amount);
                        amountCell(lineRow, 3, line.amount(), amount);
                        if (event != null) {
                            text(lineRow, 4, event.title(), null);
                        }
                    }
                    if (helpers) {
                        amountRow(sheet.createRow(row++), message("budget.export.helpersFromStaffing"), data.helpers(), 3, amount);
                    }
                    amountRow(sheet.createRow(row++), message("budget.export.subtotal", BudgetCategoryDto.of(category).name(locale)), budgeted, 3, boldAmount);
                    amountRow(sheet.createRow(row++), message("budget.export.actual"), categoryActual, 3, amount);
                    amountRow(sheet.createRow(row++), message("budget.export.difference"), categoryActual.subtract(budgeted), 3, amount);
                }
            }
            if (data.hasHelpers() && data.helpersCategory() == null) {
                amountRow(sheet.createRow(row++), message("budget.export.helpersFromStaffing"), data.helpers(), 3, amount);
            }
            row++;
            final Row result = sheet.createRow(row);

            text(result, 0, message("budget.export.result"), bold);
            amountCell(result, 3, data.totals().budgetedResult(), boldAmount);
            widths(sheet, 5);
        }

        private void transactions() {
            final Sheet sheet = workbook.createSheet(message("budget.export.sheet.transactions"));
            final Map<String, List<Transaction>> transactions = data.transactions().stream().collect(Collectors.groupingBy(Transaction::categoryId));
            int row = 0;

            final Row header = sheet.createRow(row++);

            List.of("budget.export.date", "budget.export.description", "budget.export.amount", "budget.export.paidBy", "budget.export.reimbursed",
                    "budget.export.receipt").forEach(key -> text(header, header.getPhysicalNumberOfCells(), message(key), bold));
            for (final BudgetCategory category : data.categories()) {
                final List<Transaction> categoryTransactions = transactions.getOrDefault(category.getId(), List.of()).stream()
                        .sorted(Comparator.comparing(Transaction::date).thenComparing(Transaction::id)).toList();

                if (categoryTransactions.isEmpty()) {
                    continue;
                }
                row++;
                text(sheet.createRow(row++), 0, BudgetCategoryDto.of(category).name(locale), bold);
                for (final Transaction transaction : categoryTransactions) {
                    final Row transactionRow = sheet.createRow(row++);
                    final String payer = data.payers().get(transaction.id());

                    dateCell(transactionRow, 0, transaction.date());
                    text(transactionRow, 1, transaction.description(), null);
                    amountCell(transactionRow, 2, transaction.amount(), amount);
                    text(transactionRow, 3, payer == null ? "" : payer, null);
                    text(transactionRow, 4, payer == null ? "" : message(transaction.reimbursed() ? "budget.export.yes" : "budget.export.no"), null);
                    text(transactionRow, 5, transaction.receipt() == null ? message("budget.export.none") : transaction.receipt().filename(), null);
                }
                amountRow(sheet.createRow(row++), message("budget.export.subtotal", BudgetCategoryDto.of(category).name(locale)),
                        sum(categoryTransactions.stream().map(Transaction::amount).toList()), 2, boldAmount);
            }
            widths(sheet, 6);
        }

        private void report() {
            final Sheet sheet = workbook.createSheet(message("budget.export.sheet.report"));
            final Map<String, BigDecimal> budgeted = new HashMap<>();
            final Map<String, BigDecimal> actual = actualByCategory();
            final @Nullable Integer participants = data.settings().participantsPlanned();
            int row = 0;

            data.lines().forEach(line -> budgeted.merge(line.categoryId(), line.amount(), BigDecimal::add));
            if (data.hasHelpers() && data.helpersCategory() != null) {
                budgeted.merge(data.helpersCategory(), data.helpers(), BigDecimal::add);
            }
            text(sheet.createRow(row++), 0, data.title(), bold);

            final Row header = sheet.createRow(row++);

            List.of("budget.export.category", "budget.export.budgeted", "budget.export.actual", "budget.export.difference",
                    "budget.export.actualPerParticipant").forEach(key -> text(header, header.getPhysicalNumberOfCells(), message(key), bold));
            for (final BudgetCategoryKind kind : BudgetCategoryKind.values()) {
                row++;
                text(sheet.createRow(row++), 0, message(kind == BudgetCategoryKind.INCOME ? "budget.export.income" : "budget.export.cost"), bold);
                for (final BudgetCategory category : categories(kind, budgeted.keySet(), actual.keySet())) {
                    final BigDecimal categoryBudgeted = Amounts.round(budgeted.getOrDefault(category.getId(), BigDecimal.ZERO));
                    final BigDecimal categoryActual = Amounts.round(actual.getOrDefault(category.getId(), BigDecimal.ZERO));
                    final Row categoryRow = sheet.createRow(row++);

                    text(categoryRow, 0, BudgetCategoryDto.of(category).name(locale), null);
                    amountCell(categoryRow, 1, categoryBudgeted, amount);
                    amountCell(categoryRow, 2, categoryActual, amount);
                    amountCell(categoryRow, 3, categoryActual.subtract(categoryBudgeted), amount);
                    if (participants != null && participants > 0) {
                        amountCell(categoryRow, 4, categoryActual.divide(BigDecimal.valueOf(participants), Amounts.SCALE, RoundingMode.HALF_UP),
                                amount);
                    }
                }
                final Totals totals = data.totals();
                final Row total = sheet.createRow(row++);

                text(total, 0, message("budget.export.total"), bold);
                amountCell(total, 1, kind == BudgetCategoryKind.INCOME ? totals.budgetedIncome() : totals.budgetedCost(), boldAmount);
                amountCell(total, 2, kind == BudgetCategoryKind.INCOME ? totals.actualIncome() : totals.actualCost(), boldAmount);
            }
            row++;
            final Row result = sheet.createRow(row);

            text(result, 0, message("budget.export.result"), bold);
            amountCell(result, 1, data.totals().budgetedResult(), boldAmount);
            amountCell(result, 2, data.totals().actualResult(), boldAmount);
            amountCell(result, 3, data.totals().actualResult().subtract(data.totals().budgetedResult()), boldAmount);
            widths(sheet, 5);
        }

        /**
         * The categories of the kind that hold a line or a transaction, in category order.
         */
        private List<BudgetCategory> categories(final BudgetCategoryKind kind, final Collection<String> withLines, final Collection<String> withActual) {
            return data.categories().stream()
                    .filter(category -> category.getKind() == kind && (withLines.contains(category.getId()) || withActual.contains(category.getId())))
                    .toList();
        }

        private Map<String, BigDecimal> actualByCategory() {
            final Map<String, BigDecimal> actual = new HashMap<>();

            data.transactions().forEach(transaction -> actual.merge(transaction.categoryId(), transaction.amount(), BigDecimal::add));
            return actual;
        }

        private String message(final String key, final Object... arguments) {
            return messageSource.getMessage(key, arguments, locale);
        }

        private int totalsRow(final Sheet sheet, final int row, final String label, final BigDecimal budgeted, final BigDecimal actual,
                              final CellStyle style) {
            final Row totals = sheet.createRow(row);

            text(totals, 0, label, style == boldAmount ? bold : null);
            amountCell(totals, 1, budgeted, style);
            amountCell(totals, 2, actual, style);
            return row + 1;
        }

        private void amountRow(final Row row, final String label, final @Nullable BigDecimal value, final CellStyle style) {
            amountRow(row, label, value, 1, style);
        }

        private void amountRow(final Row row, final String label, final @Nullable BigDecimal value, final int column, final CellStyle style) {
            text(row, 0, label, style == boldAmount ? bold : null);
            if (value != null) {
                amountCell(row, column, value, style);
            }
        }

        private void dateRow(final Row row, final String label, final @Nullable LocalDate value) {
            text(row, 0, label, null);
            if (value != null) {
                dateCell(row, 1, value);
            }
        }

        private void dateCell(final Row row, final int column, final LocalDate value) {
            final Cell cell = row.createCell(column);

            cell.setCellValue(value);
            cell.setCellStyle(date);
        }
    }
}
