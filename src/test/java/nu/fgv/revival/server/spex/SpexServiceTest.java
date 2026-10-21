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

package nu.fgv.revival.server.spex;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.config.CacheConfig;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 0.1
 */
@ExtendWith(MockitoExtension.class)
class SpexServiceTest {

    private static final List<SpexDto> SPEXES = List.of(
            new SpexDto(1L, "Ali Baba", 1956, true),
            new SpexDto(2L, "Nobel", 1988, true),
            new SpexDto(3L, "Snorre", 1974, true),
            new SpexDto(4L, "Ålänningen", 1960, true));

    @Mock
    private SpexregisterClient client;
    @Mock
    private CallerService callerService;

    private SpexService service;

    @BeforeEach
    void setUp() {
        service = new SpexService(client, new CacheConfig().cacheManager(), callerService, new AccessPolicy());
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
    }

    @Test
    void theListIsCachedAndReadOnce() {
        when(client.originalSpexes()).thenReturn(SPEXES);

        service.search("ali", 20);
        service.search("nobel", 20);
        service.findTitles(List.of(1L, 2L));

        verify(client, times(1)).originalSpexes();
    }

    @Test
    void searchIsLocalAndIgnoresCaseAndAccents() {
        when(client.originalSpexes()).thenReturn(SPEXES);

        assertThat(service.search("ali", 20)).extracting(SpexDto::title).containsExactly("Ali Baba");
        assertThat(service.search("ALANN", 20)).extracting(SpexDto::title).containsExactly("Ålänningen");
        assertThat(service.search("", 2)).hasSize(2);
    }

    @Test
    void onlySuperAdminsMaySearch() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        assertThatThrownBy(() -> service.search("ali", 20)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findTitlesResolvesSeveralIdsFromOneList() {
        when(client.originalSpexes()).thenReturn(SPEXES);

        final Map<Long, SpexDto> titles = service.findTitles(List.of(1L, 3L, 1L));

        assertThat(titles).containsOnlyKeys(1L, 3L);
        assertThat(titles.get(3L).title()).isEqualTo("Snorre");
    }

    @Test
    void anErrorMakesTitlesUnavailable() {
        when(client.originalSpexes()).thenThrow(new SpexregisterClient.SpexregisterCallException("down"));

        final Map<Long, SpexDto> titles = service.findTitles(List.of(1L));

        assertThat(titles.get(1L).available()).isFalse();
        assertThat(titles.get(1L).title()).isNull();
    }

    @Test
    void searchFailsWhenSpexregisterIsUnavailable() {
        when(client.originalSpexes()).thenThrow(new SpexregisterClient.SpexregisterCallException("down"));

        assertThatThrownBy(() -> service.search("ali", 20)).isInstanceOf(SpexregisterUnavailableException.class);
    }

    @Test
    void requireOriginalAcceptsAnOriginalSpex() {
        when(client.originalSpexes()).thenReturn(SPEXES);

        service.requireOriginal(2L);
    }

    @Test
    void requireOriginalRefusesAnUnknownSpexOrARevival() {
        when(client.originalSpexes()).thenReturn(SPEXES);
        when(client.spexById(98L)).thenReturn(Optional.empty());
        when(client.spexById(99L)).thenReturn(Optional.of(new SpexregisterClient.SpexNode("99", "2004", "Ali Baba", true)));

        assertThatThrownBy(() -> service.requireOriginal(98L)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.requireOriginal(99L)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireOriginalFailsOnTimeout() {
        when(client.originalSpexes()).thenThrow(new SpexregisterClient.SpexregisterCallException("timeout"));

        assertThatThrownBy(() -> service.requireOriginal(1L)).isInstanceOf(SpexregisterUnavailableException.class);
    }
}
