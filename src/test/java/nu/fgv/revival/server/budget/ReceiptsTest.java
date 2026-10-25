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
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.util.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a receipt file may be: its type read from its first bytes, its size, and its name.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ReceiptsTest {

    static final byte[] PDF = "%PDF-1.7\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};

    private static byte[] bytes(final String ascii, final int... prefix) {
        final byte[] text = ascii.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] content = new byte[prefix.length + text.length];

        for (int index = 0; index < prefix.length; index++) {
            content[index] = (byte) prefix[index];
        }
        System.arraycopy(text, 0, content, prefix.length, text.length);
        return content;
    }

    @Test
    void theTypeIsReadFromTheContent() {
        assertThat(Receipts.type(PDF)).isEqualTo(Receipts.PDF);
        assertThat(Receipts.type(PNG)).isEqualTo(Receipts.PNG);
        assertThat(Receipts.type(bytes("JFIF", 0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo(Receipts.JPEG);
        assertThat(Receipts.type(bytes("RIFF\u0024\u0000\u0000\u0000WEBPVP8 "))).isEqualTo(Receipts.WEBP);
        assertThat(Receipts.type(bytes("ftypheic\u0000\u0000\u0000\u0000", 0, 0, 0, 0x18))).isEqualTo(Receipts.HEIC);
        assertThat(Receipts.type(bytes("ftypmif1\u0000\u0000\u0000\u0000", 0, 0, 0, 0x18))).isEqualTo(Receipts.HEIC);
    }

    @Test
    void otherFilesAreRefusedWhateverTheyAreCalled() {
        assertThatThrownBy(() -> Receipts.type("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BadRequestException.class).hasFieldOrPropertyWithValue("detailMessageCode", "error.receipt.type");
        assertThatThrownBy(() -> Receipts.type("<html><script>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Receipts.type(bytes("ftypisom\u0000\u0000", 0, 0, 0, 0x18))).as("an MP4 video").isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Receipts.type(bytes("RIFF\u0024\u0000\u0000\u0000WAVE"))).as("a WAV sound").isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> Receipts.type(new byte[]{'%', 'P'})).isInstanceOf(BadRequestException.class);
    }

    @Test
    void emptyAndTooLargeFilesAreRefused() {
        final byte[] large = Arrays.copyOf(PDF, Receipts.MAX_SIZE + 1);

        assertThatThrownBy(() -> Receipts.type(new byte[0])).hasFieldOrPropertyWithValue("detailMessageCode", "error.receipt.empty");
        assertThatThrownBy(() -> Receipts.type(large)).hasFieldOrPropertyWithValue("detailMessageCode", "error.receipt.tooLarge");
        assertThat(Receipts.type(Arrays.copyOf(PDF, Receipts.MAX_SIZE))).isEqualTo(Receipts.PDF);
    }

    @ParameterizedTest
    @CsvSource({
            "kvitto.pdf,                       application/pdf, kvitto.pdf",
            "C:\\\\Users\\\\kim\\\\bild.jpg,   image/jpeg,      bild.jpg",
            "../../etc/passwd.png,             image/png,       passwd.png",
            "'  ',                             image/png,       kvitto.png",
            "..,                               image/webp,      kvitto.webp",
            "dir/,                             image/heic,      kvitto.heic"
    })
    void onlyTheLastSegmentOfTheNameIsKept(final String name, final String type, final String kept) {
        assertThat(Receipts.filename(name, type)).isEqualTo(kept);
    }

    @Test
    void namesLoseControlCharactersAndAreShortened() {
        assertThat(Receipts.filename("kvi\u0000tto\r\n.pdf", Receipts.PDF)).isEqualTo("kvitto.pdf");
        assertThat(Receipts.filename(null, Receipts.JPEG)).isEqualTo("kvitto.jpg");

        final String shortened = Receipts.filename("a".repeat(300) + ".pdf", Receipts.PDF);

        assertThat(shortened).hasSize(BudgetTransaction.FILENAME_MAX_LENGTH).endsWith(".pdf");
    }
}
