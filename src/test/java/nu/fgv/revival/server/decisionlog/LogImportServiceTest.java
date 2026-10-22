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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The import's rules (FR-004, FR-036–FR-042): who may import, only into an empty log, owners as chosen, the Nobel
 * type, and guide rows moved after the imported ones.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@ExtendWith(MockitoExtension.class)
class LogImportServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final long KIM = 11L;

    @Mock
    private LogEntryRepository entryRepository;
    @Mock
    private LogImportRepository importRepository;
    @Mock
    private LogEntryCounterRepository counterRepository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private OwnerResolver ownerResolver;
    @Mock
    private PersonNames personNames;

    private final LogEntryCounter counter = new LogEntryCounter(REVIVAL_ID);
    private final List<LogEntry> saved = new ArrayList<>();
    private LogImportService service;
    private Revival revival;

    @BeforeEach
    void setUp() {
        service = new LogImportService(entryRepository, importRepository, new LogEntryCounters(counterRepository), revivalRepository,
                callerService, new AccessPolicy(), ownerResolver, personNames, TestClocks.at(TODAY));
        revival = TestCallers.revival(RevivalStatus.ORGANISING);
        lenient().when(callerService.current()).thenReturn(TestCallers.directeur());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(counterRepository.findForUpdate(REVIVAL_ID)).thenReturn(Optional.of(counter));
        lenient().when(entryRepository.save(any())).thenAnswer(invocation -> {
            saved.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        lenient().when(importRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(ownerResolver.activeMembers(REVIVAL_ID)).thenReturn(new LinkedHashMap<>(Map.of(KIM, "Kim Svensson")));
    }

    private LogImportDto importAliBaba(final Map<String, Long> owners) {
        return service.importLog(REVIVAL_ID, "logg.xlsx", LogWorkbookFixtures.aliBaba(), new LogImportService.Choices(new HashMap<>(owners), null));
    }

    @Test
    void importsEveryRowWithItsNumberAndTheChosenOwners() {
        final LogImportDto result = importAliBaba(Map.of("Kim", KIM));

        assertThat(result.rowCount()).isEqualTo(12);
        assertThat(result.layout()).isEqualTo(LogImportLayout.ALI_BABA);
        assertThat(saved).hasSize(12).allSatisfy(entry -> assertThat(entry.getOrigin()).isEqualTo(LogEntryOrigin.IMPORT));
        assertThat(saved).extracting(LogEntry::getNumber).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertThat(saved.getFirst().getOwner()).isEqualTo(Owner.member(KIM));
        assertThat(saved.get(5).getOwner()).isEqualTo(Owner.named("Robin"));
        assertThat(counter.getLastNumber()).isEqualTo(12);
    }

    @Test
    void namesLeftOutOrMappedToNullAreFreeTextOwners() {
        importAliBaba(Map.of());

        assertThat(saved.getFirst().getOwner()).isEqualTo(Owner.named("Kim"));
    }

    @Test
    void anOwnerIdThatIsNotAnActiveMemberIsRefused() {
        assertThatThrownBy(() -> importAliBaba(Map.of("Kim", 99L))).isInstanceOf(BadRequestException.class);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void nobelRowsGetTheChosenTypeTodayAndTheirMeetingTag() {
        service.importLog(REVIVAL_ID, "dagbok.xlsx", LogWorkbookFixtures.nobel(), new LogImportService.Choices(Map.of(), LogEntryType.DECISION));

        assertThat(saved).extracting(LogEntry::getType).containsOnly(LogEntryType.DECISION);
        assertThat(saved).extracting(LogEntry::getDate).containsOnly(TODAY);
        assertThat(saved.getFirst().getTags()).containsExactly("möte 5");
    }

    @Test
    void nobelRowsAreToDosByDefault() {
        service.importLog(REVIVAL_ID, "dagbok.xlsx", LogWorkbookFixtures.nobel(), LogImportService.Choices.none());

        assertThat(saved).extracting(LogEntry::getType).containsOnly(LogEntryType.TODO);
    }

    @Test
    void guideRowsAreMovedAfterTheImportedOnes() {
        final LogEntry question = new LogEntry();

        question.setNumber(1);
        question.setOrigin(LogEntryOrigin.GUIDE);
        when(entryRepository.findByRevivalIdAndOriginOrderByNumberAsc(REVIVAL_ID, LogEntryOrigin.GUIDE)).thenReturn(List.of(question));
        when(entryRepository.saveAndFlush(question)).thenReturn(question);

        importAliBaba(Map.of());

        assertThat(question.getNumber()).isEqualTo(13);
        assertThat(counter.getLastNumber()).isEqualTo(13);
    }

    @Test
    void aLogWithRowsEnteredByPeopleOrAnEarlierImportIsRefused() {
        when(entryRepository.existsByRevivalIdAndOriginIn(eq(REVIVAL_ID), anyCollection())).thenReturn(true);
        assertThatThrownBy(() -> importAliBaba(Map.of())).isInstanceOf(ConflictException.class);

        when(entryRepository.existsByRevivalIdAndOriginIn(eq(REVIVAL_ID), anyCollection())).thenReturn(false);
        when(importRepository.existsByRevivalId(REVIVAL_ID)).thenReturn(true);
        assertThatThrownBy(() -> importAliBaba(Map.of())).isInstanceOf(ConflictException.class);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void onlyAVisibleDirecteurImports() {
        for (final Caller caller : List.of(TestCallers.steeringGroup(), TestCallers.superAdmin(), TestCallers.observer())) {
            when(callerService.current()).thenReturn(caller);
            assertThatThrownBy(() -> importAliBaba(Map.of())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.preview(REVIVAL_ID, "logg.xlsx", LogWorkbookFixtures.aliBaba())).isInstanceOf(AccessDeniedException.class);
        }
        when(callerService.current()).thenReturn(TestCallers.nobody());
        assertThatThrownBy(() -> importAliBaba(Map.of())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anArchivedRevivalIsRefused() {
        revival.setStatus(RevivalStatus.ARCHIVED);
        when(callerService.current()).thenReturn(TestCallers.superAdmin());

        assertThatThrownBy(() -> importAliBaba(Map.of())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void thePreviewCountsAndSuggestsOwnersWithoutStoringAnything() {
        when(ownerResolver.resolve(eq(REVIVAL_ID), eq("Kim"), any())).thenReturn(new OwnerResolver.Resolution.Member(KIM, "Kim Svensson"));
        when(ownerResolver.resolve(eq(REVIVAL_ID), eq("Robin"), any())).thenReturn(new OwnerResolver.Resolution.FreeText("Robin"));

        final LogImportPreviewDto preview = service.preview(REVIVAL_ID, "logg.xlsx", LogWorkbookFixtures.aliBaba());

        assertThat(preview.rowCount()).isEqualTo(12);
        assertThat(preview.byType()).containsEntry(LogEntryType.DECISION, 7).containsEntry(LogEntryType.TODO, 4).containsEntry(LogEntryType.QUESTION, 1);
        assertThat(preview.byStatus()).containsEntry(LogEntryStatus.DONE, 7).containsEntry(LogEntryStatus.OPEN, 5);
        assertThat(preview.numbers()).isEqualTo(new LogImportPreviewDto.Numbers(1, 12));
        assertThat(preview.owners()).containsExactly(new LogImportPreviewDto.OwnerName("Kim", 8, KIM), new LogImportPreviewDto.OwnerName("Robin", 4, null));
        verify(entryRepository, never()).save(any());
        verify(importRepository, never()).saveAndFlush(any());
    }

    @Test
    void theImportIsRecordedWithFileLayoutRowsAndWarnings() {
        final ArgumentCaptor<LogImport> record = ArgumentCaptor.forClass(LogImport.class);

        service.importLog(REVIVAL_ID, "dagbok.xlsx", LogWorkbookFixtures.nobel(), LogImportService.Choices.none());

        verify(importRepository, atLeastOnce()).saveAndFlush(record.capture());
        assertThat(record.getValue().getFileName()).isEqualTo("dagbok.xlsx");
        assertThat(record.getValue().getLayout()).isEqualTo(LogImportLayout.NOBEL);
        assertThat(record.getValue().getRowCount()).isEqualTo(5);
        assertThat(record.getValue().getWarnings()).extracting(LogImportWarning::code).contains("HEADER_REPEATED", "EXTRA_COLUMNS");
    }
}
