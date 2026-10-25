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


package nu.fgv.revival.server.event;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.checklist.ChecklistKind;
import nu.fgv.revival.server.checklist.template.ChecklistTemplate;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateRepository;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.jspecify.annotations.Nullable;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The event types the super admin keeps.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class EventTypeServiceTest {

    private static final long EVENT_TEMPLATE = 40L;
    private static final long CHAPTER_TEMPLATE = 41L;
    private final List<EventType> types = new ArrayList<>();
    @Mock
    private EventTypeRepository repository;
    @Mock
    private ChecklistTemplateRepository templateRepository;
    @Mock
    private CallerService callerService;
    private EventTypeService service;
    private ChecklistTemplate eventTemplate;

    private static EventType type(final long id, final String name, final int position) {
        final EventType type = new EventType();

        type.setId(id);
        type.setNameSv(name);
        type.setPosition(position);
        type.setVersion(0L);
        return type;
    }

    private static ChecklistTemplate template(final long id, final ChecklistKind kind) {
        final ChecklistTemplate template = new ChecklistTemplate();

        template.setId(id);
        template.setKind(kind);
        return template;
    }

    private static EventTypeService.Definition definition(final String name, final @Nullable Integer min, final @Nullable Integer max, final int days,
                                                          final @Nullable Long templateId) {
        return new EventTypeService.Definition(new EventTypeService.Text(name, null), new EventTypeService.Text(" Öl och mat. ", "Beer and food."),
                new EventTypeService.Text(" ", "Rooms"), null, min, max, days, true, templateId);
    }

    @BeforeEach
    void setUp() {
        service = new EventTypeService(repository, templateRepository, callerService, new AccessPolicy(), TestClocks.at(LocalDate.of(2026, 10, 23)));
        types.add(type(1L, "AW", 0));
        types.add(type(2L, "Kickoff", 1));
        types.add(type(3L, "Rephelg 1", 2));
        eventTemplate = template(EVENT_TEMPLATE, ChecklistKind.EVENT);
        lenient().when(callerService.current()).thenReturn(TestCallers.superAdmin());
        lenient().when(repository.findAllByOrderByPositionAscIdAsc()).thenAnswer(invocation -> List.copyOf(types));
        lenient().when(repository.findById(any())).thenAnswer(invocation ->
                types.stream().filter(type -> type.getId().equals(invocation.getArgument(0))).findFirst());
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(templateRepository.findById(EVENT_TEMPLATE)).thenReturn(Optional.of(eventTemplate));
        lenient().when(templateRepository.findById(CHAPTER_TEMPLATE)).thenReturn(Optional.of(template(CHAPTER_TEMPLATE, ChecklistKind.CHAPTER)));
    }

    @Test
    void aNewTypeIsAppendedWithItsTexts() {
        final EventTypeDto created = service.create(definition(" Förrådsbesök ", -30, -20, 1, EVENT_TEMPLATE));

        assertThat(created.name().sv()).isEqualTo("Förrådsbesök");
        assertThat(created.position()).isEqualTo(3);
        assertThat(created.description()).isNotNull();
        assertThat(created.description().sv()).isEqualTo("Öl och mat.");
        assertThat(created.description().en()).isEqualTo("Beer and food.");
        // No Swedish text, so no text at all
        assertThat(created.rooms()).isNull();
        assertThat(created.catering()).isNull();
        assertThat(created.checklistTemplateId()).isEqualTo(EVENT_TEMPLATE);
        assertThat(created.revivalPays()).isTrue();
    }

    @Test
    void onlyTheSuperAdminChangesTypes() {
        when(callerService.current()).thenReturn(TestCallers.directeur());

        assertThatThrownBy(() -> service.create(definition("Förrådsbesök", null, null, 1, null))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.retire(1L, 0L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.move(1L, 0L, 2)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).saveAndFlush(any());
        assertThat(service.list(false)).hasSize(3);
    }

    @Test
    void theSwedishNameIsUniqueIgnoringCase() {
        assertThatThrownBy(() -> service.create(definition("kickoff", null, null, 1, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("error.eventType.name.taken");
        assertThat(service.update(2L, 0L, definition("Kickoff", null, null, 1, null)).name().sv()).isEqualTo("Kickoff");
        assertThatThrownBy(() -> service.create(definition(" ", null, null, 1, null))).hasMessageContaining("error.eventType.name.blank");
    }

    @Test
    void offsetsAreBothOrNeitherInOrderAndInRange() {
        assertThatThrownBy(() -> service.create(definition("A", -10, null, 1, null))).hasMessageContaining("error.eventType.offsets.both");
        assertThatThrownBy(() -> service.create(definition("A", null, 10, 1, null))).hasMessageContaining("error.eventType.offsets.both");
        assertThatThrownBy(() -> service.create(definition("A", 10, -10, 1, null))).hasMessageContaining("error.eventType.offsets.order");
        assertThatThrownBy(() -> service.create(definition("A", -731, 0, 1, null))).hasMessageContaining("error.eventType.offsets.range");
        assertThatThrownBy(() -> service.create(definition("A", 0, 731, 1, null))).hasMessageContaining("error.eventType.offsets.range");
        assertThat(service.create(definition("A", -730, 730, 1, null)).offsetMaxDays()).isEqualTo(730);
        assertThat(service.create(definition("B", null, null, 1, null)).offsetMinDays()).isNull();
    }

    @Test
    void daysAreOneToFourteen() {
        assertThatThrownBy(() -> service.create(definition("A", null, null, 0, null))).hasMessageContaining("error.eventType.days");
        assertThatThrownBy(() -> service.create(definition("A", null, null, 15, null))).hasMessageContaining("error.eventType.days");
    }

    @Test
    void theTemplateIsAnActiveEventTemplateUnlessTheTypeHasIt() {
        assertThatThrownBy(() -> service.create(definition("A", null, null, 1, CHAPTER_TEMPLATE)))
                .hasMessageContaining("error.eventType.template.kind");

        eventTemplate.setRetiredAt(Instant.EPOCH);
        assertThatThrownBy(() -> service.create(definition("A", null, null, 1, EVENT_TEMPLATE)))
                .hasMessageContaining("error.checklist.template.retired");

        types.get(2).setChecklistTemplateId(EVENT_TEMPLATE);
        assertThat(service.update(3L, 0L, definition("Rephelg 1", -70, -56, 2, EVENT_TEMPLATE)).checklistTemplateId()).isEqualTo(EVENT_TEMPLATE);
    }

    @Test
    void movingRenumbersEveryType() {
        final List<EventTypeDto> moved = service.move(3L, 0L, 0);

        assertThat(moved).extracting(EventTypeDto::id).containsExactly(3L, 1L, 2L);
        assertThat(moved).extracting(EventTypeDto::position).containsExactly(0, 1, 2);
    }

    @Test
    void aRetiredTypeIsListedOnlyOnRequestAndCanBeRestored() {
        assertThat(service.retire(2L, 0L).retired()).isTrue();
        assertThat(service.list(false)).extracting(EventTypeDto::id).containsExactly(1L, 3L);
        assertThat(service.list(true)).extracting(EventTypeDto::id).containsExactly(1L, 2L, 3L);
        assertThat(service.restore(2L, 0L).retired()).isFalse();
    }

    @Test
    void aStaleVersionIsAConflict() {
        assertThatThrownBy(() -> service.update(1L, 5L, definition("AW", null, null, 1, null))).isInstanceOf(ConflictException.class);
    }
}
