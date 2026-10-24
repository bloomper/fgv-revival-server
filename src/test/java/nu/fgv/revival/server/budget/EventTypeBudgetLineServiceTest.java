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
import nu.fgv.revival.server.event.EventType;
import nu.fgv.revival.server.event.EventTypeDto;
import nu.fgv.revival.server.event.EventTypeRepository;
import nu.fgv.revival.server.event.EventTypeService;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.ConflictException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lines event types propose for budgets (M5-FR-3): the super admin keeps them; removing one deletes it.
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@ExtendWith(MockitoExtension.class)
class EventTypeBudgetLineServiceTest {

    private static final long TYPE = 3L;
    private final List<EventTypeBudgetLine> lines = new ArrayList<>();
    @Mock
    private EventTypeBudgetLineRepository repository;
    @Mock
    private EventTypeRepository typeRepository;
    @Mock
    private EventTypeService typeService;
    @Mock
    private BudgetCategoryRepository categoryRepository;
    @Mock
    private CallerService callerService;
    private EventTypeBudgetLineService service;

    private static EventTypeBudgetLineService.Definition definition(final BudgetLineQuantity rule, final @Nullable String quantity) {
        return new EventTypeBudgetLineService.Definition(TYPE, "FOOD", " Mat per deltagare ", " ", rule, quantity == null ? null : new BigDecimal(quantity),
                new BigDecimal("150.00"));
    }

    @BeforeEach
    void setUp() {
        final AccessPolicy accessPolicy = new AccessPolicy();
        final List<BudgetCategory> categories = TestBudgetCategories.seeded();
        final EventType type = new EventType();

        service = new EventTypeBudgetLineService(repository, typeRepository, typeService,
                new BudgetCategoryService(categoryRepository, callerService, accessPolicy, TestClocks.at(LocalDate.of(2026, 10, 23))), callerService,
                accessPolicy);
        type.setId(TYPE);
        type.setNameSv("Rephelg 1");
        type.setVersion(0L);
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(typeRepository.findById(TYPE)).thenReturn(Optional.of(type));
        lenient().when(typeService.get(TYPE)).thenReturn(EventTypeDto.of(type));
        lenient().when(categoryRepository.findAllById(any())).thenAnswer(invocation -> {
            final Iterable<String> ids = invocation.getArgument(0);
            final List<BudgetCategory> found = new ArrayList<>();

            ids.forEach(id -> categories.stream().filter(category -> category.getId().equals(id)).forEach(found::add));
            return found;
        });
        lenient().when(repository.findByEventTypeIdOrderByPositionAscIdAsc(TYPE)).thenAnswer(invocation -> List.copyOf(lines));
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                lines.stream().filter(line -> line.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            final EventTypeBudgetLine line = invocation.getArgument(0);

            if (line.getId() == null) {
                line.setId(10L + lines.size());
                line.setVersion(0L);
                lines.add(line);
            }
            return line;
        });
    }

    @Test
    void linesAreAppendedToTheType() {
        final EventTypeDto type = service.create(definition(BudgetLineQuantity.PER_PARTICIPANT, "3"));

        assertThat(type.id()).isEqualTo(TYPE);
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.getDescriptionSv()).isEqualTo("Mat per deltagare");
            assertThat(line.getDescriptionEn()).isNull();
            assertThat(line.getQuantity()).as("a quantity per participant is not stored").isNull();
            assertThat(line.getPosition()).isZero();
        });
        service.create(definition(BudgetLineQuantity.FIXED, "2"));
        assertThat(lines.get(1).getPosition()).isEqualTo(1);
        assertThat(lines.get(1).getQuantity()).isEqualTo(new BigDecimal("2.00"));
    }

    @Test
    void aFixedLineNeedsAQuantity() {
        assertThatThrownBy(() -> service.create(definition(BudgetLineQuantity.FIXED, null)))
                .hasFieldOrPropertyWithValue("detailMessageCode", "error.eventTypeBudgetLine.quantity");
        assertThatThrownBy(() -> service.create(new EventTypeBudgetLineService.Definition(null, "FOOD", "Mat", null, BudgetLineQuantity.FIXED,
                BigDecimal.ONE, BigDecimal.ONE))).hasFieldOrPropertyWithValue("detailMessageCode", "error.eventTypeBudgetLine.type");
    }

    @Test
    void removingALineDeletesItAndClosesTheGap() {
        service.create(definition(BudgetLineQuantity.FIXED, "1"));
        service.create(definition(BudgetLineQuantity.FIXED, "2"));

        final EventTypeBudgetLine first = lines.getFirst();

        when(repository.findByEventTypeIdOrderByPositionAscIdAsc(TYPE)).thenReturn(List.of(lines.get(1)));
        service.remove(first.getId(), 0L);

        verify(repository).delete(first);
        assertThat(lines.get(1).getPosition()).isZero();
        assertThatThrownBy(() -> service.remove(lines.get(1).getId(), 7L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyTheSuperAdminKeepsTheLines() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        assertThatThrownBy(() -> service.create(definition(BudgetLineQuantity.FIXED, "1"))).isInstanceOf(AccessDeniedException.class);
    }
}
