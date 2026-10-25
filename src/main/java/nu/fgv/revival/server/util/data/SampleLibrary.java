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

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * The reference library, with guide items in every version linked to its entries, and the application settings: the
 * terms every participant accepts and the fee new revivals start with.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
final class SampleLibrary {

    private static final List<Entry> ENTRIES = List.of(
            new Entry("VENUE", "RunAn", "Kårhusets stora sal med fast scen och läktare för 600.",
                    "Stor scen och bra ljud. Nära kalaslokalerna i Kårhuset.", "Bokas tidigt av Chalmersspexet; kolla krockar.",
                    "Via Chalmers Studentkår, lokalbokningen.", "2024: 18 000 kr för en helg med generalrepetition.", null,
                    "https://chalmersstudentkar.se", "%lokal%", false),
            new Entry("VENUE", "Lindholmen Conference Centre", "Konferensanläggning på Lindholmen med en aula för 400.",
                    "Modern teknik och personal på plats.", "Dyrt, och kalaset måste hållas någon annanstans.",
                    "Direkt hos anläggningen.", "2022: 32 000 kr för två föreställningar.", null, null, "%Föreställningslokal%", false),
            new Entry("VENUE", "Gamla gymnastiksalen", "Gymnastiksal som användes som kalaslokal förr.", null, "Riven 2023.",
                    null, null, null, null, null, true),
            new Entry("TICKET_SERVICE", "Tickster", "Biljettsläpp med QR-koder och avgift per biljett.",
                    "Enkelt att sätta upp, Swish och kort.", "Avgift på varje biljett.", null, "2026: 9 kr per biljett.", null,
                    "https://www.tickster.com", "%biljett%", false),
            new Entry("ROLE_DESCRIPTION", "Casseur", "Sköter nyuppsättningens ekonomi: budget, betalningar, kvitton och bokslut. "
                    + "Väljs in i JK:s styrelse tillsammans med directeuren.", null, null, null, null, null, null, "%casseur%", false),
            new Entry("CONTACT_ROLE", "Chalmersspexets directeur", "Årets directeur för Chalmersspexet, för datum, kortafton och lokaler.",
                    null, null, null, null, "directeur@chalmersspexet.example", null, null, false));

    private SampleLibrary() {
    }

    static void insert(final JdbcClient jdbcClient, final String createdBy) {
        final LocalDate written = LocalDate.now().minusDays(30);

        for (final Entry entry : ENTRIES) {
            final KeyHolder keyHolder = new GeneratedKeyHolder();

            jdbcClient
                    .sql("""
                            INSERT INTO library_entry
                                (kind, name_sv, description_sv, pros_sv, cons_sv, booking_sv, prices_sv, contact_sv, url, retired_at,
                                 created_by, created_at, version)
                            VALUES
                                (:kind, :name, :description, :pros, :cons, :booking, :prices, :contact, :url, :retiredAt,
                                 :createdBy, :createdAt, 0)
                            """)
                    .param("kind", entry.kind())
                    .param("name", entry.name())
                    .param("description", entry.description())
                    .param("pros", entry.pros())
                    .param("cons", entry.cons())
                    .param("booking", entry.booking())
                    .param("prices", entry.prices())
                    .param("contact", entry.contact())
                    .param("url", entry.url())
                    .param("retiredAt", entry.retired() ? written.plusDays(10).atTime(9, 0).atOffset(ZoneOffset.UTC) : null)
                    .param("createdBy", createdBy)
                    .param("createdAt", written.atTime(9, 0).atOffset(ZoneOffset.UTC))
                    .update(keyHolder);
            if (entry.linkedItems() != null) {
                jdbcClient
                        .sql("""
                                INSERT INTO guide_item_library_entry (item_id, library_entry_id)
                                SELECT DISTINCT i.id, :entryId FROM guide_item i
                                LEFT JOIN guide_section s ON s.id = i.section_id
                                WHERE i.text_sv LIKE :pattern OR s.title_sv LIKE :pattern
                                """)
                        .param("entryId", Objects.requireNonNull(keyHolder.getKey()).longValue())
                        .param("pattern", entry.linkedItems())
                        .update();
            }
        }
        jdbcClient
                .sql("UPDATE application_settings SET terms_sv = :terms, default_participation_fee = :fee WHERE id = 1")
                .param("terms", "Jag följer Chalmers och FGV:s regler för alkohol och uppförande på repetitioner och kalas.")
                .param("fee", new BigDecimal("800.00"))
                .update();
    }

    /**
     * A library entry and, as a LIKE pattern, the guide items (by text or section title) that link to it.
     */
    private record Entry(String kind, String name, String description, @Nullable String pros, @Nullable String cons,
                         @Nullable String booking, @Nullable String prices, @Nullable String contact, @Nullable String url,
                         @Nullable String linkedItems, boolean retired) {
    }
}
