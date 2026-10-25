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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
class GuideMarkdownTest {

    private final GuideMarkdown markdown = new GuideMarkdown();

    @Test
    void plainTextDropsEmphasisAndLinkSyntax() {
        assertThat(markdown.plainText("Boka **RunAn** och *Kårhuset* via [kåren](https://example.org/bokning)."))
                .isEqualTo("Boka RunAn och Kårhuset via kåren.");
    }

    @Test
    void plainTextKeepsRawHtmlAsLiteralText() {
        assertThat(markdown.plainText("Se <b>här</b> och <script>alert(1)</script>"))
                .contains("<b>här</b>")
                .contains("<script>alert(1)</script>");
    }

    @Test
    void plainTextSeparatesParagraphsAndListItems() {
        assertThat(markdown.plainText("Första stycket.\n\nAndra stycket.\n\n- ett\n- två"))
                .isEqualTo("Första stycket.\nAndra stycket.\nett\ntvå");
    }

    @Test
    void plainTextDropsImages() {
        assertThat(markdown.plainText("Före ![tidplan](https://example.org/x.png) efter")).isEqualTo("Före  efter");
    }

    @Test
    void nestedListsSurviveParsing() {
        assertThat(markdown.plainText("Huvudpunkt\n\n- underpunkt ett\n- underpunkt två"))
                .isEqualTo("Huvudpunkt\nunderpunkt ett\nunderpunkt två");
    }

    @Test
    void toHtmlRendersTheGuidesElements() {
        assertThat(markdown.toHtml("Hej **alla** och *välkomna*!\n\n- ett\n- två\n\n1. först\n\nSe [sidan](https://example.org/a?b=1&c=2)."))
                .isEqualTo("<p>Hej <strong>alla</strong> och <em>välkomna</em>!</p><ul><li>ett</li><li>två</li></ul><ol><li>först</li></ol>"
                        + "<p>Se <a href=\"https://example.org/a?b=1&amp;c=2\">sidan</a>.</p>");
    }

    @Test
    void toHtmlEscapesRawHtmlAndDropsImages() {
        final String html = markdown.toHtml("Se <b>här</b> ![spårare](https://example.org/pixel.png)\n\n<script>alert(1)</script>");

        assertThat(html).doesNotContain("<b>").doesNotContain("<script").doesNotContain("<img").doesNotContain("pixel.png")
                .contains("&lt;b&gt;här&lt;/b&gt;").contains("&lt;script&gt;");
    }

    @Test
    void toHtmlKeepsOnlyWebAndMailLinksAndUnwrapsHeadings() {
        assertThat(markdown.toHtml("# Rubrik\n\n[klicka](javascript:alert(1)) [mejla](mailto:styrgrupp@example.org)"))
                .isEqualTo("<p>Rubrik</p><p>klicka <a href=\"mailto:styrgrupp@example.org\">mejla</a></p>");
    }
}
