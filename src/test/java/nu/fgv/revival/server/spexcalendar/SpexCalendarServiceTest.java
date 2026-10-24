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
package nu.fgv.revival.server.spexcalendar;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The current Chalmersspex's show dates: the super admin keeps them, super admins and observers read them.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@ExtendWith(MockitoExtension.class)
class SpexCalendarServiceTest {

    private final List<SpexCalendarDate> dates = new ArrayList<>();
    @Mock
    private SpexCalendarDateRepository repository;
    @Mock
    private CallerService callerService;
    private SpexCalendarService service;

    @BeforeEach
    void setUp() {
        service = new SpexCalendarService(repository, callerService, new AccessPolicy());
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(repository.findAllByOrderByDateAscIdAsc()).thenAnswer(invocation -> List.copyOf(dates));
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                dates.stream().filter(date -> date.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            final SpexCalendarDate date = invocation.getArgument(0);

            if (date.getId() == null) {
                date.setId(1L + dates.size());
                date.setVersion(0L);
                dates.add(date);
            }
            return date;
        });
    }

    @Test
    void datesAreListedWithinTheRange() {
        service.create(LocalDate.of(2027, 2, 6), " Chalmersspexet: premiär ");
        service.create(LocalDate.of(2027, 2, 13), "Chalmersspexet: föreställning");
        service.create(LocalDate.of(2027, 3, 1), "Chalmersspexet: Stockholm");

        assertThat(service.list(null, null)).extracting(SpexCalendarDateDto::label)
                .containsExactly("Chalmersspexet: premiär", "Chalmersspexet: föreställning", "Chalmersspexet: Stockholm");
        assertThat(service.list(LocalDate.of(2027, 2, 13), LocalDate.of(2027, 2, 28))).hasSize(1);

        when(callerService.current()).thenReturn(TestCallers.observer());
        assertThat(service.list(LocalDate.of(2027, 2, 7), null)).hasSize(2);
    }

    @Test
    void labelsAreValidatedAndVersionsChecked() {
        assertThatThrownBy(() -> service.create(LocalDate.of(2027, 2, 6), " ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(LocalDate.of(2027, 2, 6), "x".repeat(101))).isInstanceOf(BadRequestException.class);

        final SpexCalendarDateDto created = service.create(LocalDate.of(2027, 2, 6), "Premiär");

        assertThat(service.update(created.id(), 0L, LocalDate.of(2027, 2, 7), "Premiär").date()).isEqualTo(LocalDate.of(2027, 2, 7));
        assertThatThrownBy(() -> service.delete(created.id(), 4L)).isInstanceOf(ConflictException.class);
        service.delete(created.id(), 0L);
        verify(repository).delete(dates.getFirst());
    }

    @Test
    void onlyTheSuperAdminKeepsTheDatesAndOnlyItAndObserversReadThem() {
        when(callerService.current()).thenReturn(TestCallers.observer());
        assertThatThrownBy(() -> service.create(LocalDate.of(2027, 2, 6), "Premiär")).isInstanceOf(AccessDeniedException.class);

        when(callerService.current()).thenReturn(TestCallers.directeur());
        assertThatThrownBy(() -> service.list(null, null)).isInstanceOf(AccessDeniedException.class);
    }
}
