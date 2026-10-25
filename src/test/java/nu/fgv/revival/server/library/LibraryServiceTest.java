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
package nu.fgv.revival.server.library;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.guide.GuideAccess;
import nu.fgv.revival.server.guide.GuideItemRepository;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * The reference library the super admin keeps.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class LibraryServiceTest {

    private final List<LibraryEntry> entries = new ArrayList<>();
    @Mock
    private LibraryEntryRepository repository;
    @Mock
    private GuideItemRepository itemRepository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private CallerService callerService;
    private LibraryService service;

    private static LibraryEntry entry(final long id, final LibraryEntryKind kind, final String name) {
        final LibraryEntry entry = new LibraryEntry();

        entry.setId(id);
        entry.setKind(kind);
        entry.setNameSv(name);
        entry.setVersion(0L);
        return entry;
    }

    private static LibraryService.Definition definition(final LibraryEntryKind kind, final String name, final String url) {
        return new LibraryService.Definition(kind, new LibraryService.Text(name, null), new LibraryService.Text(" Stor scen. ", "Big stage."),
                new LibraryService.Text("Nära", null), new LibraryService.Text(" ", "Ignored"), null, null, null, url);
    }

    @BeforeEach
    void setUp() {
        final AccessPolicy accessPolicy = new AccessPolicy();

        service = new LibraryService(repository, itemRepository, callerService, accessPolicy, new GuideAccess(accessPolicy, revivalRepository),
                TestClocks.at(LocalDate.of(2026, 10, 25)));
        entries.add(entry(1L, LibraryEntryKind.VENUE, "Runan"));
        entries.add(entry(2L, LibraryEntryKind.VENUE, "Kårhuset"));
        entries.add(entry(3L, LibraryEntryKind.CONTACT_ROLE, "Chalmersspexets directeur"));
        entries.get(1).setRetiredAt(Instant.parse("2026-01-01T00:00:00Z"));
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(repository.findAll()).thenAnswer(invocation -> List.copyOf(entries));
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                entries.stream().filter(entry -> entry.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.findAllById(any())).thenAnswer(invocation -> {
            final Iterable<Long> ids = invocation.getArgument(0);
            final List<LibraryEntry> found = new ArrayList<>();

            ids.forEach(id -> entries.stream().filter(entry -> entry.getId().equals(id)).findFirst().ifPresent(found::add));
            return found;
        });
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void theListIsByKindThenNameWithoutRetiredEntries() {
        assertThat(service.list(false)).extracting(entry -> entry.name().sv()).containsExactly("Runan", "Chalmersspexets directeur");
        assertThat(service.list(true)).extracting(entry -> entry.name().sv()).containsExactly("Kårhuset", "Runan", "Chalmersspexets directeur");
    }

    @Test
    void textsAreTrimmedAndABlankSwedishTextClearsIt() {
        final LibraryEntryDto created = service.create(definition(LibraryEntryKind.VENUE, " Lindholmen ", " https://example.org/scen "));

        assertThat(created.name().sv()).isEqualTo("Lindholmen");
        assertThat(created.description()).isNotNull();
        assertThat(created.description().sv()).isEqualTo("Stor scen.");
        assertThat(created.description().en()).isEqualTo("Big stage.");
        assertThat(created.cons()).isNull();
        assertThat(created.url()).isEqualTo("https://example.org/scen");
    }

    @Test
    void aLinkMustBeHttps() {
        assertThatThrownBy(() -> service.create(definition(LibraryEntryKind.TICKET_SERVICE, "Biljett", "http://example.org")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void theKindStaysAsCreated() {
        final LibraryEntryDto updated = service.update(1L, 0L, definition(LibraryEntryKind.ROLE_DESCRIPTION, "RunAn", ""));

        assertThat(updated.kind()).isEqualTo(LibraryEntryKind.VENUE);
        assertThat(updated.name().sv()).isEqualTo("RunAn");
        assertThat(updated.url()).isNull();
    }

    @Test
    void aStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.retire(1L, 3L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyTheSuperAdminChangesTheLibraryAndObserversReadIt() {
        lenient().when(callerService.current()).thenReturn(TestCallers.observer());

        assertThat(service.list(false)).hasSize(2);
        assertThatThrownBy(() -> service.retire(1L, 0L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aRetiredEntryCanBeLinkedOnlyByAnItemThatAlreadyLinksToIt() {
        assertThat(service.validLinks(List.of(1L, 2L, 1L), List.of(2L))).containsExactly(1L, 2L);
        assertThatThrownBy(() -> service.validLinks(List.of(2L), List.of())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void retiringKeepsTheFirstDateAndRestoringClearsIt() {
        final Instant first = entries.get(1).getRetiredAt();

        assertThat(service.retire(2L, 0L).retired()).isTrue();
        assertThat(entries.get(1).getRetiredAt()).isEqualTo(first);
        assertThat(service.restore(2L, 0L).retired()).isFalse();
    }
}
