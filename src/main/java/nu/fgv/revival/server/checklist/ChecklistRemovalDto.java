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
package nu.fgv.revival.server.checklist;

import nu.fgv.revival.server.guide.GuideVersionView;
import org.jspecify.annotations.Nullable;

/**
 * A chapter checklist that moving the revival to another guide version would delete, with the items that would go
 * with it and the log rows linked to them, which stay but lose the link.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
public record ChecklistRemovalDto(Long checklistId,
                                  String chapterKey,
                                  GuideVersionView.@Nullable Text chapterTitle,
                                  int itemCount,
                                  int doneCount,
                                  int linkedLogEntryCount) {
}
