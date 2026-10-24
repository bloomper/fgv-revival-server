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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.event.EventDto;
import nu.fgv.revival.server.event.EventService;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalTitles;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * The budget as an Excel workbook (M5-FR-11): four sheets, labels in the language, amounts with two decimals, receipts
 * per category, and payers by name only.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@ExtendWith(MockitoExtension.class)
class BudgetExportServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2028, 3, 1);

    @Mock
    private BudgetService budgetService;
    @Mock
    private TransactionService transactionService;
    @Mock
    private BudgetCategoryService categoryService;
    @Mock
    private EventService eventService;
    @Mock
    private OwnerViews ownerViews;
    @Mock
    private RevivalTitles titles;
    @Mock
    private CallerService callerService;
    @Mock
    private BudgetAccess access;

    private BudgetExportService service;

    private static BigDecimal kr(final String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private static Workbook open(final BudgetExportService.Export export) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(export.content()));
    }

    private static List<String> column(final Sheet sheet, final int column) {
        final List<String> values = new ArrayList<>();

        for (final Row row : sheet) {
            if (row.getCell(column) != null) {
                values.add(row.getCell(column).toString());
            }
        }
        return values;
    }

    private static Row rowStarting(final Sheet sheet, final String text) {
        for (final Row row : sheet) {
            if (row.getCell(0) != null && text.equals(row.getCell(0).toString())) {
                return row;
            }
        }
        throw new AssertionError("No row " + text);
    }

    @BeforeEach
    void setUp() {
        final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        final Revival revival = TestCallers.revival(RevivalStatus.SETTLING);

        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        service = new BudgetExportService(budgetService, transactionService, categoryService, eventService, ownerViews, titles, callerService, access,
                messages, TestClocks.at(TODAY));
        lenient().when(callerService.current()).thenReturn(TestCallers.observer());
        lenient().when(access.readable(any(), eq(REVIVAL_ID))).thenReturn(revival);
        lenient().when(titles.of(eq(revival), any())).thenReturn("Ali Baba 2028");
        lenient().when(budgetService.settings(REVIVAL_ID)).thenReturn(new BudgetDtos.Settings(60, kr("500"), kr("10000"), LocalDate.of(2027, 9, 1),
                null, 3L));
        lenient().when(budgetService.totals(REVIVAL_ID)).thenReturn(new BudgetDtos.Totals(kr("90000"), kr("6000"), kr("84000"), kr("25200"),
                kr("2350.50"), kr("22849.50"), kr("2350.50"), 1, 1));
        lenient().when(budgetService.lines(REVIVAL_ID, false)).thenReturn(List.of(
                new BudgetDtos.Line(1L, REVIVAL_ID, "SHOW_TICKETS", "Biljetter", 500, kr("180"), kr("90000"), null, null, null, null, false, 0L),
                new BudgetDtos.Line(2L, REVIVAL_ID, "FOOD", "Mat under rephelgen", 40, kr("150"), kr("6000"), 9L, null, 7L, null, false, 0L)));
        lenient().when(transactionService.list(REVIVAL_ID, false)).thenReturn(List.of(
                new BudgetDtos.Transaction(11L, REVIVAL_ID, LocalDate.of(2028, 2, 1), kr("2350.50"), "FOOD", "Mat", null, Owner.member(20L), false,
                        null, null, false, 1L),
                new BudgetDtos.Transaction(12L, REVIVAL_ID, LocalDate.of(2028, 2, 10), kr("25200"), "SHOW_TICKETS", "Biljettförsäljning", null,
                        Owner.none(), false, null, new BudgetDtos.Receipt("nortic.pdf", Receipts.PDF, 1234, Instant.EPOCH), false, 0L)));
        lenient().when(categoryService.all()).thenReturn(TestBudgetCategories.seeded());
        lenient().when(eventService.list(anyLong(), eq(true))).thenReturn(List.of(new EventDto(9L, REVIVAL_ID, 3L, "Rephelg 1",
                LocalDate.of(2027, 12, 11), LocalDate.of(2027, 12, 12), null, null, null, null, true, -61, false, 0L)));
        lenient().when(ownerViews.toDtos(any(), any())).thenAnswer(invocation -> {
            final Map<OwnerViews.Ref, OwnerDto> owners = new HashMap<>();

            for (final OwnerViews.Ref ref : invocation.<Collection<OwnerViews.Ref>>getArgument(0)) {
                if (ref.owner().isSet()) {
                    owners.put(ref, new OwnerDto(new OwnerDto.OwnerPersonDto(20L, "Stina Styrgrupp", true), null));
                }
            }
            return owners;
        });
    }

    @Test
    void theWorkbookHasFourSheetsInTheLanguage() throws IOException {
        final BudgetExportService.Export export = service.export(REVIVAL_ID, Locale.of("sv"));

        assertThat(export.fileName()).isEqualTo("budget-Ali-Baba-2028.xlsx");
        try (Workbook workbook = open(export)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(List.of(workbook.getSheetName(0), workbook.getSheetName(1), workbook.getSheetName(2), workbook.getSheetName(3)))
                    .containsExactly("Sammanfattning", "Budget", "Transaktioner", "Slutrapport");

            final Sheet summary = workbook.getSheetAt(0);

            assertThat(summary.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Ali Baba 2028");
            assertThat(rowStarting(summary, "Planerat antal deltagare").getCell(1).getNumericCellValue()).isEqualTo(60);
            assertThat(rowStarting(summary, "Resultat").getCell(1).getNumericCellValue()).isEqualTo(84000);
            assertThat(rowStarting(summary, "Resultat").getCell(2).getNumericCellValue()).isEqualTo(22849.5);
            assertThat(rowStarting(summary, "Resultat").getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            assertThat(rowStarting(summary, "Lån mottaget").getCell(1).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2027, 9, 1));
            assertThat(rowStarting(summary, "Lån mottaget").getCell(1).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd");
        }
        try (Workbook english = open(service.export(REVIVAL_ID, Locale.ENGLISH))) {
            assertThat(english.getSheetName(3)).isEqualTo("Final report");
        }
    }

    @Test
    void theBudgetSheetListsLinesPerCategoryWithSubtotalsAndActuals() throws IOException {
        try (Workbook workbook = open(service.export(REVIVAL_ID, Locale.ENGLISH))) {
            final Sheet budget = workbook.getSheetAt(1);
            final List<String> labels = column(budget, 0);

            assertThat(labels).containsSubsequence("Income", "Show tickets", "Biljetter", "Total Show tickets", "Actual", "Difference",
                    "Costs", "Food and drink", "Mat under rephelgen", "Total Food and drink", "Actual", "Difference", "Result");
            assertThat(rowStarting(budget, "Mat under rephelgen").getCell(4).getStringCellValue()).isEqualTo("Rephelg 1");
            assertThat(rowStarting(budget, "Total Food and drink").getCell(3).getNumericCellValue()).isEqualTo(6000);
            assertThat(rowStarting(budget, "Result").getCell(3).getNumericCellValue()).isEqualTo(84000);
        }
    }

    @Test
    void transactionsAreListedPerCategoryWithTheirReceiptsAndPayersByName() throws IOException {
        try (Workbook workbook = open(service.export(REVIVAL_ID, Locale.of("sv")))) {
            final Sheet transactions = workbook.getSheetAt(2);

            assertThat(column(transactions, 1)).contains("Mat", "Biljettförsäljning");
            assertThat(column(transactions, 3)).contains("Stina Styrgrupp").noneMatch(value -> value.contains("@"));
            assertThat(column(transactions, 5)).contains("nortic.pdf", "—");
            assertThat(column(transactions, 4)).contains("Nej");
            assertThat(column(transactions, 0)).containsSubsequence("Biljetter föreställning", "Summa Biljetter föreställning", "Mat och dryck",
                    "Summa Mat och dryck");
        }
    }

    @Test
    void theFinalReportHasActualsPerParticipant() throws IOException {
        try (Workbook workbook = open(service.export(REVIVAL_ID, Locale.ENGLISH))) {
            final Sheet report = workbook.getSheetAt(3);
            final Row food = rowStarting(report, "Food and drink");

            assertThat(food.getCell(1).getNumericCellValue()).isEqualTo(6000);
            assertThat(food.getCell(2).getNumericCellValue()).isEqualTo(2350.5);
            assertThat(food.getCell(3).getNumericCellValue()).isEqualTo(-3649.5);
            assertThat(food.getCell(4).getNumericCellValue()).isEqualTo(39.18);
            assertThat(rowStarting(report, "Result").getCell(2).getNumericCellValue()).isEqualTo(22849.5);
        }
    }

    @Test
    void fileNamesAreAscii() {
        assertThat(BudgetExportService.fileName("Ångest på Kårhuset 2029")).isEqualTo("budget-Angest-pa-Karhuset-2029.xlsx");
        assertThat(BudgetExportService.fileName("???")).isEqualTo("budget.xlsx");
    }
}
