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

import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;

import static org.springframework.util.StringUtils.hasText;

/**
 * The limits on guide text, per language.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class GuideRules {

    public static final int MAX_ITEM_TEXT = 5_000;
    public static final int MAX_TITLE = 255;
    public static final int MAX_INTRODUCTION = 20_000;
    public static final int MAX_LABEL = 32;
    public static final int MAX_NOTE = 2_000;

    private GuideRules() {
    }

    /**
     * Swedish item text: not blank, at most {@value #MAX_ITEM_TEXT} characters.
     */
    public static String itemTextSv(final @Nullable String text) {
        if (!hasText(text)) {
            throw new BadRequestException("error.guide.text.blank");
        }
        return max(text.strip(), MAX_ITEM_TEXT, "error.guide.text.tooLong");
    }

    /**
     * English item text: optional, not blank when set. Blank clears it.
     */
    public static @Nullable String itemTextEn(final @Nullable String text) {
        return hasText(text) ? max(text.strip(), MAX_ITEM_TEXT, "error.guide.text.tooLong") : null;
    }

    public static String titleSv(final @Nullable String title) {
        if (!hasText(title)) {
            throw new BadRequestException("error.guide.title.blank");
        }
        return max(title.strip(), MAX_TITLE, "error.guide.title.tooLong");
    }

    public static @Nullable String titleEn(final @Nullable String title) {
        return hasText(title) ? max(title.strip(), MAX_TITLE, "error.guide.title.tooLong") : null;
    }

    /**
     * The Swedish introduction may be empty in a draft.
     */
    public static String introductionSv(final @Nullable String introduction) {
        return introduction == null ? "" : max(introduction.strip(), MAX_INTRODUCTION, "error.guide.introduction.tooLong");
    }

    public static @Nullable String introductionEn(final @Nullable String introduction) {
        return hasText(introduction) ? max(introduction.strip(), MAX_INTRODUCTION, "error.guide.introduction.tooLong") : null;
    }

    private static String max(final String text, final int max, final String code) {
        if (text.length() > max) {
            throw new BadRequestException(code, max);
        }
        return text;
    }
}
