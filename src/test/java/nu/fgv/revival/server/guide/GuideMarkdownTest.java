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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anders Jacobsson
 * @since 0.2
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

    @ParameterizedTest
    @ValueSource(strings = {"*", "_", "[", "]", "#", "`", "\\", "<"})
    void escapeMakesControlCharactersLiteral(final String character) {
        final String text = "a " + character + " b";

        assertThat(markdown.plainText(markdown.escape(text))).isEqualTo(text);
    }

    @Test
    void escapeProtectsLineStartsThatWouldBecomeListsOrHeadings() {
        assertThat(markdown.plainText(markdown.escape("- inte en lista"))).isEqualTo("- inte en lista");
        assertThat(markdown.plainText(markdown.escape("+ inte heller"))).isEqualTo("+ inte heller");
        assertThat(markdown.plainText(markdown.escape("1. inte numrerad"))).isEqualTo("1. inte numrerad");
        assertThat(markdown.plainText(markdown.escape("2026) inte numrerad"))).isEqualTo("2026) inte numrerad");
        assertThat(markdown.plainText(markdown.escape("> inget citat"))).isEqualTo("> inget citat");
    }

    @Test
    void escapeKeepsSwedishAndPunctuation() {
        assertThat(markdown.escape("Kåren, sittande spex – och FGV.")).isEqualTo("Kåren, sittande spex – och FGV.");
    }

    @Test
    void nestedListsSurviveParsing() {
        assertThat(markdown.plainText("Huvudpunkt\n\n- underpunkt ett\n- underpunkt två"))
                .isEqualTo("Huvudpunkt\nunderpunkt ett\nunderpunkt två");
    }
}
