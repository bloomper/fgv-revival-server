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

import nu.fgv.revival.server.guide.GuideImportReportDto.Warning;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlink;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.jspecify.annotations.Nullable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrBase;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns a Word document in guide v5's layout into a parsed guide, without touching the database (research R6,
 * contracts/import.md).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Component
public class GuideDocxParser {

    static final int MAX_LABEL_LENGTH = 40;

    private static final Pattern CHAPTER_HEADING = Pattern.compile("^\\s*([A-Za-zÅÄÖåäö])(?:[.)]|\\s)\\s*(.*)$");
    private static final Map<String, GuideItemType> LABELS = Map.of(
            "praktiska tips", GuideItemType.TIP,
            "krav", GuideItemType.REQUIREMENT,
            "att ta reda på", GuideItemType.QUESTION);

    private final GuideMarkdown markdown;

    public GuideDocxParser(final GuideMarkdown markdown) {
        this.markdown = markdown;
    }

    public record ParsedGuide(String introduction, List<ParsedChapter> chapters, List<Warning> warnings) {
    }

    public record ParsedChapter(Phase phase, String title, List<ParsedItem> items, List<ParsedSection> sections) {
    }

    public record ParsedSection(String title, List<ParsedItem> items) {
    }

    public static final class ParsedItem {

        private final GuideItemType type;
        private final StringBuilder text;

        ParsedItem(final GuideItemType type, final String text) {
            this.type = type;
            this.text = new StringBuilder(text);
        }

        public GuideItemType type() {
            return type;
        }

        public String text() {
            return text.toString();
        }

        void appendNested(final int level, final String nested) {
            text.append("\n\n").append("  ".repeat(Math.max(0, level - 1))).append("- ").append(nested);
        }
    }

    /**
     * Refuses anything that is not a readable {@code .docx}, and a document whose chapters are not exactly A–J.
     */
    public ParsedGuide parse(final byte[] bytes) {
        requireWord(bytes);

        try (final XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return new Walk(document).run();
        } catch (final IOException | RuntimeException e) {
            if (e instanceof BadRequestException badRequest) {
                throw badRequest;
            }
            throw new BadRequestException("error.guide.import.notWord");
        }
    }

    private static void requireWord(final byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw new BadRequestException("error.guide.import.notWord");
        }
        try (final ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return;
                }
            }
        } catch (final IOException e) {
            throw new BadRequestException("error.guide.import.notWord");
        }
        throw new BadRequestException("error.guide.import.notWord");
    }

    /**
     * One pass over the document body, keeping the current chapter, section and label.
     */
    private final class Walk {

        private final XWPFDocument document;
        private final List<IBodyElement> elements;
        private final List<String> introduction = new ArrayList<>();
        private final List<ParsedChapter> chapters = new ArrayList<>();
        private final List<Warning> warnings = new ArrayList<>();
        private final Set<String> chapterProblems = new LinkedHashSet<>();
        private final Set<Phase> seen = EnumSet.noneOf(Phase.class);

        private @Nullable ParsedChapter chapter;
        private @Nullable ParsedSection section;
        private @Nullable GuideItemType label;
        private @Nullable ParsedItem lastItem;

        Walk(final XWPFDocument document) {
            this.document = document;
            this.elements = document.getBodyElements();
        }

        ParsedGuide run() {
            for (int index = 0; index < elements.size(); index++) {
                final IBodyElement element = elements.get(index);

                if (element instanceof final XWPFTable _) {
                    warn(GuideImportWarningCode.TABLE, null);
                    label = null;
                } else if (element instanceof final XWPFParagraph paragraph) {
                    paragraph(paragraph, index);
                }
            }
            requireChapters();
            return new ParsedGuide(String.join("\n\n", introduction), chapters, warnings);
        }

        private void paragraph(final XWPFParagraph paragraph, final int index) {
            if (hasPicture(paragraph)) {
                warn(GuideImportWarningCode.IMAGE, null);
            }

            final String raw = rawText(paragraph).strip();
            final int heading = headingLevel(paragraph);

            if (raw.isEmpty()) {
                return;
            }
            if (heading == 1) {
                startChapter(raw);
                return;
            }
            if (chapter == null) {
                introduction.add(inlineMarkdown(paragraph));
                return;
            }
            if (heading == 2) {
                section = new ParsedSection(raw, new ArrayList<>());
                chapter.sections().add(section);
                label = null;
                lastItem = null;
                return;
            }

            final Integer listLevel = listLevel(paragraph);

            if (listLevel != null) {
                if (listLevel > 0 && lastItem != null) {
                    lastItem.appendNested(listLevel, inlineMarkdown(paragraph));
                } else {
                    add(new ParsedItem(label == null ? GuideItemType.TEXT : label, inlineMarkdown(paragraph)));
                }
                return;
            }
            if (raw.length() < MAX_LABEL_LENGTH && nextIsListItem(index)) {
                final GuideItemType known = LABELS.get(normalizeLabel(raw));

                if (known != null) {
                    label = known;
                } else {
                    add(new ParsedItem(GuideItemType.TEXT, inlineMarkdown(paragraph)));
                    warn(GuideImportWarningCode.UNKNOWN_LABEL, raw);
                    label = GuideItemType.TEXT;
                }
                lastItem = null;
                return;
            }
            label = null;
            add(new ParsedItem(GuideItemType.TEXT, inlineMarkdown(paragraph)));
        }

        private void startChapter(final String heading) {
            final Matcher matcher = CHAPTER_HEADING.matcher(heading);

            label = null;
            section = null;
            lastItem = null;
            if (!matcher.matches()) {
                chapterProblems.add(heading.length() > 20 ? heading.substring(0, 20) + "…" : heading);
                chapter = null;
                return;
            }

            final String letter = matcher.group(1).toUpperCase(Locale.ROOT);
            final Phase phase = phase(letter);

            if (phase == null) {
                chapterProblems.add(letter);
                return;
            }
            if (!seen.add(phase)) {
                chapterProblems.add(letter);
                return;
            }
            chapter = new ParsedChapter(phase, matcher.group(2).strip(), new ArrayList<>(), new ArrayList<>());
            chapters.add(chapter);
        }

        private void requireChapters() {
            final Set<String> problems = new TreeSet<>(chapterProblems);

            for (final Phase phase : Phase.values()) {
                if (!seen.contains(phase)) {
                    problems.add(phase.name());
                }
            }
            if (!problems.isEmpty()) {
                throw new BadRequestException("error.guide.import.chapters", String.join(", ", problems));
            }
        }

        private void add(final ParsedItem item) {
            Objects.requireNonNull(chapter);
            (section == null ? chapter.items() : section.items()).add(item);
            lastItem = item;
        }

        private void warn(final GuideImportWarningCode code, final @Nullable String detail) {
            warnings.add(new Warning(code, chapter == null ? null : chapter.phase(), section == null ? null : section.title(), detail));
        }

        private boolean nextIsListItem(final int index) {
            for (int next = index + 1; next < elements.size(); next++) {
                if (!(elements.get(next) instanceof final XWPFParagraph paragraph)) {
                    return false;
                }
                if (hasPicture(paragraph)) {
                    return false;
                }
                if (!rawText(paragraph).isBlank()) {
                    return listLevel(paragraph) != null && headingLevel(paragraph) == 0;
                }
            }
            return false;
        }

        private int headingLevel(final XWPFParagraph paragraph) {
            final Integer outline = outlineLevel(paragraph.getCTP().getPPr());

            if (outline != null && outline <= 1) {
                return outline + 1;
            }

            final XWPFStyle style = paragraph.getStyleID() == null || document.getStyles() == null
                    ? null : document.getStyles().getStyle(paragraph.getStyleID());

            if (style == null) {
                return 0;
            }

            final String name = style.getName() == null ? "" : style.getName().toLowerCase(Locale.ROOT);

            if ("heading 1".equals(name)) {
                return 1;
            }
            if ("heading 2".equals(name)) {
                return 2;
            }

            final Integer styleOutline = outlineLevel(style.getCTStyle().getPPr());

            return styleOutline != null && styleOutline <= 1 ? styleOutline + 1 : 0;
        }

        private String inlineMarkdown(final XWPFParagraph paragraph) {
            final List<Segment> segments = new ArrayList<>();

            for (final XWPFRun run : paragraph.getRuns()) {
                final String text = run.text();

                if (text == null || text.isEmpty()) {
                    continue;
                }

                final String url = run instanceof final XWPFHyperlinkRun link ? url(link) : null;
                final Segment segment = new Segment(text, run.isBold(), run.isItalic(), url);

                if (!segments.isEmpty() && segments.getLast().sameFormat(segment)) {
                    segments.set(segments.size() - 1, segments.getLast().append(text));
                } else {
                    segments.add(segment);
                }
            }
            return segments.stream().map(this::toMarkdown).collect(Collectors.joining()).strip();
        }

        private @Nullable String url(final XWPFHyperlinkRun run) {
            final XWPFHyperlink hyperlink = run.getHyperlink(document);

            return hyperlink == null ? null : hyperlink.getURL();
        }

        private String toMarkdown(final Segment segment) {
            final String text = segment.text().replace("\t", " ");
            final String core = text.strip();

            if (core.isEmpty()) {
                return text.replace("\n", "  \n");
            }

            final int start = text.indexOf(core);
            final String leading = text.substring(0, start);
            final String trailing = text.substring(start + core.length());
            String body = markdown.escape(core).replace("\n", "  \n");

            if (segment.url() != null) {
                body = "[" + body + "](" + segment.url().replace(" ", "%20").replace(")", "%29") + ")";
            }
            if (segment.bold() && segment.italic()) {
                body = "***" + body + "***";
            } else if (segment.bold()) {
                body = "**" + body + "**";
            } else if (segment.italic()) {
                body = "*" + body + "*";
            }
            return leading + body + trailing;
        }
    }

    private record Segment(String text, boolean bold, boolean italic, @Nullable String url) {

        boolean sameFormat(final Segment other) {
            return bold == other.bold && italic == other.italic && Objects.equals(url, other.url);
        }

        Segment append(final String more) {
            return new Segment(text + more, bold, italic, url);
        }
    }

    private static @Nullable Phase phase(final String letter) {
        for (final Phase phase : Phase.values()) {
            if (phase.name().equals(letter)) {
                return phase;
            }
        }
        return null;
    }

    private static String normalizeLabel(final String raw) {
        final String label = raw.strip().toLowerCase(Locale.of("sv"));

        return label.endsWith(":") ? label.substring(0, label.length() - 1).strip() : label;
    }

    private static String rawText(final XWPFParagraph paragraph) {
        return paragraph.getText() == null ? "" : paragraph.getText();
    }

    private static boolean hasPicture(final XWPFParagraph paragraph) {
        return paragraph.getRuns().stream().anyMatch(run -> !run.getEmbeddedPictures().isEmpty())
                || paragraph.getCTP().xmlText().contains("<w:drawing") || paragraph.getCTP().xmlText().contains("<w:pict");
    }

    private static @Nullable Integer listLevel(final XWPFParagraph paragraph) {
        if (paragraph.getNumID() == null) {
            return null;
        }
        return paragraph.getNumIlvl() == null ? 0 : paragraph.getNumIlvl().intValue();
    }

    private static @Nullable Integer outlineLevel(final @Nullable CTPPrBase properties) {
        return properties == null || properties.getOutlineLvl() == null ? null : properties.getOutlineLvl().getVal().intValue();
    }
}
