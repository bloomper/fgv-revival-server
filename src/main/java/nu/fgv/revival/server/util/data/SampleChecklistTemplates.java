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

import nu.fgv.revival.server.checklist.RelativeDeadlineKind;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import static nu.fgv.revival.server.util.data.SeededCapability.CATERING;
import static nu.fgv.revival.server.util.data.SeededCapability.COMMS;
import static nu.fgv.revival.server.util.data.SeededCapability.FINANCE;
import static nu.fgv.revival.server.util.data.SeededCapability.STAFFING;

/**
 * Chapter templates, whose items the guide sync copies into new chapter checklists, and event templates for rehearsal
 * and show weekends.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
final class SampleChecklistTemplates {

    static final String REHEARSAL_WEEKEND = "Rephelg";
    static final String SHOW_WEEKEND = "Föreställningshelg";

    private SampleChecklistTemplates() {
    }

    static void insert(final JdbcClient jdbcClient, final String createdBy) {
        final OffsetDateTime at = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);

        insertTemplate(jdbcClient, SampleGuide.key(SampleGuide.CHAPTER_A), null, null, createdBy, at, List.of(
                new Item("Boka ett första möte med JK", "Book a first meeting with JK", null, SampleGuide.key("a-contact")),
                new Item("Fråga originalensemblen vilka som vill vara med", "Ask the original cast who wants to take part", STAFFING, null)
        ));
        insertTemplate(jdbcClient, SampleGuide.key(SampleGuide.CHAPTER_B), null, null, createdBy, at, List.of(
                new Item("Begär offert från föreställningslokalen", "Ask the show venue for a quote", FINANCE, SampleGuide.key("b-venue-hall")),
                new Item("Kolla vilka datum kalaslokalen har lediga", "Check which dates the party venue has free", CATERING, null)
        ));
        insertTemplate(jdbcClient, SampleGuide.key(SampleGuide.CHAPTER_C), null, null, createdBy, at, List.of(
                new Item("Hämta budgetar från tidigare nyuppsättningar", "Get the budgets of earlier revivals", FINANCE, SampleGuide.key("c-buffer"))
        ));
        insertTemplate(jdbcClient, SampleGuide.key(SampleGuide.CHAPTER_D), null, null, createdBy, at, List.of(
                new Item("Starta en gemensam Drive för direktionen", "Start a shared Drive for the direction", null, null),
                new Item("Sammanställ en kontaktlista över alla som är med", "Put together a contact list of everyone taking part", STAFFING, null)
        ));
        insertTemplate(jdbcClient, SampleGuide.key(SampleGuide.CHAPTER_G), null, null, createdBy, at, List.of(
                new Item("Beställ affischer", "Order posters", COMMS, null),
                new Item("Skriv ett utskick till FGV:s nyhetsbrev", "Write a piece for FGV's newsletter", COMMS, SampleGuide.key("g-channels"))
        ));
        insertTemplate(jdbcClient, SampleGuide.key(SampleGuide.CHAPTER_H), null, null, createdBy, at, List.of(
                new Item("Gör ett bemanningsschema för kalaset", "Make a staffing schedule for the party", CATERING, SampleGuide.key("h-staffing"))
        ));
        insertTemplate(jdbcClient, SampleGuide.key(SampleGuide.CHAPTER_J), null, null, createdBy, at, List.of(
                new Item("Skicka slutredovisningen till FGV", "Send the final accounts to FGV", FINANCE, SampleGuide.key("j-accounts"))
        ));
        insertTemplate(jdbcClient, null, REHEARSAL_WEEKEND, "Rehearsal weekend", createdBy, at, List.of(
                Item.days("Boka replokal", "Book a rehearsal room", STAFFING, -30),
                Item.weekBefore("Skicka ut schemat för helgen", "Send out the schedule for the weekend", COMMS, DayOfWeek.MONDAY),
                Item.workingDays("Beställ mat", "Order food", CATERING, -5),
                Item.days("Städa och lämna tillbaka lokalen", "Clean and hand back the room", null, 1)
        ));
        insertTemplate(jdbcClient, null, SHOW_WEEKEND, "Show weekend", createdBy, at, List.of(
                Item.days("Bekräfta teknikerna", "Confirm the technicians", STAFFING, -14),
                Item.days("Ordna växelkassa till kalaset", "Arrange a cash float for the party", FINANCE, -7),
                Item.days("Skriv ut biljettlistorna", "Print the ticket lists", COMMS, -1)
        ));
    }

    private static void insertTemplate(final JdbcClient jdbcClient,
                                       final @Nullable String chapterKey,
                                       final @Nullable String nameSv,
                                       final @Nullable String nameEn,
                                       final String createdBy,
                                       final OffsetDateTime at,
                                       final List<Item> items) {
        final KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcClient
                .sql("""
                        INSERT INTO checklist_template
                            (kind, chapter_key, name_sv, name_en, created_by, created_at, version)
                        VALUES
                            (:kind, :chapterKey, :nameSv, :nameEn, :createdBy, :createdAt, 0)
                        """)
                .param("kind", chapterKey == null ? "EVENT" : "CHAPTER")
                .param("chapterKey", chapterKey)
                .param("nameSv", nameSv)
                .param("nameEn", nameEn)
                .param("createdBy", createdBy)
                .param("createdAt", at)
                .update(keyHolder);

        final long templateId = Objects.requireNonNull(keyHolder.getKey()).longValue();

        for (int position = 0; position < items.size(); position++) {
            final Item item = items.get(position);

            jdbcClient
                    .sql("""
                            INSERT INTO checklist_template_item
                                (template_id, position, text_sv, text_en, guide_item_key, default_capability, deadline_kind,
                                 deadline_amount, deadline_weekday, created_by, created_at, version)
                            VALUES
                                (:templateId, :position, :textSv, :textEn, :guideItemKey, :capability, :deadlineKind,
                                 :deadlineAmount, :deadlineWeekday, :createdBy, :createdAt, 0)
                            """)
                    .param("templateId", templateId)
                    .param("position", position)
                    .param("textSv", item.textSv())
                    .param("textEn", item.textEn())
                    .param("guideItemKey", item.guideItem())
                    .param("capability", item.capability() == null ? null : item.capability().name())
                    .param("deadlineKind", item.deadlineKind() == null ? null : item.deadlineKind().name())
                    .param("deadlineAmount", item.deadlineAmount())
                    .param("deadlineWeekday", item.deadlineWeekday() == null ? null : item.deadlineWeekday().name())
                    .param("createdBy", createdBy)
                    .param("createdAt", at)
                    .update();
        }
    }

    private record Item(String textSv, String textEn, @Nullable SeededCapability capability, @Nullable String guideItem,
                        @Nullable RelativeDeadlineKind deadlineKind, @Nullable Integer deadlineAmount,
                        @Nullable DayOfWeek deadlineWeekday) {

        Item(final String textSv, final String textEn, final @Nullable SeededCapability capability, final @Nullable String guideItem) {
            this(textSv, textEn, capability, guideItem, null, null, null);
        }

        static Item days(final String textSv, final String textEn, final @Nullable SeededCapability capability, final int amount) {
            return new Item(textSv, textEn, capability, null, RelativeDeadlineKind.DAYS, amount, null);
        }

        static Item workingDays(final String textSv, final String textEn, final @Nullable SeededCapability capability, final int amount) {
            return new Item(textSv, textEn, capability, null, RelativeDeadlineKind.WORKING_DAYS, amount, null);
        }

        static Item weekBefore(final String textSv, final String textEn, final @Nullable SeededCapability capability, final DayOfWeek weekday) {
            return new Item(textSv, textEn, capability, null, RelativeDeadlineKind.WEEKDAY_WEEK_BEFORE, null, weekday);
        }
    }
}
