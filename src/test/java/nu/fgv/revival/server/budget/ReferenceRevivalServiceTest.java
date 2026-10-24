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
package nu.fgv.revival.server.budget;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.guide.GuideAccess;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.ConflictException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reference figures from earlier revivals (M5-FR-2): the super admin keeps them, readers of the guide read them, and a
 * deleted revival's budget is kept as one.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@ExtendWith(MockitoExtension.class)
class ReferenceRevivalServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private final List<BudgetCategory> categories = new ArrayList<>(TestBudgetCategories.seeded());
    @Mock
    private ReferenceRevivalRepository repository;
    @Mock
    private BudgetCategoryRepository categoryRepository;
    @Mock
    private GuideAccess guideAccess;
    @Mock
    private CallerService callerService;
    private ReferenceRevivalService service;
    private ReferenceRevival stored;

    private static BigDecimal kr(final String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private static ReferenceRevivalService.FigureInput figure(final String category, final @Nullable String budgeted, final @Nullable String actual) {
        return new ReferenceRevivalService.FigureInput(category, budgeted == null ? null : kr(budgeted), actual == null ? null : kr(actual));
    }

    private static ReferenceRevivalService.Definition nobel(final List<ReferenceRevivalService.FigureInput> figures) {
        return new ReferenceRevivalService.Definition(" Nobel 2026 ", 2026, 50, " Från JK:s slutrapport ", figures);
    }

    @BeforeEach
    void setUp() {
        final AccessPolicy accessPolicy = new AccessPolicy();

        service = new ReferenceRevivalService(repository, new BudgetCategoryService(categoryRepository, callerService, accessPolicy,
                TestClocks.at(TODAY)), guideAccess, callerService, accessPolicy, TestClocks.at(TODAY));
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(categoryRepository.findAllByOrderByPositionAscIdAsc()).thenAnswer(invocation -> List.copyOf(categories));
        lenient().when(categoryRepository.findAllById(any())).thenAnswer(invocation -> {
            final Iterable<String> ids = invocation.getArgument(0);
            final List<BudgetCategory> found = new ArrayList<>();

            ids.forEach(id -> categories.stream().filter(category -> category.getId().equals(id)).forEach(found::add));
            return found;
        });
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            stored = invocation.getArgument(0);
            if (stored.getId() == null) {
                stored.setId(1L);
                stored.setVersion(0L);
            } else {
                stored.setVersion(stored.getVersion() + 1);
            }
            return stored;
        });
        lenient().when(repository.findById(1L)).thenAnswer(invocation -> Optional.ofNullable(stored));
    }

    @Test
    void theSuperAdminEntersAReferenceWithItsFiguresInCategoryOrder() {
        final ReferenceRevivalDto created = service.create(nobel(List.of(figure("FOOD", "6000", "6400"), figure("SHOW_TICKETS", null, "81000"))));

        assertThat(created.label()).isEqualTo("Nobel 2026");
        assertThat(created.comment()).isEqualTo("Från JK:s slutrapport");
        assertThat(created.automatic()).isFalse();
        assertThat(created.figures()).extracting(ReferenceRevivalDto.Figure::categoryId).containsExactly("SHOW_TICKETS", "FOOD");
    }

    @Test
    void anUpdateKeepsTheFiguresThatStayAndReplacesTheRest() {
        service.create(nobel(List.of(figure("FOOD", "6000", "6400"), figure("VENUE", "30000", null))));

        final ReferenceFigure food = stored.getFigures().getFirst();
        final ReferenceRevivalDto updated = service.update(1L, 0L, nobel(List.of(figure("FOOD", "6500", "6400"), figure("PARTY", "20000", null))));

        assertThat(updated.figures()).extracting(ReferenceRevivalDto.Figure::categoryId).containsExactly("FOOD", "PARTY");
        assertThat(stored.getFigures().getFirst()).isSameAs(food);
        assertThat(food.getBudgeted()).isEqualTo(kr("6500"));
        assertThat(updated.version()).isEqualTo(1L);
        assertThatThrownBy(() -> service.update(1L, 0L, nobel(List.of()))).isInstanceOf(ConflictException.class);
    }

    @Test
    void referencesAreValidated() {
        assertThatThrownBy(() -> service.create(new ReferenceRevivalService.Definition(" ", 2026, null, null, List.of())))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.reference.label");
        assertThatThrownBy(() -> service.create(new ReferenceRevivalService.Definition("Nobel", 1900, null, null, List.of())))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.reference.year");
        assertThatThrownBy(() -> service.create(new ReferenceRevivalService.Definition("Nobel", 2026, 2001, null, List.of())))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.reference.participants");
        assertThatThrownBy(() -> service.create(new ReferenceRevivalService.Definition("Nobel", 2026, null, "x".repeat(1001), List.of())))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.reference.comment.tooLong");
        assertThatThrownBy(() -> service.create(nobel(List.of(figure("FOOD", "1", null), figure("FOOD", "2", null)))))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.reference.figure.duplicate");
        assertThatThrownBy(() -> service.create(nobel(List.of(figure("FOOD", null, null)))))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.reference.figure.empty");
        assertThatThrownBy(() -> service.create(nobel(List.of(figure("FOOD", "-1", null)))))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.reference.figure.amount");
        assertThatThrownBy(() -> service.create(nobel(List.of(figure("NOPE", "1", null)))))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.catalog.unknown");
    }

    @Test
    void aRetiredCategoryStaysOnAReferenceThatHasIt() {
        service.create(nobel(List.of(figure("FOOD", "6000", null))));
        categories.stream().filter(category -> category.getId().equals("FOOD")).forEach(category -> category.setRetiredAt(Instant.EPOCH));

        assertThat(service.update(1L, 0L, nobel(List.of(figure("FOOD", "7000", null)))).figures()).hasSize(1);
        assertThatThrownBy(() -> service.create(nobel(List.of(figure("FOOD", "1", null)))))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.catalog.retired");
    }

    @Test
    void referencesAreDeletedForReal() {
        service.create(nobel(List.of()));
        service.delete(1L, 0L);

        verify(repository).delete(stored);
    }

    @Test
    void onlyTheSuperAdminChangesReferences() {
        when(callerService.current()).thenReturn(TestCallers.observer());

        assertThatThrownBy(() -> service.create(nobel(List.of()))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(1L, 0L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void directeursAndSteeringGroupsOfVisibleRevivalsReadThem() {
        when(repository.findAllByOrderByYearDescLabelAscIdAsc()).thenReturn(List.of());
        when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        when(guideAccess.callerRevivals(any())).thenReturn(List.of(TestCallers.revival(RevivalStatus.ORGANISING)));

        assertThat(service.list()).isEmpty();

        when(guideAccess.callerRevivals(any())).thenReturn(List.of(TestCallers.revival(RevivalStatus.APPROVED)));
        assertThatThrownBy(() -> service.list()).isInstanceOf(AccessDeniedException.class);

        when(callerService.current()).thenReturn(TestCallers.nobody());
        when(guideAccess.callerRevivals(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.list()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aDeletedRevivalsBudgetIsKeptAsAnAutomaticReference() {
        final ReferenceRevival kept = service.keep("Ali Baba 2028", 2028, 60, Map.of("FOOD", figure("FOOD", "6000", "6200")));

        assertThat(kept).isNotNull();
        assertThat(kept.isAutomatic()).isTrue();
        assertThat(kept.getLabel()).isEqualTo("Ali Baba 2028");
        assertThat(kept.getParticipants()).isEqualTo(60);
        assertThat(kept.getFigures()).singleElement().satisfies(figure -> {
            assertThat(figure.getCategoryId()).isEqualTo("FOOD");
            assertThat(figure.getActual()).isEqualTo(kr("6200"));
        });
        assertThat(service.keep("Tomt 2028", 2028, null, Map.of())).isNull();
    }
}
