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

import org.apache.poi.util.Units;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTString;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Builds fictitious Word documents in the layout of guide v5 (contracts/import.md), so no real document is ever
 * committed (constitution V). Headings use v5's Swedish style ids with the built-in names, as Swedish Word writes them.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
public final class GuideDocxFixtures {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private final XWPFDocument document = new XWPFDocument();

    private GuideDocxFixtures() {
        final XWPFStyles styles = document.createStyles();

        styles.addStyle(style("Rubrik1", "heading 1"));
        styles.addStyle(style("Rubrik2", "heading 2"));
        styles.addStyle(style("Liststycke", "List Paragraph"));
    }

    public static GuideDocxFixtures document() {
        return new GuideDocxFixtures();
    }

    private static XWPFStyle style(final String id, final String name) {
        final CTStyle style = CTStyle.Factory.newInstance();
        final CTString styleName = CTString.Factory.newInstance();

        style.setStyleId(id);
        styleName.setVal(name);
        style.setName(styleName);
        style.setType(STStyleType.PARAGRAPH);
        return new XWPFStyle(style);
    }

    public GuideDocxFixtures heading1(final String text) {
        final XWPFParagraph paragraph = document.createParagraph();

        paragraph.setStyle("Rubrik1");
        paragraph.createRun().setText(text);
        return this;
    }

    /**
     * A level 1 heading recognised by its outline level only, with a style that has no built-in name.
     */
    public GuideDocxFixtures outlineHeading1(final String text) {
        final XWPFParagraph paragraph = document.createParagraph();
        final CTDecimalNumber level = paragraph.getCTP().addNewPPr().addNewOutlineLvl();

        level.setVal(BigInteger.ZERO);
        paragraph.createRun().setText(text);
        return this;
    }

    public GuideDocxFixtures heading2(final String text) {
        final XWPFParagraph paragraph = document.createParagraph();

        paragraph.setStyle("Rubrik2");
        paragraph.createRun().setText(text);
        return this;
    }

    public GuideDocxFixtures paragraph(final String text) {
        document.createParagraph().createRun().setText(text);
        return this;
    }

    public GuideDocxFixtures emptyParagraph() {
        document.createParagraph();
        return this;
    }

    /**
     * A paragraph built from runs, e.g. {@code p -> { run(p, "a"); bold(p, "b"); }}.
     */
    public GuideDocxFixtures paragraph(final Consumer<XWPFParagraph> runs) {
        runs.accept(document.createParagraph());
        return this;
    }

    public GuideDocxFixtures listItem(final String text) {
        return listItem(0, paragraph -> paragraph.createRun().setText(text));
    }

    public GuideDocxFixtures listItem(final int level, final String text) {
        return listItem(level, paragraph -> paragraph.createRun().setText(text));
    }

    public GuideDocxFixtures listItem(final int level, final Consumer<XWPFParagraph> runs) {
        final XWPFParagraph paragraph = document.createParagraph();

        paragraph.setStyle("Liststycke");
        paragraph.setNumID(BigInteger.ONE);
        paragraph.setNumILvl(BigInteger.valueOf(level));
        runs.accept(paragraph);
        return this;
    }

    public GuideDocxFixtures picture() {
        try {
            document.createParagraph().createRun()
                    .addPicture(new ByteArrayInputStream(PNG), PictureType.PNG, "x.png", Units.toEMU(10), Units.toEMU(10));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    public GuideDocxFixtures table() {
        document.createTable(1, 1).getRow(0).getCell(0).setText("Cell");
        return this;
    }

    public static void run(final XWPFParagraph paragraph, final String text) {
        paragraph.createRun().setText(text);
    }

    public static void bold(final XWPFParagraph paragraph, final String text) {
        final XWPFRun run = paragraph.createRun();

        run.setBold(true);
        run.setText(text);
    }

    public static void italic(final XWPFParagraph paragraph, final String text) {
        final XWPFRun run = paragraph.createRun();

        run.setItalic(true);
        run.setText(text);
    }

    public static void boldItalic(final XWPFParagraph paragraph, final String text) {
        final XWPFRun run = paragraph.createRun();

        run.setBold(true);
        run.setItalic(true);
        run.setText(text);
    }

    public static void link(final XWPFParagraph paragraph, final String text, final String url) {
        final XWPFHyperlinkRun run = paragraph.createHyperlinkRun(url);

        run.setText(text);
    }

    public byte[] bytes() {
        try (final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.write(out);
            document.close();
            return out.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Ten fictitious chapters A–J in v5's layout: an introduction, chapter prose, items before the first section,
     * the three known labels, two unknown labels, a label followed by a picture, and one picture.
     *
     * <ul>
     *   <li>Introduction: 2 paragraphs</li>
     *   <li>Sections: 4 (B ×2, D ×1, J ×1)</li>
     *   <li>Items: 6 TIP, 3 REQUIREMENT, 1 QUESTION, TEXT as listed in {@link #V5_LAYOUT_TEXTS}</li>
     *   <li>Warnings: 1 IMAGE (B, "Tidslinjen"), 2 UNKNOWN_LABEL ("Om kalaset" in B, "Checklista för mötet" in D)</li>
     * </ul>
     */
    public static GuideDocxFixtures v5Layout() {
        final GuideDocxFixtures doc = document()
                .paragraph("Kontinuitetsguide: Nyuppsättning av ett Chalmersspex")
                .paragraph("Ni funderar alltså över nyuppsättning av ert gamla spex? Grattis!");

        doc.heading1("A. Först: godkännande och tajmning")
                .paragraph("Innan något annat görs behöver ni klartecken från JK och FGV.")
                .paragraph("Praktiska tips")
                .listItem("Kontakt sker via respektive förenings ordförande.")
                .listItem(0, p -> {
                    run(p, "Förankra idén ");
                    bold(p, "brett");
                    run(p, " och ");
                    italic(p, "tidigt");
                    run(p, ", se ");
                    link(p, "fgv.nu", "https://fgv.example.org");
                    run(p, ".");
                })
                .paragraph("Krav")
                .listItem("Nyuppsättningen ska godkännas av JK och FGV innan arbetet startar.");

        doc.heading1("B. Boka lokaler och lägg fast er tidslinje")
                .heading2("Kalaslokal")
                .paragraph("Om kalaset")
                .listItem("250 personer är ett stort kalas.")
                .listItem("Ha en plan för vem som ska få gå.")
                .paragraph("Praktiska tips:")
                .listItem("Kontrollera sprittillstånd i vald lokal.")
                .listItem(1, "Gäller även internkalas.")
                .heading2("Tidslinjen")
                .paragraph("Exempel på tidplan för nyuppsättning")
                .picture()
                .emptyParagraph()
                .paragraph("att ta reda på")
                .listItem("Vilka datum har sittande spex?");

        doc.heading1("C. Sätt budgeten")
                .paragraph("Praktiska tips")
                .listItem("Sätt av en buffert för oväntade kostnader.")
                .paragraph("Krav")
                .listItem("Budget ska upprättas innan ni drar kostnader.");

        doc.heading1("D. Bygg organisationen")
                .heading2("Andra direktionsmötet")
                .paragraph("Checklista för mötet")
                .listItem("Organisation: roller och ansvar.")
                .paragraph("Praktiska tips")
                .listItem("Starta en gemensam Drive.");

        doc.heading1("E. Ordna tillgångar inför repetitionerna").paragraph("Praktiska tips").listItem("Undersök accesser i god tid.");
        doc.heading1("F. Repetitioner").paragraph("Dokumentera processen löpande, både för minnen och för lärdomar.");
        doc.heading1("G. Marknadsföring och biljetter").paragraph("Starta marknadsföringen tidigt och var kreativa.");
        doc.heading1("H. Föreställnings- och kalashelgen").paragraph("Kalaset är en lika stor apparat som föreställningen.");
        doc.heading1("I. Eventuell turné (frivilligt)").paragraph("Frågan om turné dyker ofta upp under kalaset.");
        doc.heading1("J. Stänga nyuppsättningen")
                .heading2("Ekonomi")
                .paragraph("Krav")
                .listItem("Ett eventuellt lån ska återbetalas.");
        return doc;
    }

    /**
     * TEXT items in {@link #v5Layout()}: A 1 (prose); B 4 ("Om kalaset", its 2 items, "Exempel på tidplan…");
     * D 2 ("Checklista för mötet", its item); F–I 1 each.
     */
    public static final int V5_LAYOUT_TEXTS = 11;
}
