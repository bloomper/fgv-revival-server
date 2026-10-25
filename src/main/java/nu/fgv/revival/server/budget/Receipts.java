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
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * What a receipt file may be: a PDF or a JPEG, PNG, HEIC or WebP image of at most 10 MB. The type is read
 * from the file's first bytes, never taken from the client alone.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class Receipts {

    public static final int MAX_SIZE = 10 * 1024 * 1024;
    public static final String PDF = "application/pdf";
    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String HEIC = "image/heic";
    public static final String WEBP = "image/webp";

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final Set<String> HEIC_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1");

    private Receipts() {
    }

    /**
     * The file's type by its content, or a refusal when it is empty, too large or of another type.
     */
    public static String type(final byte[] content) {
        if (content.length == 0) {
            throw new BadRequestException("error.receipt.empty");
        }
        if (content.length > MAX_SIZE) {
            throw new BadRequestException("error.receipt.tooLarge");
        }

        final @Nullable String type = sniff(content);

        if (type == null) {
            throw new BadRequestException("error.receipt.type");
        }
        return type;
    }

    static @Nullable String sniff(final byte[] content) {
        if (startsWith(content, 0, "%PDF".getBytes(StandardCharsets.US_ASCII))) {
            return PDF;
        }
        if (startsWith(content, 0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return JPEG;
        }
        if (startsWith(content, 0, PNG_SIGNATURE)) {
            return PNG;
        }
        if (startsWith(content, 0, ascii("RIFF")) && startsWith(content, 8, ascii("WEBP"))) {
            return WEBP;
        }
        if (startsWith(content, 4, ascii("ftyp")) && content.length >= 12
                && HEIC_BRANDS.contains(new String(content, 8, 4, StandardCharsets.US_ASCII))) {
            return HEIC;
        }
        return null;
    }

    /**
     * The last segment of the name the client gave, without control characters, at most 255 characters; "kvitto" with
     * the type's extension when nothing is left.
     */
    public static String filename(final @Nullable String original, final String type) {
        String name = original == null ? "" : original;
        final int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));

        name = name.substring(slash + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "kvitto" + extension(type);
        }
        if (name.length() > BudgetTransaction.FILENAME_MAX_LENGTH) {
            final int dot = name.lastIndexOf('.');
            final String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";

            name = name.substring(0, BudgetTransaction.FILENAME_MAX_LENGTH - extension.length()) + extension;
        }
        return name;
    }

    private static String extension(final String type) {
        return switch (type) {
            case PDF -> ".pdf";
            case JPEG -> ".jpg";
            case PNG -> ".png";
            case HEIC -> ".heic";
            case WEBP -> ".webp";
            default -> "";
        };
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean startsWith(final byte[] content, final int offset, final byte[] prefix) {
        return content.length >= offset + prefix.length && Arrays.equals(content, offset, offset + prefix.length, prefix, 0, prefix.length);
    }
}
