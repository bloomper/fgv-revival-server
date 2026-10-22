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


package nu.fgv.revival.server.decisionlog;

import nu.fgv.revival.server.member.PersonRefDto;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * What an import would do, stored nowhere (contracts/http.md, preview). For the Nobel layout {@code byType} counts every
 * row as the default type.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public record LogImportPreviewDto(String fileName,
                                  LogImportLayout layout,
                                  String sheet,
                                  List<String> ignoredSheets,
                                  int rowCount,
                                  Map<LogEntryType, Integer> byType,
                                  Map<LogEntryStatus, Integer> byStatus,
                                  Numbers numbers,
                                  List<OwnerName> owners,
                                  List<PersonRefDto> members,
                                  List<LogImportWarning> warnings,
                                  int guideRowsRenumbered) {

    public record Numbers(int first, int last) {
    }

    public record OwnerName(String name, int rows, @Nullable Long suggestedPersonId) {
    }
}
