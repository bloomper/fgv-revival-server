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

import nu.fgv.revival.server.event.EventType;
import nu.fgv.revival.server.event.EventTypeRepository;
import nu.fgv.revival.server.guide.GuideMarkdown.PdfFonts;
import org.jspecify.annotations.Nullable;
import org.openpdf.text.Chapter;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Section;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The guide as a PDF, in Swedish or English: a title page, a linked table of contents,
 * bookmarks, the introduction, every chapter, section and item with its type, and last the timeline template: the active
 * event types with their offsets from the premiere. Event types are not versioned, so every version shows the current
 * ones. The draft is marked on every page. No access checks here: callers check first. Never calls Spexregister.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class GuidePdfService {

    private static final ZoneId SWEDEN = ZoneId.of("Europe/Stockholm");

    private final GuideMarkdown markdown;
    private final MessageSource messageSource;
    private final GuideItemTypeRepository typeRepository;
    private final EventTypeRepository eventTypeRepository;

    public GuidePdfService(final GuideMarkdown markdown, final MessageSource messageSource, final GuideItemTypeRepository typeRepository,
                           final EventTypeRepository eventTypeRepository) {
        this.markdown = markdown;
        this.messageSource = messageSource;
        this.typeRepository = typeRepository;
        this.eventTypeRepository = eventTypeRepository;
    }

    private static String text(final GuideVersionView.Text text, final boolean english) {
        return english && text.en() != null ? text.en() : text.sv();
    }

    private static @Nullable String text(final @Nullable String sv, final @Nullable String en, final boolean english) {
        return english && en != null ? en : sv;
    }

    /**
     * Whole weeks from the premiere, rounded as the web rounds them.
     */
    private static long weeks(final int days) {
        return Math.abs(Math.round(days / 7.0));
    }

    public byte[] render(final GuideVersionView version, final Locale locale, final @Nullable String revivalTitle) {
        final boolean english = Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
        final PdfFonts body = PdfFonts.helvetica(10.5f);
        final Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 26);
        final Font subtitle = FontFactory.getFont(FontFactory.HELVETICA, 13);
        final Font chapterFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 17);
        final Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        final Font typeFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Font.NORMAL, new Color(90, 90, 90));
        final Font noteFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8.5f, Font.NORMAL, new Color(120, 120, 120));
        final Map<String, GuideItemType> types = typeRepository.findAllByOrderByPositionAscIdAsc().stream()
                .collect(Collectors.toMap(GuideItemType::getId, Function.identity()));
        final List<EventType> eventTypes = eventTypeRepository.findAllByOrderByPositionAscIdAsc().stream()
                .filter(type -> !type.isRetired())
                .toList();

        try (final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final Document document = new Document(PageSize.A4, 56, 56, 56, 56);
            final PdfWriter writer = PdfWriter.getInstance(document, out);

            if (version.draft()) {
                writer.setPageEvent(new DraftMark(message("guide.draft", locale)));
            }
            document.addTitle(message("guide.name", locale));
            document.open();

            // Title page
            final Paragraph cover = new Paragraph();

            cover.setSpacingBefore(180);
            cover.add(new Paragraph(message("guide.name", locale), title));
            cover.add(new Paragraph(" "));
            cover.add(new Paragraph(version.draft()
                    ? message("guide.draft", locale)
                    : message("guide.pdf.version", locale, version.label()), subtitle));
            if (version.publishedAt() != null) {
                cover.add(new Paragraph(message("guide.pdf.published", locale,
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(version.publishedAt().atZone(SWEDEN))), subtitle));
            }
            if (revivalTitle != null) {
                cover.add(new Paragraph(" "));
                cover.add(new Paragraph(message("guide.pdf.revival", locale, revivalTitle), subtitle));
            }
            document.add(cover);
            document.newPage();

            // Table of contents, linked to the chapters and sections
            document.add(new Paragraph(message("guide.pdf.contents", locale), chapterFont));
            for (int index = 0; index < version.chapters().size(); index++) {
                final GuideVersionView.Chapter chapter = version.chapters().get(index);
                final Chunk entry = new Chunk(GuideMarkdown.pdfSafe(message("guide.pdf.chapter", locale, index + 1, text(chapter.title(), english))), body.normal());
                final Paragraph line = new Paragraph(entry);

                entry.setLocalGoto("chapter-" + chapter.key());
                line.setSpacingBefore(4);
                document.add(line);
                for (final GuideVersionView.Section section : chapter.sections()) {
                    final Chunk sectionEntry = new Chunk(GuideMarkdown.pdfSafe(text(section.title(), english)), body.normal());
                    final Paragraph sectionLine = new Paragraph(sectionEntry);

                    sectionEntry.setLocalGoto("section-" + section.key());
                    sectionLine.setIndentationLeft(16);
                    document.add(sectionLine);
                }
            }
            if (!eventTypes.isEmpty()) {
                final Chunk entry = new Chunk(message("guide.pdf.timeline", locale), body.normal());
                final Paragraph line = new Paragraph(entry);

                entry.setLocalGoto("timeline");
                line.setSpacingBefore(4);
                document.add(line);
            }
            document.newPage();

            final GuideVersionView.Text introduction = version.introduction();

            if (!introduction.sv().isBlank()) {
                document.add(new Paragraph(message("guide.pdf.introduction", locale), chapterFont));
                addText(document, introduction, english, body, noteFont, locale);
            }

            int number = 1;

            for (final GuideVersionView.Chapter chapter : version.chapters()) {
                final Chunk heading = new Chunk(GuideMarkdown.pdfSafe(message("guide.pdf.chapter", locale, number, text(chapter.title(), english))), chapterFont);

                heading.setLocalDestination("chapter-" + chapter.key());

                final Chapter pdfChapter = new Chapter(new Paragraph(heading), number++);

                pdfChapter.setNumberDepth(0);
                pdfChapter.setBookmarkTitle(heading.getContent());
                for (final GuideVersionView.Item item : chapter.items()) {
                    addItem(pdfChapter, item, types.get(item.type()), english, body, typeFont, noteFont, locale);
                }
                for (final GuideVersionView.Section section : chapter.sections()) {
                    final Chunk sectionHeading = new Chunk(GuideMarkdown.pdfSafe(text(section.title(), english)), sectionFont);
                    final Paragraph sectionTitle = new Paragraph(sectionHeading);

                    sectionHeading.setLocalDestination("section-" + section.key());
                    sectionTitle.setSpacingBefore(10);

                    final Section pdfSection = pdfChapter.addSection(sectionTitle);

                    pdfSection.setNumberDepth(0);
                    pdfSection.setBookmarkTitle(sectionHeading.getContent());
                    for (final GuideVersionView.Item item : section.items()) {
                        addItem(pdfSection, item, types.get(item.type()), english, body, typeFont, noteFont, locale);
                    }
                }
                document.add(pdfChapter);
            }
            if (!eventTypes.isEmpty()) {
                document.add(timeline(eventTypes, number, english, body, chapterFont, sectionFont, typeFont, locale));
            }

            document.close();
            return out.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The timeline template: each active event type with when it falls, how long it lasts, whether the revival pays,
     * and its default content.
     */
    private Chapter timeline(final List<EventType> eventTypes, final int number, final boolean english, final PdfFonts body,
                             final Font chapterFont, final Font sectionFont, final Font typeFont, final Locale locale) {
        final Chunk heading = new Chunk(message("guide.pdf.timeline", locale), chapterFont);

        heading.setLocalDestination("timeline");

        final Chapter chapter = new Chapter(new Paragraph(heading), number);

        chapter.setNumberDepth(0);
        chapter.setBookmarkTitle(heading.getContent());
        chapter.add(new Paragraph(message("guide.pdf.timeline.intro", locale), body.normal()));
        for (final EventType type : eventTypes) {
            final Paragraph name = new Paragraph(GuideMarkdown.pdfSafe(Objects.requireNonNull(text(type.getNameSv(), type.getNameEn(), english))), sectionFont);

            name.setSpacingBefore(10);

            final Section section = chapter.addSection(name);
            final String days = message(type.getDays() == 1 ? "guide.pdf.timeline.day" : "guide.pdf.timeline.days", locale, type.getDays());
            final String facts = type.isRevivalPays()
                    ? String.join(" · ", offset(type, locale), days, message("guide.pdf.timeline.revivalPays", locale))
                    : String.join(" · ", offset(type, locale), days);

            section.setNumberDepth(0);
            section.setBookmarkTitle(name.getContent());
            section.add(new Paragraph(facts, typeFont));
            addTimelineText(section, message("guide.pdf.timeline.description", locale), text(type.getDescriptionSv(), type.getDescriptionEn(), english), body);
            addTimelineText(section, message("guide.pdf.timeline.rooms", locale), text(type.getRoomsSv(), type.getRoomsEn(), english), body);
            addTimelineText(section, message("guide.pdf.timeline.catering", locale), text(type.getCateringSv(), type.getCateringEn(), english), body);
        }
        return chapter;
    }

    /**
     * When a type falls, in whole weeks from the premiere: "8–10 veckor före premiären".
     */
    private String offset(final EventType type, final Locale locale) {
        final Integer min = type.getOffsetMinDays();
        final Integer max = type.getOffsetMaxDays();

        if (min == null || max == null) {
            return message("guide.pdf.timeline.offsetNone", locale);
        }
        if (min == 0 && max == 0) {
            return message("guide.pdf.timeline.offsetPremiere", locale);
        }
        if (max <= 0) {
            return message("guide.pdf.timeline.offsetBefore", locale, weeks(min), weeks(max));
        }
        if (min >= 0) {
            return message("guide.pdf.timeline.offsetAfter", locale, weeks(min), weeks(max));
        }
        return message("guide.pdf.timeline.offsetAround", locale, weeks(min), weeks(max));
    }

    private static void addTimelineText(final Section target, final String label, final @Nullable String text, final PdfFonts body) {
        if (text == null || text.isBlank()) {
            return;
        }

        final Paragraph paragraph = new Paragraph();

        paragraph.setSpacingBefore(3);
        paragraph.add(new Chunk(label + ": ", body.bold()));
        paragraph.add(new Chunk(GuideMarkdown.pdfSafe(text.strip()), body.normal()));
        target.add(paragraph);
    }

    private void addItem(final Section target, final GuideVersionView.Item item, final @Nullable GuideItemType itemType, final boolean english,
                         final PdfFonts body, final Font typeFont, final Font noteFont, final Locale locale) {
        if (item.kind() != GuideItemKind.TEXT && itemType != null) {
            final String name = english && itemType.getNameEn() != null ? itemType.getNameEn() : itemType.getNameSv();
            final Paragraph type = new Paragraph(GuideMarkdown.pdfSafe(name), typeFont);

            type.setSpacingBefore(6);
            target.add(type);
        }
        target.addAll(markdown.toPdfElements(text(item.text(), english), body));
        if (english && !item.text().translated()) {
            target.add(new Paragraph(message("guide.pdf.notTranslated", locale), noteFont));
        }
    }

    private void addText(final Document document, final GuideVersionView.Text text, final boolean english, final PdfFonts body,
                         final Font noteFont, final Locale locale) {
        for (final Element element : markdown.toPdfElements(text(text, english), body)) {
            document.add(element);
        }
        if (english && !text.translated()) {
            document.add(new Paragraph(message("guide.pdf.notTranslated", locale), noteFont));
        }
    }

    private String message(final String code, final Locale locale, final Object... arguments) {
        return messageSource.getMessage(code, arguments, code, locale);
    }

    /**
     * "Utkast" / "Draft" across every page of the draft.
     */
    private static final class DraftMark extends PdfPageEventHelper {

        private final String label;
        private final Font font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 72, Font.NORMAL, new Color(225, 225, 225));

        DraftMark(final String label) {
            this.label = label;
        }

        @Override
        public void onEndPage(final PdfWriter writer, final Document document) {
            final PdfContentByte canvas = writer.getDirectContentUnder();
            final float x = (document.getPageSize().getLeft() + document.getPageSize().getRight()) / 2;
            final float y = (document.getPageSize().getTop() + document.getPageSize().getBottom()) / 2;

            ColumnText.showTextAligned(canvas, Element.ALIGN_CENTER, new Phrase(label.toUpperCase(Locale.ROOT), font), x, y, 45);
        }
    }
}
