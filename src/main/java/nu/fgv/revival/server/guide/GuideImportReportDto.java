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

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * GraphQL's {@code GuideImportReport}, and the {@code report} of {@code POST /api/guide/import} (contracts/http.md).
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
public record GuideImportReportDto(String fileName,
                                   Counts totals,
                                   List<ChapterCounts> chapters,
                                   List<Warning> warnings,
                                   Instant createdAt) {

    public record Counts(int sections, int tips, int requirements, int questions, int texts) {

        public static final Counts NONE = new Counts(0, 0, 0, 0, 0);

        public Counts plus(final Counts other) {
            return new Counts(sections + other.sections, tips + other.tips, requirements + other.requirements,
                    questions + other.questions, texts + other.texts);
        }
    }

    public record ChapterCounts(Phase phase, Counts counts) {
    }

    public record Warning(GuideImportWarningCode code, @Nullable Phase phase, @Nullable String sectionTitle, @Nullable String detail) {
    }
}
