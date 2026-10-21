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

package nu.fgv.revival.server.guide;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.guide.GuideDocxParser.ParsedChapter;
import nu.fgv.revival.server.guide.GuideDocxParser.ParsedGuide;
import nu.fgv.revival.server.guide.GuideDocxParser.ParsedItem;
import nu.fgv.revival.server.guide.GuideImportReportDto.Warning;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static nu.fgv.revival.server.guide.GuideDocxFixtures.bold;
import static nu.fgv.revival.server.guide.GuideDocxFixtures.boldItalic;
import static nu.fgv.revival.server.guide.GuideDocxFixtures.document;
import static nu.fgv.revival.server.guide.GuideDocxFixtures.italic;
import static nu.fgv.revival.server.guide.GuideDocxFixtures.link;
import static nu.fgv.revival.server.guide.GuideDocxFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The import against fictitious documents in guide v5's layout (research R6, contracts/import.md). No real document is
 * used (constitution V).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
class GuideImportServiceTest {

    private final GuideDocxParser parser = new GuideDocxParser(new GuideMarkdown());

    private static Stream<ParsedItem> allItems(final ParsedChapter chapter) {
        return Stream.concat(chapter.items().stream(), chapter.sections().stream().flatMap(section -> section.items().stream()));
    }

    private static long count(final ParsedGuide guide, final GuideItemType type) {
        return guide.chapters().stream().flatMap(GuideImportServiceTest::allItems).filter(item -> item.type() == type).count();
    }

    /**
     * Chapters A–J with one text paragraph each, plus whatever {@code extra} adds to chapter A.
     */
    private static GuideDocxFixtures minimal(final java.util.function.Consumer<GuideDocxFixtures> extra) {
        final GuideDocxFixtures doc = document().heading1("A. Först");

        extra.accept(doc);
        for (final Phase phase : Phase.values()) {
            if (phase != Phase.A) {
                doc.heading1(phase.name() + ". Kapitel " + phase.name()).paragraph("Text i " + phase.name() + ".");
            }
        }
        return doc;
    }

    @Nested
    class Structure {

        @Test
        void v5LayoutGivesTenChaptersWithTitlesWithoutTheLetter() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());

            assertThat(guide.chapters()).extracting(ParsedChapter::phase).containsExactly(Phase.values());
            assertThat(guide.chapters().getFirst().title()).isEqualTo("Först: godkännande och tajmning");
            assertThat(guide.chapters().get(8).title()).isEqualTo("Eventuell turné (frivilligt)");
        }

        @Test
        void sectionsLandUnderTheirChapter() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());

            assertThat(guide.chapters().get(1).sections()).extracting(GuideDocxParser.ParsedSection::title)
                    .containsExactly("Kalaslokal", "Tidslinjen");
            assertThat(guide.chapters().stream().mapToInt(chapter -> chapter.sections().size()).sum()).isEqualTo(4);
        }

        @Test
        void itemsBeforeTheFirstSectionBelongToTheChapter() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());
            final ParsedChapter a = guide.chapters().getFirst();

            assertThat(a.sections()).isEmpty();
            assertThat(a.items()).extracting(ParsedItem::type)
                    .containsExactly(GuideItemType.TEXT, GuideItemType.TIP, GuideItemType.TIP, GuideItemType.REQUIREMENT);
        }

        @Test
        void paragraphsBeforeChapterABecomeTheIntroduction() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());

            assertThat(guide.introduction()).isEqualTo(
                    "Kontinuitetsguide: Nyuppsättning av ett Chalmersspex\n\nNi funderar alltså över nyuppsättning av ert gamla spex? Grattis\\!");
        }

        @Test
        void countsMatchTheDocument() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());

            assertThat(count(guide, GuideItemType.TIP)).isEqualTo(6);
            assertThat(count(guide, GuideItemType.REQUIREMENT)).isEqualTo(3);
            assertThat(count(guide, GuideItemType.QUESTION)).isEqualTo(1);
            assertThat(count(guide, GuideItemType.TEXT)).isEqualTo(GuideDocxFixtures.V5_LAYOUT_TEXTS);
        }

        @Test
        void headingsAreRecognisedByOutlineLevelToo() {
            final GuideDocxFixtures doc = document().outlineHeading1("A. Först").paragraph("Text.");

            for (final Phase phase : Phase.values()) {
                if (phase != Phase.A) {
                    doc.heading1(phase.name() + ". Kapitel").paragraph("Text.");
                }
            }
            assertThat(parser.parse(doc.bytes()).chapters()).hasSize(10);
        }
    }

    @Nested
    class Labels {

        @Test
        void knownLabelsTypeTheListAndAreNotImported() {
            final ParsedGuide guide = parser.parse(minimal(doc -> doc
                    .paragraph("praktiska tips:").listItem("Ett tips.")
                    .paragraph("KRAV").listItem("Ett krav.")
                    .paragraph("Att ta reda på").listItem("En fråga.")).bytes());

            assertThat(guide.chapters().getFirst().items()).extracting(ParsedItem::type, ParsedItem::text).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(GuideItemType.TIP, "Ett tips."),
                    org.assertj.core.groups.Tuple.tuple(GuideItemType.REQUIREMENT, "Ett krav."),
                    org.assertj.core.groups.Tuple.tuple(GuideItemType.QUESTION, "En fråga."));
        }

        @Test
        void unknownLabelsBecomeTextWithAWarning() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());

            assertThat(guide.warnings()).filteredOn(warning -> warning.code() == GuideImportWarningCode.UNKNOWN_LABEL)
                    .containsExactly(
                            new Warning(GuideImportWarningCode.UNKNOWN_LABEL, Phase.B, "Kalaslokal", "Om kalaset"),
                            new Warning(GuideImportWarningCode.UNKNOWN_LABEL, Phase.D, "Andra direktionsmötet", "Checklista för mötet"));
            assertThat(guide.chapters().get(1).sections().getFirst().items()).extracting(ParsedItem::type, ParsedItem::text).startsWith(
                    org.assertj.core.groups.Tuple.tuple(GuideItemType.TEXT, "Om kalaset"),
                    org.assertj.core.groups.Tuple.tuple(GuideItemType.TEXT, "250 personer är ett stort kalas."));
        }

        @Test
        void aShortParagraphFollowedByAPictureIsText() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());

            assertThat(guide.chapters().get(1).sections().get(1).items()).extracting(ParsedItem::type, ParsedItem::text).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(GuideItemType.TEXT, "Exempel på tidplan för nyuppsättning"),
                    org.assertj.core.groups.Tuple.tuple(GuideItemType.QUESTION, "Vilka datum har sittande spex?"));
        }

        @Test
        void aLabelEndsAtTheNextPlainParagraph() {
            final ParsedGuide guide = parser.parse(minimal(doc -> doc
                    .paragraph("Praktiska tips").listItem("Ett tips.")
                    .paragraph("Ett vanligt stycke som inte är en etikett.")
                    .listItem("En punkt utan etikett.")).bytes());

            assertThat(guide.chapters().getFirst().items()).extracting(ParsedItem::type)
                    .containsExactly(GuideItemType.TIP, GuideItemType.TEXT, GuideItemType.TEXT);
        }
    }

    @Nested
    class Inline {

        @Test
        void boldItalicAndLinksBecomeMarkdown() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());

            assertThat(guide.chapters().getFirst().items().get(2).text())
                    .isEqualTo("Förankra idén **brett** och *tidigt*, se [fgv.nu](https://fgv.example.org).");
        }

        @Test
        void boldItalicTogether() {
            final ParsedGuide guide = parser.parse(minimal(doc -> doc.paragraph(p -> {
                run(p, "Viktigt: ");
                boldItalic(p, "boka i tid");
            })).bytes());

            assertThat(guide.chapters().getFirst().items().getFirst().text()).isEqualTo("Viktigt: ***boka i tid***");
        }

        @Test
        void spacesStayOutsideEmphasis() {
            final ParsedGuide guide = parser.parse(minimal(doc -> doc.paragraph(p -> {
                run(p, "Boka");
                bold(p, " RunAn ");
                italic(p, "nu");
            })).bytes());

            assertThat(guide.chapters().getFirst().items().getFirst().text()).isEqualTo("Boka **RunAn** *nu*");
        }

        @Test
        void markdownCharactersAreEscaped() {
            final ParsedGuide guide = parser.parse(minimal(doc -> doc.paragraph("Pris *exkl.* moms [ca] 1 000 kr")).bytes());

            assertThat(new GuideMarkdown().plainText(guide.chapters().getFirst().items().getFirst().text()))
                    .isEqualTo("Pris *exkl.* moms [ca] 1 000 kr");
        }

        @Test
        void nestedListItemsJoinTheItemAbove() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());
            final ParsedItem tip = guide.chapters().get(1).sections().getFirst().items().get(3);

            assertThat(tip.type()).isEqualTo(GuideItemType.TIP);
            assertThat(tip.text()).isEqualTo("Kontrollera sprittillstånd i vald lokal.\n\n- Gäller även internkalas.");
        }

        @Test
        void linkTextWithinARunIsKept() {
            final ParsedGuide guide = parser.parse(minimal(doc -> doc.paragraph(p -> link(p, "Boka här", "https://example.org/boka?lokal=1&dag=2"))).bytes());

            assertThat(guide.chapters().getFirst().items().getFirst().text()).isEqualTo("[Boka här](https://example.org/boka?lokal=1&dag=2)");
        }
    }

    @Nested
    class Warnings {

        @Test
        void picturesAndTablesAreWarnedWithTheirPlace() {
            final ParsedGuide guide = parser.parse(minimal(doc -> doc.heading2("Avsnitt").table().picture()).bytes());

            assertThat(guide.warnings()).containsExactly(
                    new Warning(GuideImportWarningCode.TABLE, Phase.A, "Avsnitt", null),
                    new Warning(GuideImportWarningCode.IMAGE, Phase.A, "Avsnitt", null));
        }

        @Test
        void v5LayoutWarnsAboutTheTimelinePicture() {
            final ParsedGuide guide = parser.parse(GuideDocxFixtures.v5Layout().bytes());

            assertThat(guide.warnings()).contains(new Warning(GuideImportWarningCode.IMAGE, Phase.B, "Tidslinjen", null));
            assertThat(guide.warnings()).hasSize(3);
        }
    }

    @Nested
    class Refusals {

        private static void assertChapters(final GuideDocxFixtures doc, final String letters) {
            assertThatThrownBy(() -> new GuideDocxParser(new GuideMarkdown()).parse(doc.bytes()))
                    .isInstanceOfSatisfying(BadRequestException.class, e -> {
                        assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.import.chapters");
                        assertThat(e.getDetailMessageArguments()).containsExactly(letters);
                    });
        }

        @Test
        void aMissingChapter() {
            final GuideDocxFixtures doc = document();

            for (final Phase phase : Phase.values()) {
                if (phase != Phase.C && phase != Phase.F) {
                    doc.heading1(phase.name() + ". Kapitel").paragraph("Text.");
                }
            }
            assertChapters(doc, "C, F");
        }

        @Test
        void aLetterOutsideAToJ() {
            final GuideDocxFixtures doc = minimal(_ -> {
            });

            assertChapters(doc.heading1("K. Extra"), "K");
        }

        @Test
        void aLetterTwice() {
            assertChapters(minimal(_ -> {
            }).heading1("B. Igen"), "B");
        }

        @Test
        void notAZip() {
            assertThatThrownBy(() -> parser.parse("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                    .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.import.notWord"));
        }

        @Test
        void aZipWithoutADocument() throws Exception {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            try (final java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("xl/workbook.xml"));
                zip.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            assertThatThrownBy(() -> parser.parse(out.toByteArray()))
                    .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.import.notWord"));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Service {

        @Mock
        private GuideVersionRepository versionRepository;
        @Mock
        private GuideChapterRepository chapterRepository;
        @Mock
        private GuideSectionRepository sectionRepository;
        @Mock
        private GuideItemRepository itemRepository;
        @Mock
        private GuideImportReportRepository reportRepository;
        @Mock
        private CallerService callerService;

        private GuideImportService service() {
            return new GuideImportService(parser, versionRepository, chapterRepository, sectionRepository, itemRepository,
                    reportRepository, callerService, new AccessPolicy());
        }

        @Test
        void onlySuperAdminsImport() {
            for (final var caller : List.of(TestCallers.observer(), TestCallers.directeur(), TestCallers.steeringGroup(), TestCallers.nobody())) {
                when(callerService.current()).thenReturn(caller);
                assertThatThrownBy(() -> service().importDocx("g.docx", GuideDocxFixtures.v5Layout().bytes()))
                        .isInstanceOf(AccessDeniedException.class);
            }
            verify(versionRepository, never()).saveAndFlush(any());
        }

        @Test
        void refusedOnceAVersionIsPublished() {
            when(callerService.current()).thenReturn(TestCallers.superAdmin());
            when(versionRepository.existsByStatus(GuideVersionStatus.PUBLISHED)).thenReturn(true);

            assertThatThrownBy(() -> service().importDocx("g.docx", GuideDocxFixtures.v5Layout().bytes()))
                    .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.import.published"));
            verify(versionRepository, never()).saveAndFlush(any());
        }

        @Test
        void refusedWhileADraftExists() {
            when(callerService.current()).thenReturn(TestCallers.superAdmin());
            when(versionRepository.existsByStatus(GuideVersionStatus.PUBLISHED)).thenReturn(false);
            when(versionRepository.existsByStatus(GuideVersionStatus.DRAFT)).thenReturn(true);

            assertThatThrownBy(() -> service().importDocx("g.docx", GuideDocxFixtures.v5Layout().bytes()))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.guide.import.draftExists"));
        }

        @Test
        void aRefusedDocumentStoresNothing() {
            when(callerService.current()).thenReturn(TestCallers.superAdmin());

            assertThatThrownBy(() -> service().importDocx("g.docx", "nope".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(BadRequestException.class);
            verify(versionRepository, never()).saveAndFlush(any());
        }
    }
}
