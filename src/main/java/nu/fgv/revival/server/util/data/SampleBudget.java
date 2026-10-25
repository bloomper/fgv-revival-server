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
package nu.fgv.revival.server.util.data;

import nu.fgv.revival.server.budget.BudgetLine;
import nu.fgv.revival.server.budget.BudgetLineQuantity;
import nu.fgv.revival.server.budget.BudgetLineRepository;
import nu.fgv.revival.server.budget.BudgetScenario;
import nu.fgv.revival.server.budget.BudgetScenarioRepository;
import nu.fgv.revival.server.budget.BudgetSettings;
import nu.fgv.revival.server.budget.BudgetSettingsRepository;
import nu.fgv.revival.server.budget.BudgetTransaction;
import nu.fgv.revival.server.budget.BudgetTransactionRepository;
import nu.fgv.revival.server.budget.EventTypeBudgetLine;
import nu.fgv.revival.server.budget.EventTypeBudgetLineRepository;
import nu.fgv.revival.server.budget.ReceiptContent;
import nu.fgv.revival.server.budget.ReceiptContentRepository;
import nu.fgv.revival.server.budget.Receipts;
import nu.fgv.revival.server.budget.ReferenceFigure;
import nu.fgv.revival.server.budget.ReferenceRevival;
import nu.fgv.revival.server.budget.ReferenceRevivalRepository;
import nu.fgv.revival.server.budget.ScenarioParameters;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.decisionlog.LogEntryRepository;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.event.EventType;
import nu.fgv.revival.server.event.EventTypeRepository;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.revival.RevivalStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sample budgets: the rehearsal weekends propose food per participant, one fictitious earlier revival gives
 * reference figures, and each sample revival with a premiere gets settings, a dozen lines (one removed), a few
 * transactions (one cost paid by a member and not yet reimbursed, one with a receipt, one cancelled) and a scenario.
 * Once the revival has settled, its loan is repaid. All amounts are made up.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
class SampleBudget {

    private final EventTypeRepository eventTypeRepository;
    private final EventTypeBudgetLineRepository typeLineRepository;
    private final ReferenceRevivalRepository referenceRepository;
    private final BudgetSettingsRepository settingsRepository;
    private final BudgetLineRepository lineRepository;
    private final BudgetTransactionRepository transactionRepository;
    private final BudgetScenarioRepository scenarioRepository;
    private final EventRepository eventRepository;
    private final LogEntryRepository logEntryRepository;
    private final ReceiptContentRepository receiptRepository;

    SampleBudget(final EventTypeRepository eventTypeRepository,
                 final EventTypeBudgetLineRepository typeLineRepository,
                 final ReferenceRevivalRepository referenceRepository,
                 final BudgetSettingsRepository settingsRepository,
                 final BudgetLineRepository lineRepository,
                 final BudgetTransactionRepository transactionRepository,
                 final BudgetScenarioRepository scenarioRepository,
                 final EventRepository eventRepository,
                 final LogEntryRepository logEntryRepository,
                 final ReceiptContentRepository receiptRepository) {
        this.eventTypeRepository = eventTypeRepository;
        this.typeLineRepository = typeLineRepository;
        this.referenceRepository = referenceRepository;
        this.settingsRepository = settingsRepository;
        this.lineRepository = lineRepository;
        this.transactionRepository = transactionRepository;
        this.scenarioRepository = scenarioRepository;
        this.eventRepository = eventRepository;
        this.logEntryRepository = logEntryRepository;
        this.receiptRepository = receiptRepository;
    }

    private static void figure(final ReferenceRevival reference, final String category, final String budgeted, final String actual) {
        final ReferenceFigure figure = new ReferenceFigure(reference, category);

        figure.setBudgeted(new BigDecimal(budgeted).setScale(2));
        figure.setActual(new BigDecimal(actual).setScale(2));
        reference.getFigures().add(figure);
    }

    /**
     * A one-page PDF saying what the receipt is for.
     */
    static byte[] receiptPdf(final String description, final BigDecimal amount) {
        final String text = "BT /F1 18 Tf 72 760 Td (Exempelkvitto) Tj 0 -28 Td /F1 12 Tf (%s) Tj 0 -18 Td (%s kr) Tj ET"
                .formatted(description.replaceAll("[^A-Za-z0-9 ,.-]", "?"), amount.toPlainString());
        final List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length %d >>\nstream\n%s\nendstream".formatted(text.length(), text),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        final ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        final List<Integer> offsets = new ArrayList<>();

        pdf.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(pdf.size());
            pdf.writeBytes("%d 0 obj\n%s\nendobj\n".formatted(index + 1, objects.get(index)).getBytes(StandardCharsets.US_ASCII));
        }

        final int xref = pdf.size();
        final StringBuilder trailer = new StringBuilder("xref\n0 %d\n0000000000 65535 f \n".formatted(objects.size() + 1));

        offsets.forEach(offset -> trailer.append("%010d 00000 n \n".formatted(offset)));
        trailer.append("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n".formatted(objects.size() + 1, xref));
        pdf.writeBytes(trailer.toString().getBytes(StandardCharsets.US_ASCII));
        return pdf.toByteArray();
    }

    /**
     * Food per participant on each rehearsal weekend type that proposes nothing yet, and the reference revival.
     */
    void insertCatalog() {
        for (final EventType type : eventTypeRepository.findAllByOrderByPositionAscIdAsc()) {
            if (type.getNameSv().startsWith(SampleChecklistTemplates.REHEARSAL_WEEKEND)
                    && typeLineRepository.findByEventTypeIdOrderByPositionAscIdAsc(type.getId()).isEmpty()) {
                final EventTypeBudgetLine line = new EventTypeBudgetLine();

                line.setEventTypeId(type.getId());
                line.setCategoryId("FOOD");
                line.setDescriptionSv("Mat under rephelgen");
                line.setDescriptionEn("Food during the rehearsal weekend");
                line.setQuantityRule(BudgetLineQuantity.PER_PARTICIPANT);
                line.setUnitAmount(new BigDecimal("150.00"));
                line.setPosition(0);
                typeLineRepository.saveAndFlush(line);
            }
        }
        if (referenceRepository.findAllByOrderByYearDescLabelAscIdAsc().isEmpty()) {
            final ReferenceRevival reference = new ReferenceRevival();

            reference.setLabel("Exempelspexet 2024");
            reference.setYear(2024);
            reference.setParticipants(58);
            reference.setComment("Påhittade siffror som visar hur referenser fungerar.");
            figure(reference, "SHOW_TICKETS", "81000", "86400");
            figure(reference, "PARTY_TICKETS", "37500", "33250");
            figure(reference, "FEES", "30000", "29500");
            figure(reference, "VENUE", "43500", "45100");
            figure(reference, "FOOD", "21600", "24310");
            figure(reference, "PARTY", "30000", "34800");
            figure(reference, "TECHNICAL", "18000", "16250");
            figure(reference, "HELPERS", "4800", "4800");
            referenceRepository.saveAndFlush(reference);
        }
    }

    /**
     * A revival's sample budget. {@code payer} pays a cost out of their own pocket and waits for reimbursement.
     */
    void insert(final long revivalId, final RevivalStatus status, final LocalDate premiere, final long payer, final long directeur, final LocalDate today) {
        final BudgetSettings settings = settingsRepository.findByRevivalId(revivalId).orElseGet(() -> new BudgetSettings(revivalId));

        settings.setParticipantsPlanned(60);
        settings.setParticipationFee(new BigDecimal("500.00"));
        settings.setLoanAmount(new BigDecimal("10000.00"));
        settings.setLoanReceivedOn(premiere.minusDays(120));
        settings.setLoanRepaidOn(status == RevivalStatus.SETTLING || status == RevivalStatus.ARCHIVED ? premiere.plusDays(30) : null);
        settingsRepository.saveAndFlush(settings);

        final List<Event> events = eventRepository.findByRevivalId(revivalId);
        final Optional<Event> rehearsal = events.stream()
                .filter(event -> event.getTitle().startsWith(SampleChecklistTemplates.REHEARSAL_WEEKEND) && event.getTypeId() != null).findFirst();
        final Optional<Event> show = events.stream().filter(event -> event.getTitle().equals(SampleChecklistTemplates.SHOW_WEEKEND)).findFirst();
        final @Nullable Long decision = logEntryRepository.findByRevivalIdOrderByNumberAsc(revivalId).stream()
                .filter(entry -> entry.getType() == LogEntryType.DECISION).map(LogEntry::getId).findFirst().orElse(null);

        line(revivalId, "SHOW_TICKETS", "Biljetter, tre föreställningar", "450", "180", show.map(Event::getId).orElse(null), null, null);
        line(revivalId, "PARTY_TICKETS", "Biljetter till lördagskalaset", "150", "250", show.map(Event::getId).orElse(null), null, null);
        line(revivalId, "FEES", "Spexpengar", "60", "500", null, null, null);
        line(revivalId, "SPONSORSHIP", "Sponsring från lokalt företag", "1", "15000", null, null, null);
        line(revivalId, "VENUE", "Föreställningslokal, tre dagar", "3", "12000", show.map(Event::getId).orElse(null), null, null);
        line(revivalId, "VENUE", "Replokaler", "3", "2500", null, null, null);
        rehearsal.ifPresent(event -> typeLineRepository.findByEventTypeIdOrderByPositionAscIdAsc(event.getTypeId()).stream().findFirst()
                .ifPresent(typeLine -> line(revivalId, typeLine.getCategoryId(), typeLine.getDescriptionSv(), "60", typeLine.getUnitAmount().toPlainString(),
                        event.getId(), null, typeLine.getId())));
        line(revivalId, "PARTY", "Kalaslokal och mat", "1", "30000", null, null, null);
        line(revivalId, "TECHNICAL", "Ljud och ljus", "1", "18000", null, null, null);
        line(revivalId, "COSTUMES", "Kostymer och smink", "1", "8000", null, null, null);
        final BudgetLine marketing = line(revivalId, "MARKETING", "Affischer och annonser", "1", "3000", null, null, null);

        marketing.setComment("Annonserna i Göteborgs-Posten ingår inte");
        lineRepository.saveAndFlush(marketing);

        final BudgetLine bus = line(revivalId, "TECHNICAL", "Buss till turnén", "1", "6500", null, null, null);

        bus.setRemovedAt(Instant.now().minus(Duration.ofDays(30)));
        bus.setRemovedBy(directeur);
        lineRepository.saveAndFlush(bus);

        final BudgetLine helpers = line(revivalId, "HELPERS", "Fyra springare", "4", "1200", null, decision, null);

        final BudgetTransaction deposit = transaction(revivalId, today.minusDays(40), "12000.00", "VENUE", "Handpenning föreställningslokal", null,
                Owner.none(), false);
        final BudgetTransaction food = transaction(revivalId, today.minusDays(20), "2350.50", "FOOD", "Mat till rephelgen", null, Owner.member(payer),
                false);

        receipt(deposit, "kvitto-handpenning.pdf");
        receipt(food, "kvitto-mat.pdf");
        transaction(revivalId, today.minusDays(15), "1450.00", "PRINTING", "Affischtryck", null, Owner.named("Tryckeriet"), true);
        transaction(revivalId, today.minusDays(10), "25200.00", "SHOW_TICKETS", "Biljettförsäljning, första veckan", null, Owner.none(), false);
        transaction(revivalId, today.minusDays(5), "1200.00", "HELPERS", "Springare, första dagen", helpers.getId(), Owner.none(), false);

        final BudgetTransaction twice = transaction(revivalId, today.minusDays(9), "1450.00", "PRINTING", "Affischtryck, bokfört två gånger", null, Owner.none(),
                false);

        twice.setCancelledAt(Instant.now().minus(Duration.ofDays(8)));
        twice.setCancelledBy(payer);
        transactionRepository.saveAndFlush(twice);

        final BudgetScenario scenario = new BudgetScenario();

        scenario.setRevivalId(revivalId);
        scenario.setName("Fler biljetter, dyrare kalas");
        scenario.setParameters(new ScenarioParameters(null, null, null, 550, new BigDecimal("280.00"), null, null, null));
        scenarioRepository.saveAndFlush(scenario);
    }

    private BudgetLine line(final long revivalId, final String category, final String description, final String quantity, final String unitAmount,
                            final @Nullable Long eventId, final @Nullable Long logEntryId, final @Nullable Long eventTypeLineId) {
        final BudgetLine line = new BudgetLine();

        line.setRevivalId(revivalId);
        line.setCategoryId(category);
        line.setDescription(description);
        line.setQuantity(new BigDecimal(quantity).setScale(2));
        line.setUnitAmount(new BigDecimal(unitAmount).setScale(2));
        line.setEventId(eventId);
        line.setLogEntryId(logEntryId);
        line.setEventTypeLineId(eventTypeLineId);
        return lineRepository.saveAndFlush(line);
    }

    private BudgetTransaction transaction(final long revivalId, final LocalDate date, final String amount, final String category, final String description,
                                          final @Nullable Long budgetLineId, final Owner paidBy, final boolean reimbursed) {
        final BudgetTransaction transaction = new BudgetTransaction();

        transaction.setRevivalId(revivalId);
        transaction.setDate(date);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setCategoryId(category);
        transaction.setDescription(description);
        transaction.setBudgetLineId(budgetLineId);
        transaction.setPaidBy(paidBy);
        transaction.setReimbursedAt(reimbursed ? Instant.now() : null);
        return transactionRepository.saveAndFlush(transaction);
    }

    private void receipt(final BudgetTransaction transaction, final String filename) {
        transaction.setReceiptFilename(filename);
        transaction.setReceiptContentType(Receipts.PDF);
        transactionRepository.saveAndFlush(transaction);
        receiptRepository.save(new ReceiptContent(transaction.getId(), receiptPdf(transaction.getDescription(), transaction.getAmount())));
    }
}
