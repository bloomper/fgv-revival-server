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
package nu.fgv.revival.server.member;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.catalog.CatalogService.Names;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The list of areas of responsibility, and through it what every list the super admin keeps does.
 *
 * @author Anders Jacobsson
 */
@ExtendWith(MockitoExtension.class)
class CapabilityServiceTest {

    @Mock
    private CapabilityRepository repository;
    @Mock
    private CallerService callerService;

    private CapabilityService service;
    private final List<Capability> capabilities = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new CapabilityService(repository, callerService, new AccessPolicy(), TestClocks.at(LocalDate.of(2026, 10, 23)));
        capabilities.add(capability("FINANCE", "Ekonomi", 0));
        capabilities.add(capability("COMMS", "Kommunikation", 1));
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(repository.findAllByOrderByPositionAscIdAsc()).thenAnswer(invocation -> List.copyOf(capabilities));
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                capabilities.stream().filter(capability -> capability.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.findAllById(any())).thenAnswer(invocation -> {
            final Iterable<String> ids = invocation.getArgument(0);
            final List<Capability> found = new ArrayList<>();

            ids.forEach(id -> capabilities.stream().filter(capability -> capability.getId().equals(id)).forEach(found::add));
            return found;
        });
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Capability capability(final String id, final String name, final int position) {
        final Capability capability = new Capability();

        capability.setId(id);
        capability.setNameSv(name);
        capability.setPosition(position);
        capability.setVersion(0L);
        return capability;
    }

    @Test
    void aNewCapabilityIsAppendedWithANewIdAndTrimmedNames() {
        final Capability created = service.create(new Names("  Scenografi ", ""));

        assertThat(created.getId()).hasSize(36);
        assertThat(created.getNameSv()).isEqualTo("Scenografi");
        assertThat(created.getNameEn()).isNull();
        assertThat(created.getPosition()).isEqualTo(2);
    }

    @Test
    void namesAreRequiredShortAndUniqueInSwedish() {
        assertThatThrownBy(() -> service.create(new Names(" ", "Set design"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(new Names("x".repeat(101), null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(new Names("ekonomi", null))).isInstanceOf(BadRequestException.class);
        assertThat(service.rename("FINANCE", 0L, new Names("Ekonomi", "Money")).getNameEn()).isEqualTo("Money");
    }

    @Test
    void onlyTheSuperAdminChangesTheList() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        assertThatThrownBy(() -> service.create(new Names("Scenografi", null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.retire("FINANCE", 0L)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.list(false)).hasSize(2);
    }

    @Test
    void aStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.rename("FINANCE", 3L, new Names("Pengar", null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void movingRenumbersTheWholeList() {
        final List<Capability> moved = service.move("COMMS", 0L, 0);

        assertThat(moved).extracting(Capability::getId).containsExactly("COMMS", "FINANCE");
        assertThat(moved).extracting(Capability::getPosition).containsExactly(0, 1);
    }

    @Test
    void aRetiredCapabilityIsListedOnlyOnRequestAndCanBeRestored() {
        service.retire("COMMS", 0L);

        assertThat(service.list(false)).extracting(Capability::getId).containsExactly("FINANCE");
        assertThat(service.list(true)).extracting(Capability::getId).containsExactly("FINANCE", "COMMS");
        assertThat(service.restore("COMMS", 0L).isRetired()).isFalse();
    }

    @Test
    void aRetiredCapabilityCanBeKeptButNotNewlyAssigned() {
        capabilities.get(1).setRetiredAt(capabilities.get(0).getCreatedAt());

        assertThatThrownBy(() -> service.requireAssignable(List.of("COMMS"), Set.of())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.requireAssignable(List.of("NOPE"), Set.of())).isInstanceOf(BadRequestException.class);
        service.requireAssignable(List.of("FINANCE", "COMMS"), Set.of("COMMS"));
    }

    @Test
    void aCapabilityInUseIsNotDeleted() {
        when(repository.findUsedIds()).thenReturn(List.of("FINANCE"));

        assertThat(service.inUse(List.of("FINANCE", "COMMS"))).containsEntry("FINANCE", true).containsEntry("COMMS", false);
        assertThatThrownBy(() -> service.delete("FINANCE", 0L)).isInstanceOf(BadRequestException.class);
        verify(repository, never()).delete(any());

        service.delete("COMMS", 0L);

        verify(repository).delete(capabilities.get(1));
    }
}
