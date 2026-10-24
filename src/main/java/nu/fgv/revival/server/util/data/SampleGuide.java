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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


/**
 * A made-up guide in the shape of the real one, published as two versions where the second adds a tip and tightens
 * a requirement. Keys are derived from fixed names, so they stay the same across imports and versions.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
final class SampleGuide {

    private static final String INTRO_SV = """
            Ska ni sätta upp ert gamla spex igen? Den här guiden är till för er i direktionen. Den bygger på \
            erfarenheter från tidigare nyuppsättningar och följer i stort den ordning saker behöver göras i.

            Glöm inte att resan är minst lika viktig som resultatet: rephelgerna och kalasen är det ni minns.""";
    private static final String INTRO_EN = """
            Are you reviving your old spex? This guide is for you in the direction. It builds on earlier revivals and \
            follows roughly the order in which things need to be done.

            Remember that the journey matters as much as the result: the rehearsal weekends and parties are what you \
            will remember.""";

    /**
     * The guide item types the database starts with (migration V3_8), by id.
     */
    static final String TIP = "TIP";
    static final String REQUIREMENT = "REQUIREMENT";
    static final String QUESTION = "QUESTION";
    static final String TEXT = "TEXT";

    static final String CHAPTER_A = "chapter-a";
    static final String CHAPTER_B = "chapter-b";
    static final String CHAPTER_C = "chapter-c";
    static final String CHAPTER_D = "chapter-d";
    static final String CHAPTER_E = "chapter-e";
    static final String CHAPTER_F = "chapter-f";
    static final String CHAPTER_G = "chapter-g";
    static final String CHAPTER_H = "chapter-h";
    static final String CHAPTER_I = "chapter-i";
    static final String CHAPTER_J = "chapter-j";

    private static final List<String> CHAPTERS = List.of(CHAPTER_A, CHAPTER_B, CHAPTER_C, CHAPTER_D, CHAPTER_E, CHAPTER_F, CHAPTER_G,
            CHAPTER_H, CHAPTER_I, CHAPTER_J);

    private SampleGuide() {
    }

    record Version(long id, Instant publishedAt) {
    }

    private record Item(String name, String type, String text) {
    }

    private record Section(String name, String title, List<Item> items) {
    }

    private record Chapter(String name, String titleSv, String titleEn, List<Item> items, List<Section> sections) {
    }

    /**
     * Both versions, oldest first. The first is published long before any sample revival is created.
     */
    static List<Version> insert(final JdbcClient jdbcClient, final long publishedBy, final String createdBy) {
        final Instant first = Instant.now().minus(Duration.ofDays(6 * 365));
        final Instant second = Instant.now().minus(Duration.ofDays(20));
        final long v1 = insertVersion(jdbcClient, "v1", "Första versionen", null, first, publishedBy, createdBy, chapters(false));
        final long v2 = insertVersion(jdbcClient, "v2", "Tips om genrepet och skärpt krav på budgeten", v1, second, publishedBy, createdBy, chapters(true));

        return List.of(new Version(v1, first), new Version(v2, second));
    }

    private static List<Chapter> chapters(final boolean second) {
        final List<Item> rehearsalTips = new ArrayList<>(List.of(
                new Item("f-weekends", TIP, "Tre rephelger som ligger tätare ju närmare premiären det är brukar fungera bra."),
                new Item("f-food", TIP, "Från första rephelgen står nyuppsättningen för mat och en omgång dricka.")
        ));

        if (second) {
            rehearsalTips.add(new Item("f-recording", TIP, "Filma genrepet, det är ovärderligt för de som missade en rephelg."));
        }

        return List.of(
                new Chapter(CHAPTER_A, "Godkännande från JK och FGV", "Approval from JK and FGV",
                        List.of(
                                new Item("a-intro", TEXT, "Innan något annat görs behöver ni klartecken och en ungefärlig tidpunkt, så att nyuppsättningar inte krockar med varandra eller med årets spex."),
                                new Item("a-contact", TIP, "Kontakta FGV:s och JK:s ordförande. Mycket kan skötas via mejl, men gå gärna på något styrelsemöte."),
                                new Item("a-mentor", TIP, "Hitta någon som har gjort en nyuppsättning förut. Hen har kontakter och gamla budgetar."),
                                new Item("a-approval", REQUIREMENT, "Nyuppsättningen och dess ungefärliga tidpunkt ska godkännas av JK och FGV innan lokaler bokas.")
                        ),
                        List.of()),
                new Chapter(CHAPTER_B, "Lokaler och tidslinje", "Venues and timeline",
                        List.of(
                                new Item("b-intro", TEXT, "Lokaler för föreställning och kalas blir ofta bokade ett år i förväg, så boka dem så snart ni har klartecken.")
                        ),
                        List.of(
                                new Section("b-venue", "Föreställningslokal", List.of(
                                        new Item("b-venue-campus", TIP, "Kårhuset är billigt och flexibelt, men kräver vana av ljud och ljus."),
                                        new Item("b-venue-hall", TIP, "En konserthall ger stämning och god akustik, men är dyr och har strikta tider."),
                                        new Item("b-venue-booking", REQUIREMENT, "Lokal för både föreställning och kalas ska bokas ett år i förväg.")
                                )),
                                new Section("b-party", "Kalaslokal", List.of(
                                        new Item("b-party-size", TIP, "Omkring 250 gäster är ett stort kalas och ungefär taket för att det ska förbli roligt."),
                                        new Item("b-party-alcohol", QUESTION, "Har kalaslokalen sprittillstånd, eller får ni ta med egen dryck?")
                                )),
                                new Section("b-timeline", "Tidslinjen", List.of(
                                        new Item("b-timeline-shape", TIP, "En informell träff, en kickoff, tre rephelger och en föreställningshelg."),
                                        new Item("b-timeline-current", QUESTION, "När planerar årets och nästa års spex sina helger?")
                                ))
                        )),
                new Chapter(CHAPTER_C, "Budget", "Budget",
                        List.of(
                                new Item("c-intro", TEXT, "Budgeten styr var ni kan spela och hur stort kalas ni har råd med, så ta fram den tidigt."),
                                new Item("c-buffer", TIP, "Sätt av en buffert och titta i tidigare nyuppsättningars budgetar."),
                                new Item("c-fee", QUESTION, "Hur stor ska deltagaravgiften vara, och ska ni erbjuda resebidrag?"),
                                second
                                        ? new Item("c-budget", REQUIREMENT, "Budget ska upprättas av direktionen och godkännas av JK innan ni drar några kostnader.")
                                        : new Item("c-budget", REQUIREMENT, "Budget ska upprättas av direktionen innan ni drar några kostnader.")
                        ),
                        List.of()),
                new Chapter(CHAPTER_D, "Organisation", "Organisation",
                        List.of(
                                new Item("d-intro", TEXT, "Med lokaler, tidslinje och budget på plats är det dags att bemanna och fördela ansvar.")
                        ),
                        List.of(
                                new Section("d-staffing", "Bemanning", List.of(
                                        new Item("d-staffing-original", TIP, "Räkna inte med att alla vill ha sin gamla post. Flytta om roller och hämta in folk från närliggande årgångar."),
                                        new Item("d-staffing-young", TIP, "Se till att någon i direktionen har kontakt med yngre spexare.")
                                )),
                                new Section("d-bank", "Ekonomi och bank", List.of(
                                        new Item("d-bank-board", REQUIREMENT, "Directeur och casseur ska väljas in i JK:s styrelse för att kunna teckna firma."),
                                        new Item("d-bank-books", REQUIREMENT, "Bokföring ska föras löpande och granskas av FGV:s revisor."),
                                        new Item("d-bank-invoices", QUESTION, "Kan ni beställa via kåren och få kårpris, eller går fakturorna direkt till er?")
                                ))
                        )),
                new Chapter(CHAPTER_E, "Tillgångar", "Assets",
                        List.of(
                                new Item("e-inventory", TIP, "Inventera manus, noter, rekvisita och kostymer i förråden tidigt."),
                                new Item("e-storage", QUESTION, "Vilka förråd får ni använda, och vem har nycklarna?"),
                                new Item("e-return", REQUIREMENT, "Lånat material ska återlämnas i samma skick efter föreställningarna.")
                        ),
                        List.of()),
                new Chapter(CHAPTER_F, "Repetitioner", "Rehearsals", rehearsalTips, List.of()),
                new Chapter(CHAPTER_G, "Marknadsföring och biljetter", "Marketing and tickets",
                        List.of(
                                new Item("g-channels", TIP, "Använd FGV:s nyhetsbrev, hemsidan och spexets sociala medier."),
                                new Item("g-sponsors", TIP, "Var kreativ med spons, tidigare spexare har ofta kontakter."),
                                new Item("g-tickets", QUESTION, "Vilken biljettförsäljning använder ni, och när öppnar den?")
                        ),
                        List.of()),
                new Chapter(CHAPTER_H, "Föreställnings- och kalashelgen", "Show and party weekend",
                        List.of(
                                new Item("h-staffing", TIP, "Kalaset är en lika stor apparat som föreställningen, så bemanna båda."),
                                new Item("h-safety", REQUIREMENT, "Utrymningsvägar och brandregler i lokalen ska gås igenom med alla som jobbar.")
                        ),
                        List.of()),
                new Chapter(CHAPTER_I, "Turné", "Tour",
                        List.of(
                                new Item("i-optional", TEXT, "En turné är frivillig och lika mycket jobb som föreställningshelgen. Det är helt legitimt att tacka nej.")
                        ),
                        List.of()),
                new Chapter(CHAPTER_J, "Avslut", "Closing",
                        List.of(
                                new Item("j-handover", TIP, "Lämna över budget, beslutslogg och erfarenheter till nästa nyuppsättning."),
                                new Item("j-accounts", REQUIREMENT, "Slutredovisningen ska lämnas till FGV inom tre månader efter sista föreställningen.")
                        ),
                        List.of())
        );
    }

    private static long insertVersion(final JdbcClient jdbcClient,
                                      final String label,
                                      final String note,
                                      final @Nullable Long basedOnId,
                                      final Instant publishedAt,
                                      final long publishedBy,
                                      final String createdBy,
                                      final List<Chapter> chapters) {
        final OffsetDateTime at = OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC);
        final long versionId = insert(jdbcClient
                .sql("""
                        INSERT INTO guide_version
                            (status, label, note, intro_sv, intro_en, based_on_id, published_at, published_by, created_by, created_at, version)
                        VALUES
                            ('PUBLISHED', :label, :note, :introSv, :introEn, :basedOnId, :publishedAt, :publishedBy, :createdBy, :publishedAt, 0)
                        """)
                .param("label", label)
                .param("note", note)
                .param("introSv", INTRO_SV)
                .param("introEn", INTRO_EN)
                .param("basedOnId", basedOnId)
                .param("publishedAt", at)
                .param("publishedBy", publishedBy)
                .param("createdBy", createdBy));

        for (int chapterPosition = 0; chapterPosition < chapters.size(); chapterPosition++) {
            final Chapter chapter = chapters.get(chapterPosition);
            final long chapterId = insert(jdbcClient
                    .sql("""
                            INSERT INTO guide_chapter
                                (version_id, `key`, position, title_sv, title_en, created_by, created_at, version)
                            VALUES
                                (:versionId, :key, :position, :titleSv, :titleEn, :createdBy, :createdAt, 0)
                            """)
                    .param("versionId", versionId)
                    .param("key", key(chapter.name()))
                    .param("position", chapterPosition)
                    .param("titleSv", chapter.titleSv())
                    .param("titleEn", chapter.titleEn())
                    .param("createdBy", createdBy)
                    .param("createdAt", at));

            insertItems(jdbcClient, versionId, chapterId, null, chapter.items(), createdBy, at);
            for (int position = 0; position < chapter.sections().size(); position++) {
                final Section section = chapter.sections().get(position);
                final long sectionId = insert(jdbcClient
                        .sql("""
                                INSERT INTO guide_section
                                    (version_id, chapter_id, `key`, position, title_sv, created_by, created_at, version)
                                VALUES
                                    (:versionId, :chapterId, :key, :position, :titleSv, :createdBy, :createdAt, 0)
                                """)
                        .param("versionId", versionId)
                        .param("chapterId", chapterId)
                        .param("key", key(section.name()))
                        .param("position", position)
                        .param("titleSv", section.title())
                        .param("createdBy", createdBy)
                        .param("createdAt", at));

                insertItems(jdbcClient, versionId, chapterId, sectionId, section.items(), createdBy, at);
            }
        }
        return versionId;
    }

    private static void insertItems(final JdbcClient jdbcClient,
                                    final long versionId,
                                    final long chapterId,
                                    final @Nullable Long sectionId,
                                    final List<Item> items,
                                    final String createdBy,
                                    final OffsetDateTime at) {
        for (int position = 0; position < items.size(); position++) {
            final Item item = items.get(position);

            jdbcClient
                    .sql("""
                            INSERT INTO guide_item
                                (version_id, chapter_id, section_id, `key`, position, type, text_sv, created_by, created_at, version)
                            VALUES
                                (:versionId, :chapterId, :sectionId, :key, :position, :type, :textSv, :createdBy, :createdAt, 0)
                            """)
                    .param("versionId", versionId)
                    .param("chapterId", chapterId)
                    .param("sectionId", sectionId)
                    .param("key", key(item.name()))
                    .param("position", position)
                    .param("type", item.type())
                    .param("textSv", item.text())
                    .param("createdBy", createdBy)
                    .param("createdAt", at)
                    .update();
        }
    }

    private static long insert(final JdbcClient.StatementSpec statement) {
        final KeyHolder keyHolder = new GeneratedKeyHolder();

        statement.update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * A sample chapter's place in the guide, counting from 0, by its key.
     */
    static int chapterIndex(final String key) {
        for (int index = 0; index < CHAPTERS.size(); index++) {
            if (key(CHAPTERS.get(index)).equals(key)) {
                return index;
            }
        }
        throw new IllegalArgumentException(key);
    }

    static String key(final String name) {
        return UUID.nameUUIDFromBytes(("sample-guide/" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
