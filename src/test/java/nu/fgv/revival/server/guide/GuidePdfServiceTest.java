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

import nu.fgv.revival.server.TestCatalogs;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfArray;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.SimpleBookmark;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PDF, read back with OpenPDF's own reader (FR-028–FR-030, SC-005, FR-010).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
class GuidePdfServiceTest {

    private static final Locale SWEDISH = Locale.of("sv");

    private final GuidePdfService service = new GuidePdfService(new GuideMarkdown(), messages(), TestCatalogs.itemTypeRepository());

    private static ResourceBundleMessageSource messages() {
        final ResourceBundleMessageSource messages = new ResourceBundleMessageSource();

        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        return messages;
    }

    private static GuideVersionView.Text text(final String sv, final String en) {
        return GuideSearchTest.text(sv, en);
    }

    private static GuideVersionView version(final GuideVersionStatus status, final List<GuideVersionView.Item> extraItems) {
        final List<GuideVersionView.Chapter> chapters = new ArrayList<>();

        for (final String key : GuideTestChapters.KEYS) {
            final List<GuideVersionView.Item> items = new ArrayList<>();

            if (key.equals("A")) {
                items.add(new GuideVersionView.Item(1L, "a1", key, null, "REQUIREMENT", GuideItemKind.REQUIREMENT,
                        text("Godkänn av **JK** och [FGV](https://fgv.example.org).", "Approved by **JK** and [FGV](https://fgv.example.org)."), 0L));
                items.add(new GuideVersionView.Item(2L, "a2", key, null, "TEXT", GuideItemKind.TEXT, text("Löptext på svenska – med åäö é.", null), 0L));
                items.addAll(extraItems);
            }
            chapters.add(new GuideVersionView.Chapter((long) GuideTestChapters.KEYS.indexOf(key), key, GuideVersionView.Text.plain("Kapitel " + key, key.equals("A") ? "Chapter A" : null),
                    items,
                    key.equals("B") ? List.of(new GuideVersionView.Section(20L, "kalas", key, GuideVersionView.Text.plain("Kalaslokal", null), List.of(
                            new GuideVersionView.Item(3L, "b1", key, "kalas", "TIP", GuideItemKind.TIP, text("Kontrollera sprittillstånd.\n\n- en punkt\n- två", null), 0L),
                            new GuideVersionView.Item(4L, "b2", key, "kalas", "QUESTION", GuideItemKind.QUESTION, text("Vilka datum?", null), 0L)), 0L)) : List.of(),
                    0L));
        }
        return new GuideVersionView(1L, status, status == GuideVersionStatus.PUBLISHED ? "v5" : null, null,
                status == GuideVersionStatus.PUBLISHED ? Instant.parse("2026-11-02T10:00:00Z") : null, null, null, 0L,
                text("Välkommen till guiden.", null), chapters);
    }

    private static String allText(final byte[] pdf) throws IOException {
        final PdfReader reader = new PdfReader(pdf);
        final PdfTextExtractor extractor = new PdfTextExtractor(reader);
        final StringBuilder text = new StringBuilder();

        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(extractor.getTextFromPage(page)).append('\n');
        }
        return text.toString();
    }

    @Test
    void theTitlePageNamesTheVersionDateAndRevival() throws IOException {
        final byte[] pdf = service.render(version(GuideVersionStatus.PUBLISHED, List.of()), SWEDISH, "Ali Baba 2028");
        final String firstPage = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);

        assertThat(firstPage).contains("Nyuppsättningsguide").contains("Version v5").contains("Publicerad").contains("För Ali Baba 2028");
    }

    @Test
    void everyItemIsThereInOrderWithItsTypeInTheChosenLanguage() throws IOException {
        final String swedish = allText(service.render(version(GuideVersionStatus.PUBLISHED, List.of()), SWEDISH, null));

        assertThat(swedish).contains("Innehåll", "Inledning", "Välkommen till guiden.", "1. Kapitel A", "Krav", "Godkänn av JK och FGV.",
                "Kalaslokal", "Praktiska tips", "Kontrollera sprittillstånd.", "en punkt", "Att ta reda på", "Vilka datum?", "10. Kapitel J");
        assertThat(swedish.indexOf("Godkänn av")).isLessThan(swedish.indexOf("Kontrollera sprittillstånd"));

        final String english = allText(service.render(version(GuideVersionStatus.PUBLISHED, List.of()), Locale.ENGLISH, null));

        assertThat(english).contains("Contents", "1. Chapter A", "Requirement", "Approved by JK and FGV.", "Tip", "To find out", "Not translated");
    }

    @Test
    void chaptersAndSectionsAreBookmarksAndLinksAreAnnotations() throws IOException {
        final PdfReader reader = new PdfReader(service.render(version(GuideVersionStatus.PUBLISHED, List.of()), SWEDISH, null));
        final List<Map<String, Object>> bookmarks = SimpleBookmark.getBookmarkList(reader);

        assertThat(bookmarks).extracting(bookmark -> bookmark.get("Title")).contains("1. Kapitel A", "10. Kapitel J");

        int links = 0;

        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            final PdfArray annotations = reader.getPageN(page).getAsArray(PdfName.ANNOTS);

            links += annotations == null ? 0 : annotations.size();
        }
        assertThat(links).as("table of contents and Markdown links").isGreaterThan(10);
    }

    @Test
    void theDraftIsMarkedOnEveryPage() throws IOException {
        final PdfReader reader = new PdfReader(service.render(version(GuideVersionStatus.DRAFT, List.of()), SWEDISH, null));
        final PdfTextExtractor extractor = new PdfTextExtractor(reader);

        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            assertThat(extractor.getTextFromPage(page)).as("page %d", page).contains("UTKAST");
        }
    }

    @Test
    void swedishCharactersRenderAndOthersBecomeAQuestionMark() throws IOException {
        final String text = allText(service.render(version(GuideVersionStatus.PUBLISHED, List.of(
                new GuideVersionView.Item(9L, "a9", "A", null, "TEXT", GuideItemKind.TEXT, text("Pil → och emoji 🎭.", null), 0L))), SWEDISH, null));

        assertThat(text).contains("åäö é").contains("–");
        assertThat(GuideMarkdown.pdfSafe("Pil → och emoji 🎭.")).isEqualTo("Pil ? och emoji ?.");
    }

    @Test
    void otherMarkdownIsPlainTextAndImagesAreNeverFetched() throws IOException {
        try (final ServerSocket socket = new ServerSocket(0)) {
            final String image = "![bild](http://127.0.0.1:%d/x.png)".formatted(socket.getLocalPort());
            final String text = allText(service.render(version(GuideVersionStatus.PUBLISHED, List.of(
                    new GuideVersionView.Item(9L, "a9", "A", null, "TEXT", GuideItemKind.TEXT,
                            text("# En rubrik\n\n`kod` och <b>html</b>\n\nFöre " + image + " efter.", null), 0L))), SWEDISH, null));

            assertThat(text).contains("En rubrik", "kod", "<b>html</b>", "Före", "efter.");
            socket.setSoTimeout(300);
            try {
                socket.accept().close();
                throw new AssertionError("The PDF fetched the image");
            } catch (final SocketTimeoutException expected) {
                // Nothing connected
            }
        }
    }

    @Test
    void aGuideOf150ItemsRendersInUnder10Seconds() {
        final List<GuideVersionView.Item> many = new ArrayList<>();
        final Map<Integer, String> paragraph = new HashMap<>();

        for (int index = 0; index < 150; index++) {
            paragraph.put(index, "Punkt %d med **fetstil**, *kursiv* och en [länk](https://example.org/%d). ".formatted(index, index).repeat(4));
            many.add(new GuideVersionView.Item(100L + index, "k" + index, "A", null, "TIP", GuideItemKind.TIP, text(paragraph.get(index), null), 0L));
        }

        final Instant start = Instant.now();
        final byte[] pdf = service.render(version(GuideVersionStatus.PUBLISHED, many), SWEDISH, null);

        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
        assertThat(pdf.length).isPositive();
    }
}
