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

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Block;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.Nullable;
import org.openpdf.text.Anchor;
import org.openpdf.text.Chunk;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.ListItem;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Guide text is CommonMark (FR-010). This turns it into plain text for search and renders it for the PDF and for
 * emails. Images are always dropped and raw HTML is always literal text.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@Component
public class GuideMarkdown {


    private static final Pattern SAFE_LINK = Pattern.compile("(?i)^(https?://|mailto:)\\S+$");
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");
    private final Parser parser = Parser.builder().build();

    /**
     * Escapes text for HTML content and attribute values.
     */
    public static String escapeHtml(final String text) {
        final StringBuilder escaped = new StringBuilder(text.length());

        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);

            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    /**
     * Replaces characters the standard fonts cannot show with {@code ?}, so nothing silently disappears.
     */
    static String pdfSafe(final String text) {
        final CharsetEncoder encoder = WIN_ANSI.newEncoder();
        final StringBuilder safe = new StringBuilder(text.length());

        text.codePoints().forEach(codePoint -> {
            final String character = new String(Character.toChars(codePoint));

            safe.append(encoder.canEncode(character) ? character : "?");
        });
        return safe.toString();
    }

    private static void newLine(final StringBuilder text) {
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
    }

    public Node parse(final String markdown) {
        return parser.parse(markdown);
    }

    /**
     * The text a reader sees, without Markdown syntax: blocks on separate lines, images dropped, raw HTML kept as
     * literal text.
     */
    public String plainText(final String markdown) {
        final StringBuilder text = new StringBuilder();

        parse(markdown).accept(new AbstractVisitor() {
            @Override
            public void visit(final Text node) {
                text.append(node.getLiteral());
            }

            @Override
            public void visit(final Code node) {
                text.append(node.getLiteral());
            }

            @Override
            public void visit(final HtmlInline node) {
                text.append(node.getLiteral());
            }

            @Override
            public void visit(final HtmlBlock node) {
                newLine(text);
                text.append(node.getLiteral().strip());
            }

            @Override
            public void visit(final FencedCodeBlock node) {
                newLine(text);
                text.append(node.getLiteral().strip());
            }

            @Override
            public void visit(final IndentedCodeBlock node) {
                newLine(text);
                text.append(node.getLiteral().strip());
            }

            @Override
            public void visit(final Image node) {
                // Dropped, never shown or loaded (FR-010)
            }

            @Override
            public void visit(final SoftLineBreak node) {
                text.append(' ');
            }

            @Override
            public void visit(final HardLineBreak node) {
                text.append('\n');
            }

            @Override
            protected void visitChildren(final Node parent) {
                if (parent instanceof Block && !(parent.getFirstChild() instanceof Block)) {
                    newLine(text);
                }
                super.visitChildren(parent);
            }
        });
        return text.toString().strip();
    }

    /**
     * Guide Markdown as PDF elements: paragraphs, nested bullet and numbered lists, bold, italics and links. Everything
     * else is its plain text, and images are dropped, so building a PDF never fetches a URL (FR-010).
     */
    public List<Element> toPdfElements(final String markdown, final PdfFonts fonts) {
        final List<Element> elements = new ArrayList<>();

        for (Node block = parse(markdown).getFirstChild(); block != null; block = block.getNext()) {
            final Element element = pdfBlock(block, fonts);

            if (element != null) {
                elements.add(element);
            }
        }
        return elements;
    }

    private @Nullable Element pdfBlock(final Node block, final PdfFonts fonts) {
        if (block instanceof final BulletList list) {
            return pdfList(list, false, fonts);
        }
        if (block instanceof final OrderedList list) {
            return pdfList(list, true, fonts);
        }

        final Paragraph paragraph = new Paragraph();

        paragraph.setLeading(fonts.normal().getSize() * 1.35f);
        paragraph.setSpacingAfter(fonts.normal().getSize() * 0.4f);
        if (block instanceof HtmlBlock || block instanceof FencedCodeBlock || block instanceof IndentedCodeBlock) {
            final String literal = block instanceof final HtmlBlock html ? html.getLiteral()
                    : block instanceof final FencedCodeBlock fenced ? fenced.getLiteral() : ((IndentedCodeBlock) block).getLiteral();

            paragraph.add(new Chunk(pdfSafe(literal.strip()), fonts.normal()));
        } else {
            inline(block, paragraph, fonts, false, false);
        }
        return paragraph.isEmpty() ? null : paragraph;
    }

    private org.openpdf.text.List pdfList(final ListBlock list, final boolean numbered, final PdfFonts fonts) {
        final org.openpdf.text.List pdf = new org.openpdf.text.List(numbered, 12);

        if (!numbered) {
            pdf.setListSymbol(new Chunk("• ", fonts.normal()));
        }
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            final ListItem listItem = new ListItem();

            listItem.setLeading(fonts.normal().getSize() * 1.35f);
            for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof final ListBlock nested) {
                    listItem.add(pdfList(nested, nested instanceof OrderedList, fonts));
                } else {
                    inline(child, listItem, fonts, false, false);
                }
            }
            pdf.add(listItem);
        }
        return pdf;
    }

    private void inline(final Node parent, final Phrase target, final PdfFonts fonts, final boolean strong, final boolean emphasis) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            switch (node) {
                case final Text text -> target.add(new Chunk(pdfSafe(text.getLiteral()), fonts.of(strong, emphasis)));
                case final Code code -> target.add(new Chunk(pdfSafe(code.getLiteral()), fonts.of(strong, emphasis)));
                case final HtmlInline html ->
                        target.add(new Chunk(pdfSafe(html.getLiteral()), fonts.of(strong, emphasis)));
                case final StrongEmphasis _ -> inline(node, target, fonts, true, emphasis);
                case final Emphasis _ -> inline(node, target, fonts, strong, true);
                case final Link link -> {
                    final Anchor anchor = new Anchor("", fonts.link());

                    anchor.setReference(link.getDestination());
                    inline(link, anchor, new PdfFonts(fonts.link(), fonts.link(), fonts.link(), fonts.link(), fonts.link()), strong, emphasis);
                    target.add(anchor);
                }
                case final Image _ -> {
                    // Dropped: the server never fetches an image URL (FR-010)
                }
                case final SoftLineBreak _ -> target.add(new Chunk(" ", fonts.normal()));
                case final HardLineBreak _ -> target.add(Chunk.NEWLINE);
                default -> inline(node, target, fonts, strong, emphasis);
            }
        }
    }

    /**
     * Guide Markdown as HTML for an email (M6B-FR-9), with only the elements the web renders guide text with (FR-010):
     * paragraphs, bullet and numbered lists, bold, italics, line breaks and http(s) or mailto links. Every other element
     * becomes its text, raw HTML is escaped and shown literally, and images are dropped, so nothing is ever loaded.
     */
    public String toHtml(final String markdown) {
        final StringBuilder html = new StringBuilder();

        for (Node block = parse(markdown).getFirstChild(); block != null; block = block.getNext()) {
            htmlBlock(block, html);
        }
        return html.toString();
    }

    private void htmlBlock(final Node block, final StringBuilder html) {
        if (block instanceof final ListBlock list) {
            final boolean numbered = list instanceof OrderedList;

            html.append(numbered ? "<ol>" : "<ul>");
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                html.append("<li>");
                for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                    if (child instanceof ListBlock) {
                        htmlBlock(child, html);
                    } else {
                        htmlInline(child, html);
                    }
                }
                html.append("</li>");
            }
            html.append(numbered ? "</ol>" : "</ul>");
            return;
        }
        if (block instanceof HtmlBlock || block instanceof FencedCodeBlock || block instanceof IndentedCodeBlock) {
            final String literal = block instanceof final HtmlBlock raw ? raw.getLiteral()
                    : block instanceof final FencedCodeBlock fenced ? fenced.getLiteral() : ((IndentedCodeBlock) block).getLiteral();

            html.append("<p>").append(escapeHtml(literal.strip()).replace("\n", "<br>")).append("</p>");
            return;
        }
        if (block.getFirstChild() instanceof Block) {
            // A quote or another container: its blocks, unwrapped
            for (Node child = block.getFirstChild(); child != null; child = child.getNext()) {
                htmlBlock(child, html);
            }
            return;
        }

        final StringBuilder paragraph = new StringBuilder();

        htmlInline(block, paragraph);
        if (!paragraph.isEmpty()) {
            html.append("<p>").append(paragraph).append("</p>");
        }
    }

    private void htmlInline(final Node parent, final StringBuilder html) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            switch (node) {
                case final Text text -> html.append(escapeHtml(text.getLiteral()));
                case final Code code -> html.append(escapeHtml(code.getLiteral()));
                case final HtmlInline raw -> html.append(escapeHtml(raw.getLiteral()));
                case final StrongEmphasis _ -> {
                    html.append("<strong>");
                    htmlInline(node, html);
                    html.append("</strong>");
                }
                case final Emphasis _ -> {
                    html.append("<em>");
                    htmlInline(node, html);
                    html.append("</em>");
                }
                case final Link link -> {
                    final String destination = link.getDestination() == null ? "" : link.getDestination().strip();

                    if (SAFE_LINK.matcher(destination).matches()) {
                        html.append("<a href=\"").append(escapeHtml(destination)).append("\">");
                        htmlInline(link, html);
                        html.append("</a>");
                    } else {
                        htmlInline(link, html);
                    }
                }
                case final Image _ -> {
                    // Dropped: an email never loads an image (FR-010)
                }
                case final SoftLineBreak _ -> html.append(' ');
                case final HardLineBreak _ -> html.append("<br>");
                default -> htmlInline(node, html);
            }
        }
    }

    /**
     * The fonts the PDF uses: the standard Helvetica set, which covers Swedish (research R7).
     */
    public record PdfFonts(Font normal, Font bold, Font italic,
                           Font boldItalic, Font link) {

        public static PdfFonts helvetica(final float size) {
            return new PdfFonts(
                    FontFactory.getFont(FontFactory.HELVETICA, size),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, size),
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, size),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLDOBLIQUE, size),
                    FontFactory.getFont(FontFactory.HELVETICA, size, Font.UNDERLINE, new Color(0, 70, 160)));
        }

        Font of(final boolean strong, final boolean emphasis) {
            if (strong && emphasis) {
                return boldItalic;
            }
            return strong ? bold : emphasis ? italic : normal;
        }
    }
}
