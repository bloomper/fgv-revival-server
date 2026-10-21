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

package nu.fgv.revival.server.guide.suggestion;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.guide.GuideChapterRepository;
import nu.fgv.revival.server.guide.GuideDraftService;
import nu.fgv.revival.server.guide.GuideItemRepository;
import nu.fgv.revival.server.guide.GuideItemType;
import nu.fgv.revival.server.guide.GuideSectionRepository;
import nu.fgv.revival.server.guide.GuideVersion;
import nu.fgv.revival.server.guide.GuideVersionRepository;
import nu.fgv.revival.server.guide.GuideVersionStatus;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.guide.GuideVersionViews;
import nu.fgv.revival.server.guide.Phase;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Creating, withdrawing and reopening suggestions (FR-031, FR-032, FR-034, FR-015). Accepting against a real draft is in
 * {@code GuideSuggestionGraphqlApiIntegrationTest}.
 *
 * @author Anders Jacobsson
 * @since 0.2
 */
@ExtendWith(MockitoExtension.class)
class GuideSuggestionServiceTest {

    private static final long VERSION_ID = 5L;

    @Mock
    private GuideSuggestionRepository repository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private GuideVersionRepository versionRepository;
    @Mock
    private GuideChapterRepository chapterRepository;
    @Mock
    private GuideSectionRepository sectionRepository;
    @Mock
    private GuideItemRepository itemRepository;
    @Mock
    private GuideVersionViews views;
    @Mock
    private GuideDraftService draftService;
    @Mock
    private CallerService callerService;

    private GuideSuggestionService service;
    private Revival revival;

    private static GuideVersionView view() {
        final GuideVersionView.Text text = new GuideVersionView.Text("Ett tips.", null, "Ett tips.", null);
        final List<GuideVersionView.Chapter> chapters = new ArrayList<>();

        for (final Phase phase : Phase.values()) {
            chapters.add(new GuideVersionView.Chapter((long) phase.ordinal(), phase, GuideVersionView.Text.plain("Kapitel " + phase, null),
                    phase == Phase.B ? List.of(new GuideVersionView.Item(1L, "tip", phase, null, GuideItemType.TIP, text, 0L)) : List.of(),
                    phase == Phase.B ? List.of(new GuideVersionView.Section(2L, "kalas", phase, GuideVersionView.Text.plain("Kalas", null), List.of(), 0L)) : List.of(),
                    0L));
        }
        return new GuideVersionView(VERSION_ID, GuideVersionStatus.PUBLISHED, "v1", null, Instant.now(), null, null, 0L,
                GuideVersionView.Text.plain("", null), chapters);
    }

    private static GuideSuggestionService.Proposal proposal(final GuideSuggestionKind kind, final String itemKey, final Phase phase, final String sectionKey,
                                                            final GuideItemType type, final String sv, final String reason) {
        return new GuideSuggestionService.Proposal(REVIVAL_ID, VERSION_ID, kind, itemKey, phase, sectionKey, type, sv, null, reason);
    }

    @BeforeEach
    void setUp() {
        service = new GuideSuggestionService(repository, revivalRepository, versionRepository, chapterRepository, sectionRepository,
                itemRepository, views, draftService, callerService, new AccessPolicy());
        revival = TestCallers.revival(RevivalStatus.ORGANISING);

        final GuideVersion version = new GuideVersion();

        version.setId(VERSION_ID);
        version.setStatus(GuideVersionStatus.PUBLISHED);
        lenient().when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.of(version));
        lenient().when(views.find(VERSION_ID)).thenReturn(view());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            final GuideSuggestion suggestion = invocation.getArgument(0);

            suggestion.setId(9L);
            return suggestion;
        });
    }

    private static String code(final Throwable e) {
        return ((BadRequestException) e).getDetailMessageCode();
    }

    @Test
    void aChangeNeedsAKnownItemAReasonAndSomethingNew() {
        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.CHANGE, "nope", null, null, null, "Ny text.", "För att")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.suggestion.item.unknown"));
        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.CHANGE, "tip", null, null, null, "Ny text.", " ")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.suggestion.reason.required"));
        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.CHANGE, "tip", null, null, null, null, "För att")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.suggestion.content.required"));

        final GuideSuggestionDto created = service.create(proposal(GuideSuggestionKind.CHANGE, "tip", null, null, null, "Ny text.", "För att"));

        assertThat(created.status()).isEqualTo(GuideSuggestionStatus.OPEN);
        assertThat(created.phase()).isEqualTo(Phase.B);
        assertThat(created.mine()).isTrue();
        assertThat(created.item().text().sv()).isEqualTo("Ett tips.");
    }

    @Test
    void anAddNeedsAPhaseATypeAndTextAndAKnownSection() {
        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.ADD, null, null, null, GuideItemType.TIP, "Nytt.", null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.suggestion.phase.required"));
        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.ADD, null, Phase.B, null, null, "Nytt.", null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.suggestion.type.required"));
        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.ADD, null, Phase.B, null, GuideItemType.TIP, " ", null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.guide.text.blank"));
        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.ADD, null, Phase.C, "kalas", GuideItemType.TIP, "Nytt.", null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.suggestion.section.unknown"));

        assertThat(service.create(proposal(GuideSuggestionKind.ADD, null, Phase.B, "kalas", GuideItemType.REQUIREMENT, "Nytt krav.", null)).sectionKey())
                .isEqualTo("kalas");
    }

    @Test
    void textsAreLimited() {
        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.CHANGE, "tip", null, null, null, "x".repeat(5001), "För att")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.guide.text.tooLong"));
        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.REMOVE, "tip", null, null, null, null, "x".repeat(2001))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.suggestion.reason.tooLong"));
    }

    @Test
    void onlyPublishedVersions() {
        when(versionRepository.findById(VERSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.REMOVE, "tip", null, null, null, null, "Inaktuellt")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.guide.version.notPublished"));
    }

    @Test
    void anObserverOrSuperAdminWithoutARevivalRoleCannotSuggest() {
        for (final var caller : List.of(TestCallers.observer(), TestCallers.superAdmin())) {
            when(callerService.current()).thenReturn(caller);
            assertThatThrownBy(() -> service.create(proposal(GuideSuggestionKind.REMOVE, "tip", null, null, null, null, "Inaktuellt")))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void onlyTheAuthorWithdrawsAnOpenSuggestion() {
        final GuideSuggestion suggestion = new GuideSuggestion();

        suggestion.setId(9L);
        suggestion.setRevivalId(REVIVAL_ID);
        suggestion.setAuthorId(TestCallers.PERSON_ID + 1);
        suggestion.setVersionId(VERSION_ID);
        suggestion.setKind(GuideSuggestionKind.REMOVE);
        suggestion.setPhase(Phase.B);
        suggestion.setItemKey("tip");
        suggestion.setVersion(0L);
        when(repository.findById(9L)).thenReturn(Optional.of(suggestion));

        assertThatThrownBy(() -> service.withdraw(9L, 0L)).isInstanceOf(AccessDeniedException.class);

        suggestion.setAuthorId(TestCallers.PERSON_ID);
        assertThat(service.withdraw(9L, 0L).status()).isEqualTo(GuideSuggestionStatus.WITHDRAWN);

        suggestion.setStatus(GuideSuggestionStatus.REJECTED);
        assertThatThrownBy(() -> service.withdraw(9L, 0L)).satisfies(e -> assertThat(code(e)).isEqualTo("error.suggestion.notOpen"));
    }

    @Test
    void discardingADraftReopensWhatWasAcceptedIntoIt() {
        final GuideSuggestion first = new GuideSuggestion();
        final GuideSuggestion second = new GuideSuggestion();

        for (final GuideSuggestion suggestion : List.of(first, second)) {
            suggestion.setStatus(GuideSuggestionStatus.ACCEPTED);
            suggestion.setAcceptedIntoVersionId(20L);
            suggestion.setDecidedBy(TestCallers.PERSON_ID);
            suggestion.setDecidedAt(Instant.now());
            suggestion.setDecisionReason("Bra");
        }
        when(repository.findByAcceptedIntoVersionId(20L)).thenReturn(List.of(first, second));
        when(repository.countByAcceptedIntoVersionId(20L)).thenReturn(2);

        assertThat(service.acceptedInto(20L)).isEqualTo(2);
        service.draftDiscarded(20L);

        assertThat(List.of(first, second)).allSatisfy(suggestion -> {
            assertThat(suggestion.getStatus()).isEqualTo(GuideSuggestionStatus.OPEN);
            assertThat(suggestion.getAcceptedIntoVersionId()).isNull();
            assertThat(suggestion.getDecidedBy()).isNull();
            assertThat(suggestion.getReopenedAt()).isNotNull();
        });
    }

    @Test
    void onlySuperAdminsDecide() {
        assertThatThrownBy(() -> service.accept(9L, 0L, null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reject(9L, 0L, null)).isInstanceOf(AccessDeniedException.class);
        assertThat(revival.getId()).isEqualTo(REVIVAL_ID);
    }
}
