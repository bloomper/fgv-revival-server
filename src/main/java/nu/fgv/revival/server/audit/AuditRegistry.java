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
package nu.fgv.revival.server.audit;

import nu.fgv.revival.server.audit.AuditedEntityDescriptor.Format;
import nu.fgv.revival.server.audit.AuditedEntityDescriptor.Formatter;
import nu.fgv.revival.server.budget.BudgetCategoryRepository;
import nu.fgv.revival.server.budget.BudgetLine;
import nu.fgv.revival.server.budget.BudgetLineRepository;
import nu.fgv.revival.server.budget.BudgetTransaction;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.event.EventRepository;
import nu.fgv.revival.server.material.LinkServiceRepository;
import nu.fgv.revival.server.material.Material;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.news.News;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.catalog.CatalogEntry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * One {@link AuditedEntityDescriptor} per {@link AuditedType}: whose history may be read by whom, and which fields
 * are shown how. Ids that point elsewhere are shown as what they point at, in the caller's language.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class AuditRegistry {

    private static final String REVIVAL_ID = "revivalId";

    private final Map<AuditedType, AuditedEntityDescriptor> descriptors = new EnumMap<>(AuditedType.class);
    private final RevivalRepository revivalRepository;
    private final AccessPolicy accessPolicy;
    private final PersonNames personNames;

    public AuditRegistry(final RevivalRepository revivalRepository,
                         final AccessPolicy accessPolicy,
                         final PersonNames personNames,
                         final BudgetCategoryRepository budgetCategoryRepository,
                         final BudgetLineRepository budgetLineRepository,
                         final EventRepository eventRepository,
                         final LinkServiceRepository linkServiceRepository) {
        this.revivalRepository = revivalRepository;
        this.accessPolicy = accessPolicy;
        this.personNames = personNames;

        final Formatter owner = text(this::ownerName);
        final Formatter budgetCategory = text((value, locale) -> budgetCategoryRepository.findById((String) value)
                .map(category -> catalogName(category, locale)).orElse((String) value));
        final Formatter amount = new Formatter(FieldKind.AMOUNT, (value, locale) -> value.toString());
        final Formatter budgetLine = text((value, locale) -> budgetLineRepository.findById((Long) value).map(BudgetLine::getDescription).orElse(null));
        final Formatter event = text((value, locale) -> eventRepository.findById((Long) value).map(Event::getTitle).orElse(null));
        final Formatter linkService = text((value, locale) -> linkServiceRepository.findById((String) value)
                .map(service -> catalogName(service, locale)).orElse((String) value));

        register(AuditedType.LOG_ENTRY, LogEntry.class, revivalRead(accessPolicy::requireReadRevivalWork),
                Set.of(REVIVAL_ID, "number", "origin", "guideItemKey", "checklistItemId", "answersEntryId", "meetingId"),
                Map.of("owner", owner, "eventId", event));
        register(AuditedType.CHECKLIST_ITEM, ChecklistItem.class, revivalRead(accessPolicy::requireReadRevivalWork),
                Set.of(REVIVAL_ID, "checklistId", "position", "required", "guideItemKey", "stateChangedBy", "stateChangedAt", "reasonKey",
                        "reasonArg", "deadlineManual", "relativeDeadline", "templateItemId", "defaultCapability"),
                Map.of("owner", owner));
        register(AuditedType.EVENT, Event.class, revivalRead(accessPolicy::requireReadRevivalWork),
                Set.of(REVIVAL_ID, "typeId"),
                Map.of());
        register(AuditedType.BUDGET_LINE, BudgetLine.class, revivalRead(accessPolicy::requireReadBudget),
                Set.of(REVIVAL_ID, "eventTypeLineId", "logEntryId", "removedBy"),
                Map.of("categoryId", budgetCategory, "unitAmount", amount, "eventId", event));
        register(AuditedType.BUDGET_TRANSACTION, BudgetTransaction.class, revivalRead(accessPolicy::requireReadBudget),
                Set.of(REVIVAL_ID, "receiptContentType", "receiptSize", "reimbursedBy", "cancelledBy"),
                Map.of("categoryId", budgetCategory, "amount", amount, "budgetLineId", budgetLine, "paidBy", owner));
        register(AuditedType.MATERIAL, Material.class, revivalRead(accessPolicy::requireReadAllMaterials),
                // The positions are Spexregister's tasks, whose names the history does not fetch
                Set.of(REVIVAL_ID, "serviceChosen", "positions", "removedBy"),
                Map.of("serviceId", linkService));
        register(AuditedType.NEWS, News.class, (caller, snapshot) -> accessPolicy.requireManageNews(caller),
                Set.of(),
                Map.of());
    }

    private static Formatter text(final Format format) {
        return new Formatter(FieldKind.TEXT, format);
    }

    private static String catalogName(final CatalogEntry entry, final Locale locale) {
        return "en".equals(locale.getLanguage()) && entry.getNameEn() != null ? entry.getNameEn() : entry.getNameSv();
    }

    private @Nullable String ownerName(final Object value, final Locale locale) {
        final Owner owner = (Owner) value;

        if (owner.getPersonId() != null) {
            return personNames.byIds(List.of(owner.getPersonId()), locale).get(owner.getPersonId());
        }
        return owner.getName();
    }

    /**
     * Readable by whoever may read that part of the revival the record belongs to, while they can see the revival.
     */
    private AuditedEntityDescriptor.Access revivalRead(final BiConsumer<Caller, Revival> requirement) {
        return (caller, snapshot) -> {
            final Long revivalId = revivalIdOf(snapshot);
            final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

            requirement.accept(caller, revival);
        };
    }

    private static Long revivalIdOf(final Object snapshot) {
        return switch (snapshot) {
            case final LogEntry entry -> entry.getRevivalId();
            case final ChecklistItem item -> item.getRevivalId();
            case final Event event -> event.getRevivalId();
            case final BudgetLine line -> line.getRevivalId();
            case final BudgetTransaction transaction -> transaction.getRevivalId();
            case final Material material -> material.getRevivalId();
            default -> throw new IllegalArgumentException("Not revival data: " + snapshot.getClass().getName());
        };
    }

    private void register(final AuditedType type, final Class<?> entityClass, final AuditedEntityDescriptor.Access access,
                          final Set<String> hidden, final Map<String, Formatter> formatters) {
        descriptors.put(type, new AuditedEntityDescriptor(type, entityClass, access, hidden, formatters));
    }

    public AuditedEntityDescriptor require(final AuditedType type) {
        return Optional.ofNullable(descriptors.get(type)).orElseThrow(() -> new IllegalStateException("No history for " + type));
    }
}
