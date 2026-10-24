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
package nu.fgv.revival.server.material;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.catalog.CatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The link services the super admin keeps (M8-FR-1): hosts are lower-case host names, at most ten, and a host belongs to
 * at most one active service; the home page is https.
 *
 * @author Anders Jacobsson
 */
@ExtendWith(MockitoExtension.class)
class LinkServiceCatalogTest {

    private final List<LinkService> services = new ArrayList<>();
    @Mock
    private LinkServiceRepository repository;
    @Mock
    private CallerService callerService;
    private LinkServiceCatalog catalog;

    private static CatalogService.Names names(final String sv) {
        return new CatalogService.Names(sv, null);
    }

    @BeforeEach
    void setUp() {
        catalog = new LinkServiceCatalog(repository, callerService, new AccessPolicy(), TestClocks.at(LocalDate.of(2026, 10, 23)));
        services.addAll(TestLinkServices.seeded());
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(repository.findAllByOrderByPositionAscIdAsc()).thenAnswer(invocation -> List.copyOf(services));
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                services.stream().filter(service -> service.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private LinkService find(final String id) {
        return services.stream().filter(service -> service.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aNewServiceHasLowerCaseHostsAndAnHttpsHomePage() {
        final LinkService created = catalog.create(names("Spexipedia"), List.of(" Wiki.FGV.nu ", "wiki.fgv.nu", "spexipedia.example.org"),
                "https://wiki.fgv.nu/");

        assertThat(created.getHosts()).containsExactlyInAnyOrder("wiki.fgv.nu", "spexipedia.example.org");
        assertThat(created.getHomeUrl()).isEqualTo("https://wiki.fgv.nu/");
        assertThat(created.getPosition()).isEqualTo(services.size());
    }

    @Test
    void aHostAnotherActiveServiceHoldsIsRefused() {
        assertThatThrownBy(() -> catalog.create(names("Drive 2"), List.of("drive.google.com"), null))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.linkService.host.taken");
        assertThatThrownBy(() -> catalog.update("SPEXFLIX", 0L, names("Spexflix"), List.of("youtu.be"), null))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.linkService.host.taken");
        verify(repository, never()).saveAndFlush(any());

        // Its own hosts are its own
        assertThat(catalog.update("YOUTUBE", 0L, names("YouTube"), List.of("youtube.com"), null).getHosts()).containsExactly("youtube.com");
    }

    @Test
    void aRetiredServiceDoesNotHoldItsHostsButCannotBeRestoredWhileAnotherDoes() {
        find("YOUTUBE").setRetiredAt(Instant.parse("2026-10-01T10:00:00Z"));

        final LinkService created = catalog.create(names("Video"), List.of("youtu.be"), null);

        services.add(created);
        assertThat(created.getHosts()).containsExactly("youtu.be");
        assertThatThrownBy(() -> catalog.restore("YOUTUBE", 0L)).hasFieldOrPropertyWithValue("detailMessageCode", "error.linkService.host.taken");
    }

    @Test
    void atMostTenHostsAndOnlyHostNames() {
        final List<String> eleven = new ArrayList<>();

        for (int index = 0; index < 11; index++) {
            eleven.add("h" + index + ".example.org");
        }
        assertThatThrownBy(() -> catalog.create(names("Många"), eleven, null)).hasFieldOrPropertyWithValue("detailMessageCode", "error.linkService.hosts.tooMany");
        assertThatThrownBy(() -> catalog.create(names("Fel"), List.of("https://drive.google.com/"), null))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.linkService.host.invalid");
        assertThat(catalog.create(names("Inga"), Collections.emptyList(), null).getHosts()).isEmpty();
    }

    @Test
    void theHomePageIsHttps() {
        assertThatThrownBy(() -> catalog.create(names("Osäker"), List.of(), "http://example.org/"))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.link.https");
        assertThat(catalog.create(names("Utan"), List.of(), " ").getHomeUrl()).isNull();
    }

    @Test
    void onlyTheSuperAdminKeepsTheList() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        assertThatThrownBy(() -> catalog.create(names("Spexipedia"), List.of(), null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void recognitionUsesTheActiveServicesInOrder() {
        assertThat(catalog.recognise("docs.google.com")).map(LinkService::getId).contains("GOOGLE_DRIVE");
        assertThat(catalog.recognise("example.org")).isEmpty();
    }
}
