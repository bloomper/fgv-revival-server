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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.spex.SpexDto;
import nu.fgv.revival.server.spex.SpexService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 0.1
 */
@ExtendWith(MockitoExtension.class)
class RevivalServiceTest {

    @Mock
    private RevivalRepository repository;
    @Mock
    private RevivalStatusChangeRepository statusChangeRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private SpexService spexService;
    @Mock
    private RevivalGuideVersions guideVersions;

    private RevivalService service;

    private static RevivalUpdateDto details(final LocalDate premiere, final List<LocalDate> showDates) {
        return new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, null, premiere, null, showDates, null);
    }

    @BeforeEach
    void setUp() {
        service = new RevivalService(repository, statusChangeRepository, callerService, new AccessPolicy(), spexService, guideVersions);
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(spexService.findTitles(anyCollection())).thenReturn(Map.of(1L, new SpexDto(1L, "Ali Baba", 1956, true)));
    }

    @Test
    void createSetsProposed() {
        final RevivalDto created = service.create(new RevivalCreateDto(1L, 2028, "  Ali Baba 2028 "));

        assertThat(created.status()).isEqualTo(RevivalStatus.PROPOSED);
        assertThat(created.workingTitle()).isEqualTo("Ali Baba 2028");
        verify(spexService).requireOriginal(1L);
        verify(guideVersions).pinToLatest(any(), any());
    }

    @Test
    void createNeedsSuperAdmin() {
        when(callerService.current()).thenReturn(TestCallers.observer());

        assertThatThrownBy(() -> service.create(new RevivalCreateDto(1L, 2028, "Ali Baba 2028"))).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1947, 2101})
    void theYearMustBeBetween1948And2100(final int year) {
        assertThatThrownBy(() -> service.create(new RevivalCreateDto(1L, year, "Ali Baba"))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void theWorkingTitleMustNotBeBlank() {
        assertThatThrownBy(() -> service.create(new RevivalCreateDto(1L, 2028, "  "))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void thePremiereMustBeInTheRevivalsYear() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThatThrownBy(() -> service.update(details(LocalDate.of(2029, 1, 1), null))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void showDatesMustBeOnOrAfterThePremiere() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThatThrownBy(() -> service.update(details(LocalDate.of(2028, 2, 10), List.of(LocalDate.of(2028, 2, 9)))))
                .isInstanceOf(BadRequestException.class);

        final RevivalDto updated = service.update(details(LocalDate.of(2028, 2, 10), List.of(LocalDate.of(2028, 2, 10), LocalDate.of(2028, 2, 12))));

        assertThat(updated.showDates()).hasSize(2);
    }

    @Test
    void showDatesAreRefusedWithoutAPremiere() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, null, null, true, List.of(LocalDate.of(2028, 3, 1)), null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void venuesAreTrimmedNotBlankAndAtMost20() {
        final Revival revival = TestCallers.revival(RevivalStatus.OPEN);

        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));

        assertThat(service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, null, null, null, null, List.of(" Chalmers kårhus "))).venues())
                .containsExactly("Chalmers kårhus");
        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, null, null, null, null, List.of(" "))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, null, null, null, null, Collections.nCopies(21, "Scen"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void changingTheSpexChecksItIsAnOriginal() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, 2L, null, null, null, null, null, null));

        verify(spexService).requireOriginal(2L);
    }

    @Test
    void aStaleVersionIsAConflict() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 2L, null, null, "Ny titel", null, null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aDirecteurMayEditDetailsButNotCoreFields() {
        when(callerService.current()).thenReturn(TestCallers.directeur());
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        service.update(details(LocalDate.of(2028, 2, 11), null));

        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, "Ny titel", null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSuperAdminStatusChangeIsRecorded() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.SETTLING)));

        final RevivalDto changed = service.changeStatus(new RevivalStatusChangeDto(REVIVAL_ID, RevivalStatus.SETTLING, RevivalStatus.APPROVED));
        final ArgumentCaptor<RevivalStatusChange> change = ArgumentCaptor.forClass(RevivalStatusChange.class);

        assertThat(changed.status()).isEqualTo(RevivalStatus.APPROVED);
        verify(statusChangeRepository).saveAndFlush(change.capture());
        assertThat(change.getValue().getFromStatus()).isEqualTo(RevivalStatus.SETTLING);
        assertThat(change.getValue().getToStatus()).isEqualTo(RevivalStatus.APPROVED);
        assertThat(change.getValue().getChangedBy()).isEqualTo(TestCallers.PERSON_ID);
    }

    @Test
    void aStaleFromIsAConflict() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.ORGANISING)));

        assertThatThrownBy(() -> service.changeStatus(new RevivalStatusChangeDto(REVIVAL_ID, RevivalStatus.APPROVED, RevivalStatus.ORGANISING)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void archivedRefusesEveryUpdateButASuperAdminStatusChange() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.ARCHIVED)));

        assertThatThrownBy(() -> service.update(details(LocalDate.of(2028, 2, 11), null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, "Ny titel", null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.changeStatus(new RevivalStatusChangeDto(REVIVAL_ID, RevivalStatus.ARCHIVED, RevivalStatus.SETTLING)).status())
                .isEqualTo(RevivalStatus.SETTLING);
    }

    @Test
    void anInvisibleRevivalIsNotFound() {
        when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.APPROVED)));

        assertThatThrownBy(() -> service.findById(REVIVAL_ID)).isInstanceOf(ResourceNotFoundException.class);
    }
}
