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

package nu.fgv.revival.server.revival;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;

/**
 * Omitted fields are left unchanged. Lists replace the whole list.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
public record RevivalUpdateDto(Long id,
                               Long version,
                               @Nullable Long spexId,
                               @Nullable Integer year,
                               @Nullable LocalDate premiereDate,
                               @Nullable Boolean clearPremiereDate,
                               @Nullable List<LocalDate> showDates,
                               @Nullable List<String> venues) {

    boolean changesCore() {
        return spexId != null || year != null;
    }

    boolean changesDetails() {
        return premiereDate != null || Boolean.TRUE.equals(clearPremiereDate) || showDates != null || venues != null;
    }
}
