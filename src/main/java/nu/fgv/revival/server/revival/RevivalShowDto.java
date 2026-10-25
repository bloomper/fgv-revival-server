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
import java.util.Comparator;

/**
 * A show as GraphQL's {@code RevivalShow} and {@code RevivalShowInput}: a date and, once decided, a start time as
 * {@code HH:mm} in Swedish time (Europe/Stockholm) and a venue.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record RevivalShowDto(LocalDate date, @Nullable String time, @Nullable String venue) {

    /**
     * By date, then time, then venue, with an undecided time or venue last. {@code HH:mm} sorts as the time it is.
     */
    public static final Comparator<RevivalShowDto> ORDER = Comparator.comparing(RevivalShowDto::date)
            .thenComparing(RevivalShowDto::time, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(RevivalShowDto::venue, Comparator.nullsLast(Comparator.naturalOrder()));
}
