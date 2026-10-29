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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 1.0
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
    @Mock
    private ApplicationEventPublisher events;
    @Mock
    private RevivalDeletion deletion;
    @Mock
    private RevivalAnonymization anonymization;

    private RevivalService service;

    private static RevivalUpdateDto details(final LocalDate premiere, final List<LocalDate> showDates) {
        return new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, premiere, null,
                showDates == null ? null : showDates.stream().map(date -> new RevivalShowDto(date, null, null)).toList(), null);
    }

    private static RevivalUpdateDto shows(final RevivalShowDto... shows) {
        return new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, null, null, List.of(shows), null);
    }

    private static RevivalShowDto show(final int day, final String venue) {
        return show(day, null, venue);
    }

    private static RevivalShowDto show(final int day, final String time, final String venue) {
        return new RevivalShowDto(LocalDate.of(2028, 2, day), time, venue);
    }

    @BeforeEach
    void setUp() {
        service = new RevivalService(repository, statusChangeRepository, callerService, new AccessPolicy(), spexService, guideVersions, events, deletion, anonymization,
                Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC));
        lenient().when(spexService.requireOriginal(anyLong())).thenAnswer(invocation -> new SpexDto(invocation.getArgument(0), "Ali Baba", 1956, true));
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(spexService.findTitles(anyCollection())).thenReturn(Map.of(1L, new SpexDto(1L, "Ali Baba", 1956, true)));
    }

    @Test
    void createPublishesRevivalCreatedBeforePinning() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            final Revival revival = invocation.getArgument(0);

            revival.setId(7L);
            return revival;
        });

        service.create(new RevivalCreateDto(1L, 2028, null));

        final var order = inOrder(events, guideVersions);

        order.verify(events).publishEvent(new RevivalCreated(7L));
        order.verify(guideVersions).pinToLatest(any(), any());
    }

    @Test
    void createSetsProposed() {
        final RevivalDto created = service.create(new RevivalCreateDto(1L, 2028, null));

        assertThat(created.status()).isEqualTo(RevivalStatus.PROPOSED);
        verify(spexService).requireOriginal(1L);
        verify(guideVersions).pinToLatest(any(), any());
    }

    @Test
    void createNeedsSuperAdmin() {
        when(callerService.current()).thenReturn(TestCallers.observer());

        assertThatThrownBy(() -> service.create(new RevivalCreateDto(1L, 2028, null))).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1947, 2101})
    void theYearMustBeBetween1948And2100(final int year) {
        assertThatThrownBy(() -> service.create(new RevivalCreateDto(1L, year, null))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void theYearMustNotBeBeforeTheOriginalNorThisYear() {
        when(spexService.requireOriginal(2L)).thenReturn(new SpexDto(2L, "Framtiden", 2027, true));

        assertThatThrownBy(() -> service.create(new RevivalCreateDto(2L, 2026, null))).hasMessageContaining("error.revival.year.beforeOriginal");
        assertThatThrownBy(() -> service.create(new RevivalCreateDto(1L, 2025, null))).hasMessageContaining("error.revival.year.past");
        assertThat(service.create(new RevivalCreateDto(1L, 2026, null)).year()).isEqualTo(2026);
    }

    @Test
    void aRevivalAlreadyPlayedMayBeInThePastButNotBeforeTheOriginal() {
        when(spexService.requireOriginal(2L)).thenReturn(new SpexDto(2L, "Framtiden", 2020, true));

        assertThat(service.create(new RevivalCreateDto(1L, 2019, true)).year()).isEqualTo(2019);
        assertThatThrownBy(() -> service.create(new RevivalCreateDto(2L, 2019, true))).hasMessageContaining("error.revival.year.beforeOriginal");
    }

    @Test
    void aSpexHasAtMostOneRevivalAYear() {
        when(repository.existsBySpexIdAndYearAndIdNot(1L, 2028, -1L)).thenReturn(true);

        assertThatThrownBy(() -> service.create(new RevivalCreateDto(1L, 2028, null))).hasMessageContaining("error.revival.duplicate");
    }

    @Test
    void anUpdateKeepsTheYearAfterTheOriginalAndMovesItToThePastOnlyWhenAsked() {
        final Revival revival = TestCallers.revival(RevivalStatus.OPEN);

        revival.setPremiereDate(null);
        revival.getShows().clear();
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));

        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, 1955, null, null, null, null)))
                .hasMessageContaining("error.revival.year.beforeOriginal");
        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, 2020, null, null, null, null)))
                .hasMessageContaining("error.revival.year.past");
        assertThat(service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, 2020, null, null, null, true)).year()).isEqualTo(2020);
    }

    @Test
    void anUpdateKeepsAYearAlreadyInThePast() {
        final Revival revival = TestCallers.revival(RevivalStatus.OPEN);

        revival.setYear(2019);
        revival.setPremiereDate(null);
        revival.getShows().clear();
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));

        assertThat(service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, 2019, null, null, null, null)).year()).isEqualTo(2019);
    }

    @Test
    void onlyASuperAdminDeletesARevival() {
        final Revival revival = TestCallers.revival(RevivalStatus.OPEN);

        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));

        assertThatThrownBy(() -> service.delete(REVIVAL_ID, 2L)).isInstanceOf(ConflictException.class);
        service.delete(REVIVAL_ID, 3L);
        verify(deletion).delete(revival);

        when(callerService.current()).thenReturn(TestCallers.directeur());
        assertThatThrownBy(() -> service.delete(REVIVAL_ID, 3L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void thePremiereMustBeInTheRevivalsYear() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThatThrownBy(() -> service.update(details(LocalDate.of(2029, 1, 1), null))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void showsMustBeOnOrAfterThePremiere() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThatThrownBy(() -> service.update(details(LocalDate.of(2028, 2, 10), List.of(LocalDate.of(2028, 2, 9)))))
                .isInstanceOf(BadRequestException.class);

        final RevivalDto updated = service.update(details(LocalDate.of(2028, 2, 10), List.of(LocalDate.of(2028, 2, 10), LocalDate.of(2028, 2, 12))));

        assertThat(updated.shows()).hasSize(2);
    }

    @Test
    void showsAreRefusedWithoutAPremiere() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, null, null, true, List.of(new RevivalShowDto(LocalDate.of(2028, 3, 1), null, "Kårhuset")), null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void venuesAreTrimmedWithBlankAsUndecidedAndShowsAreOrderedByDateThenVenue() {
        final Revival revival = TestCallers.revival(RevivalStatus.OPEN);

        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));

        assertThat(service.update(shows(show(12, " RunAn "), show(11, " "), show(11, "Gasquen"), show(12, "Draken"))).shows())
                .containsExactly(show(11, "Gasquen"), show(11, null), show(12, "Draken"), show(12, "RunAn"))
                .doesNotHaveDuplicates();
    }

    @Test
    void timesAreHhMmOrderedBeforeTheVenueWithAnUndecidedTimeLast() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThat(service.update(shows(show(11, null, "RunAn"), show(11, " 19:00 ", "RunAn"), show(11, "14:00", "RunAn"), show(11, "", "Draken"),
                show(11, "14:00", "Gasquen"))).shows())
                .containsExactly(show(11, "14:00", "Gasquen"), show(11, "14:00", "RunAn"), show(11, "19:00", "RunAn"), show(11, null, "Draken"),
                        show(11, null, "RunAn"));
        for (final String invalid : List.of("24:00", "9:30", "19.00", "19:60", "19:00:00", "kväll")) {
            assertThatThrownBy(() -> service.update(shows(show(11, invalid, "RunAn")))).as(invalid).hasMessageContaining("error.revival.shows.timeInvalid");
        }
        assertThat(service.update(shows(show(11, "00:00", null), show(11, "23:59", null))).shows()).hasSize(2);
    }

    @Test
    void aMatineeAndAnEveningShowAtTheSameVenueAreBothAllowed() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThat(service.update(shows(show(11, "14:00", "RunAn"), show(11, "19:00", "RunAn"))).shows()).hasSize(2);
        assertThatThrownBy(() -> service.update(shows(show(11, "19:00", "RunAn"), show(11, " 19:00", "RunAn "))))
                .hasMessageContaining("error.revival.shows.duplicate");
    }

    @Test
    void theSameDateAndVenueIsRefusedTwice() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThatThrownBy(() -> service.update(shows(show(11, "RunAn"), show(11, " RunAn"))))
                .hasMessageContaining("error.revival.shows.duplicate");
        assertThatThrownBy(() -> service.update(shows(show(11, null), show(11, ""))))
                .hasMessageContaining("error.revival.shows.duplicate");
        assertThat(service.update(shows(show(11, "RunAn"), show(12, "RunAn"), show(11, "Gasquen"))).shows()).hasSize(3);
    }

    @Test
    void atMost50ShowsWithVenuesOfAtMost255Characters() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        final RevivalShowDto[] many = java.util.stream.IntStream.range(0, 51).mapToObj(n -> show(10 + n / 10, "Scen " + n)).toArray(RevivalShowDto[]::new);

        assertThatThrownBy(() -> service.update(shows(many))).hasMessageContaining("error.revival.shows.tooMany");
        assertThat(service.update(shows(java.util.Arrays.copyOf(many, 50))).shows()).hasSize(50);
        assertThatThrownBy(() -> service.update(shows(show(11, "x".repeat(256))))).hasMessageContaining("error.revival.shows.venueTooLong");
    }

    @Test
    void replacingTheShowsKeepsTheOnesThatStay() {
        final Revival revival = TestCallers.revival(RevivalStatus.OPEN);

        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        service.update(shows(show(11, "RunAn"), show(12, null)));

        final RevivalShow kept = revival.getShows().getFirst();

        service.update(shows(show(11, "RunAn"), show(13, "Draken")));

        assertThat(revival.getShows()).hasSize(2).first().isSameAs(kept);
        assertThat(revival.getShows()).extracting(RevivalShow::toDto).containsExactlyInAnyOrder(show(11, "RunAn"), show(13, "Draken"));
        assertThat(revival.getShows()).allSatisfy(show -> assertThat(show.getRevival()).isSameAs(revival));
    }

    @Test
    void changingTheSpexChecksItIsAnOriginal() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, 2L, null, null, null, null, null));

        verify(spexService).requireOriginal(2L);
    }

    @Test
    void aStaleVersionIsAConflict() {
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 2L, null, 2029, null, null, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aDirecteurMayEditDetailsButNotCoreFields() {
        when(callerService.current()).thenReturn(TestCallers.directeur());
        when(repository.findById(REVIVAL_ID)).thenReturn(Optional.of(TestCallers.revival(RevivalStatus.OPEN)));

        service.update(details(LocalDate.of(2028, 2, 11), null));

        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, 2029, null, null, null, null)))
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
        assertThatThrownBy(() -> service.update(new RevivalUpdateDto(REVIVAL_ID, 3L, null, 2029, null, null, null, null)))
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
