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

import nu.fgv.revival.server.spex.SpexDto;
import nu.fgv.revival.server.spex.SpexService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * A revival's name: its spex's title and its year, e.g. "Ali Baba 2028", or a generic name while Spexregister is
 * unavailable.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Component
public class RevivalTitles {

    private final SpexService spexService;
    private final MessageSource messageSource;

    public RevivalTitles(final SpexService spexService, final MessageSource messageSource) {
        this.spexService = spexService;
        this.messageSource = messageSource;
    }

    public String of(final SpexDto spex, final int year, final Locale locale) {
        return spex.title() == null
                ? messageSource.getMessage("revival.title.unavailable", new Object[]{year}, locale)
                : "%s %d".formatted(spex.title(), year);
    }

    public String of(final Long spexId, final int year, final Locale locale) {
        return of(spexService.findTitles(List.of(spexId)).get(spexId), year, locale);
    }

    public String of(final Revival revival, final Locale locale) {
        return of(revival.getSpexId(), revival.getYear(), locale);
    }

    /**
     * As {@link #of(Revival, Locale)}, but from the cached spex only, for work that must not depend on Spexregister.
     */
    public String ofCached(final Revival revival, final Locale locale) {
        return of(spexService.findCached(revival.getSpexId()).orElseGet(() -> SpexDto.unavailable(revival.getSpexId())), revival.getYear(), locale);
    }
}
