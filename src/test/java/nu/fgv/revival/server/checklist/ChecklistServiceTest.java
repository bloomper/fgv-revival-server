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


package nu.fgv.revival.server.checklist;

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.TestClocks;
import nu.fgv.revival.server.checklist.template.ChecklistTemplate;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItem;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateItemRepository;
import nu.fgv.revival.server.checklist.template.ChecklistTemplateRepository;
import nu.fgv.revival.server.decisionlog.LogEntryService;
import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerInputDto;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.OwnerViews;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalRole;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.PERSON_ID;
import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The rules for checklist items (FR-024–FR-028): text limits, the deadline set by hand, states, and the directeur-only
 * waiver of a guide requirement.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@ExtendWith(MockitoExtension.class)
class ChecklistServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final long CHECKLIST_ID = 3L;

    @Mock
    private ChecklistRepository checklistRepository;
    @Mock
    private ChecklistItemRepository itemRepository;
    @Mock
    private ChecklistTexts texts;
    @Mock
    private ChecklistTemplateRepository templateRepository;
    @Mock
    private ChecklistTemplateItemRepository templateItemRepository;
    @Mock
    private RevivalMemberRepository memberRepository;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private CallerService callerService;
    @Mock
    private OwnerResolver ownerResolver;
    @Mock
    private OwnerViews ownerViews;
    @Mock
    private PersonNames personNames;
    @Mock
    private MessageSource messageSource;
    @Mock
    private LogEntryService logEntryService;

    private ChecklistService service;
    private Revival revival;
    private Checklist checklist;

    @BeforeEach
    void setUp() {
        service = new ChecklistService(checklistRepository, itemRepository, texts, templateRepository, templateItemRepository, memberRepository,
                revivalRepository, callerService, new AccessPolicy(),
                ownerResolver, ownerViews, personNames, messageSource, TestClocks.at(TODAY), logEntryService);
        revival = TestCallers.revival(RevivalStatus.ORGANISING);
        checklist = new Checklist();
        checklist.setId(CHECKLIST_ID);
        checklist.setRevivalId(REVIVAL_ID);
        checklist.setKind(ChecklistKind.CHAPTER);
        checklist.setChapterKey("B");
        lenient().when(callerService.current()).thenReturn(TestCallers.steeringGroup());
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(checklistRepository.findById(CHECKLIST_ID)).thenReturn(Optional.of(checklist));
        lenient().when(checklistRepository.findAllById(any())).thenReturn(List.of(checklist));
        lenient().when(itemRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            final ChecklistItem saved = invocation.getArgument(0);

            if (saved.getId() == null) {
                saved.setId(99L);
            }
            return saved;
        });
        lenient().when(ownerViews.toDtos(anyCollection(), any())).thenReturn(Map.of());
        lenient().when(texts.texts(anyCollection())).thenReturn(Map.of());
        lenient().when(ownerResolver.validate(eq(REVIVAL_ID), any())).thenReturn(Owner.none());
        lenient().when(ownerResolver.change(eq(REVIVAL_ID), any(), any())).thenAnswer(invocation -> ownerResolver.validate(REVIVAL_ID, invocation.getArgument(2)));
        lenient().when(itemRepository.findByChecklistIdInOrderByPositionAscIdAsc(anyList())).thenReturn(List.of());
    }

    private ChecklistItem item(final boolean required) {
        final ChecklistItem item = new ChecklistItem();

        item.setId(7L);
        item.setChecklistId(CHECKLIST_ID);
        item.setRevivalId(REVIVAL_ID);
        item.setRequired(required);
        item.setGuideItemKey(required ? "krav-1" : null);
        item.setText(required ? null : "Fika");
        item.setVersion(2L);
        lenient().when(itemRepository.findById(7L)).thenReturn(Optional.of(item));
        return item;
    }

    private static ChecklistService.ItemChange change(final String text, final LocalDate deadline) {
        return new ChecklistService.ItemChange(7L, 2L, text, null, false, deadline, false, null, false);
    }

    @Nested
    class Create {

        @Test
        void textIsNonBlankAndAtMost1000Characters() {
            assertThatThrownBy(() -> service.createItem(new ChecklistService.NewItem(CHECKLIST_ID, " ", null, null, null, null), Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.createItem(new ChecklistService.NewItem(CHECKLIST_ID, "x".repeat(1001), null, null, null, null), Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);
            assertThat(service.createItem(new ChecklistService.NewItem(CHECKLIST_ID, "x".repeat(1000), null, null, null, null), Locale.ENGLISH))
                    .isNotNull();
        }

        @Test
        void aCommentIsAtMost2000Characters() {
            assertThatThrownBy(() -> service.createItem(new ChecklistService.NewItem(CHECKLIST_ID, "x", null, null, "c".repeat(2001), null), Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void aCancelledChecklistTakesNoItems() {
            checklist.setCancelledAt(java.time.Instant.now());

            assertThatThrownBy(() -> service.createItem(new ChecklistService.NewItem(CHECKLIST_ID, "x", null, null, null, null), Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void ownersGoThroughTheResolver() {
            final OwnerInputDto owner = new OwnerInputDto(null, "Sam");

            when(ownerResolver.validate(REVIVAL_ID, owner)).thenReturn(Owner.named("Sam"));
            service.createItem(new ChecklistService.NewItem(CHECKLIST_ID, "x", owner, TODAY, null, null), Locale.ENGLISH);

            org.mockito.Mockito.verify(itemRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(saved ->
                    saved.getOwner().equals(Owner.named("Sam")) && saved.isDeadlineManual()));
        }
    }

    @Nested
    class Update {

        @Test
        void aRequiredItemsTextCannotBeChanged() {
            item(true);

            assertThatThrownBy(() -> service.updateItem(change("Ny", null), Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void settingADeadlineMarksItAsSetByHand() {
            final ChecklistItem item = item(false);

            service.updateItem(change(null, TODAY.plusDays(3)), Locale.ENGLISH);

            assertThat(item.getDeadline()).isEqualTo(TODAY.plusDays(3));
            assertThat(item.isDeadlineManual()).isTrue();
        }

        @Test
        void theOwnerIsChangedAgainstTheStoredOne() {
            final ChecklistItem item = item(false);
            final OwnerInputDto owner = new OwnerInputDto(PERSON_ID, null);

            item.setOwner(Owner.member(PERSON_ID));
            when(ownerResolver.change(REVIVAL_ID, Owner.member(PERSON_ID), owner)).thenReturn(Owner.member(PERSON_ID));
            service.updateItem(new ChecklistService.ItemChange(7L, 2L, "Ny", owner, false, null, false, null, false), Locale.ENGLISH);

            assertThat(item.getOwner()).isEqualTo(Owner.member(PERSON_ID));
            assertThat(item.getText()).isEqualTo("Ny");
        }

        @Test
        void anUnchangedDeadlineStaysComputed() {
            final ChecklistItem item = item(false);

            item.setDeadline(TODAY.plusDays(3));
            service.updateItem(new ChecklistService.ItemChange(7L, 2L, "Ny", null, false, TODAY.plusDays(3), false, null, false), Locale.ENGLISH);

            assertThat(item.isDeadlineManual()).isFalse();
        }

        @Test
        void itemsOfACancelledChecklistAreReadOnly() {
            item(false);
            checklist.setCancelledAt(java.time.Instant.now());

            assertThatThrownBy(() -> service.updateItem(change("Ny", null), Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> service.setState(7L, 2L, ChecklistItemState.DONE, null, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void aStaleVersionIsAConflict() {
            item(false);

            assertThatThrownBy(() -> service.updateItem(new ChecklistService.ItemChange(7L, 1L, "x", null, false, null, false, null, false), Locale.ENGLISH))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class State {

        @Test
        void doneAndReopenRecordWhoAndWhen() {
            final ChecklistItem item = item(true);

            service.setState(7L, 2L, ChecklistItemState.DONE, null, Locale.ENGLISH);
            assertThat(item.getState()).isEqualTo(ChecklistItemState.DONE);
            assertThat(item.getStateChangedBy()).isEqualTo(PERSON_ID);
            assertThat(item.getStateChangedAt()).isNotNull();

            service.setState(7L, 2L, ChecklistItemState.OPEN, null, Locale.ENGLISH);
            assertThat(item.getState()).isEqualTo(ChecklistItemState.OPEN);
        }

        @Test
        void anOrdinaryItemIsMarkedNotApplicableWithAnOptionalReason() {
            final ChecklistItem item = item(false);

            service.setState(7L, 2L, ChecklistItemState.NOT_APPLICABLE, null, Locale.ENGLISH);
            assertThat(item.getState()).isEqualTo(ChecklistItemState.NOT_APPLICABLE);
        }

        @Test
        void onlyADirecteurWaivesARequirementAndOnlyWithAReason() {
            final ChecklistItem item = item(true);

            assertThatThrownBy(() -> service.setState(7L, 2L, ChecklistItemState.NOT_APPLICABLE, "Spelar inte i RunAn", Locale.ENGLISH))
                    .isInstanceOf(AccessDeniedException.class);

            when(callerService.current()).thenReturn(TestCallers.directeur());
            assertThatThrownBy(() -> service.setState(7L, 2L, ChecklistItemState.NOT_APPLICABLE, " ", Locale.ENGLISH))
                    .isInstanceOf(BadRequestException.class);

            service.setState(7L, 2L, ChecklistItemState.NOT_APPLICABLE, "Spelar inte i RunAn", Locale.ENGLISH);
            assertThat(item.getState()).isEqualTo(ChecklistItemState.NOT_APPLICABLE);
            assertThat(item.getReason()).isEqualTo("Spelar inte i RunAn");
        }
    }

    @Nested
    class Overdue {

        @Test
        void anOpenItemWithAPastDeadlineIsOverdue() {
            final ChecklistItem item = new ChecklistItem();

            item.setDeadline(TODAY.minusDays(1));
            assertThat(item.isOverdue(TODAY)).isTrue();
            item.setState(ChecklistItemState.DONE);
            assertThat(item.isOverdue(TODAY)).isFalse();
            item.setState(ChecklistItemState.OPEN);
            item.setDeadline(TODAY);
            assertThat(item.isOverdue(TODAY)).isFalse();
        }
    }

    @Nested
    class Refusals {

        @Test
        void observersAndSuperAdminsNeverChangeItems() {
            item(false);
            for (final Caller caller : List.of(TestCallers.observer(), TestCallers.superAdmin())) {
                when(callerService.current()).thenReturn(caller);
                assertThatThrownBy(() -> service.setState(7L, 2L, ChecklistItemState.DONE, null, Locale.ENGLISH)).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> service.createItem(new ChecklistService.NewItem(CHECKLIST_ID, "x", null, null, null, null), Locale.ENGLISH))
                        .isInstanceOf(AccessDeniedException.class);
            }
        }

        @Test
        void anArchivedRevivalIsReadOnly() {
            item(false);
            revival.setStatus(RevivalStatus.ARCHIVED);
            when(callerService.current()).thenReturn(TestCallers.superAdmin());

            assertThatThrownBy(() -> service.setState(7L, 2L, ChecklistItemState.DONE, null, Locale.ENGLISH)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        void someoneWhoCannotSeeTheRevivalGetsNotFound() {
            item(false);
            when(callerService.current()).thenReturn(TestCallers.nobody());

            assertThatThrownBy(() -> service.setState(7L, 2L, ChecklistItemState.DONE, null, Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.get(CHECKLIST_ID, Locale.ENGLISH)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void requiredItemTextsComeFromTheGuide() {
        final ChecklistItem item = item(true);

        when(itemRepository.findByChecklistIdOrderByPositionAscIdAsc(CHECKLIST_ID)).thenReturn(List.of(item));
        when(texts.texts(anyCollection())).thenReturn(Map.of(7L, GuideVersionView.Text.plain("Lokalen ska bokas.", "The venue must be booked.")));
        when(texts.pinnedVersion(REVIVAL_ID)).thenReturn(Optional.empty());

        assertThat(service.get(CHECKLIST_ID, Locale.ENGLISH).items()).singleElement()
                .satisfies(dto -> assertThat(dto.text().en()).isEqualTo("The venue must be booked."));
    }

    @Nested
    class EventChecklists {

        private static final LocalDate SATURDAY = LocalDate.of(2027, 11, 20);
        private static final long EVENT_ID = 20L;

        private final List<ChecklistItem> saved = new ArrayList<>();
        private final List<RevivalMember> members = new ArrayList<>();
        private final Map<Long, Checklist> checklists = new HashMap<>();
        private ChecklistTemplate template;
        private ChecklistTemplateItem venue;
        private ChecklistTemplateItem keys;

        @BeforeEach
        void setUp() {
            checklists.put(CHECKLIST_ID, checklist);
            lenient().when(checklistRepository.saveAndFlush(any())).thenAnswer(invocation -> {
                final Checklist stored = invocation.getArgument(0);

                if (stored.getId() == null) {
                    stored.setId(EVENT_ID);
                    stored.setVersion(0L);
                }
                checklists.put(stored.getId(), stored);
                return stored;
            });
            lenient().when(checklistRepository.findById(anyLong())).thenAnswer(invocation -> Optional.ofNullable(checklists.get(invocation.<Long>getArgument(0))));
            lenient().when(checklistRepository.findAllById(any())).thenAnswer(invocation -> new ArrayList<>(checklists.values()));
            lenient().when(itemRepository.save(any())).thenAnswer(invocation -> {
                final ChecklistItem item = invocation.getArgument(0);

                item.setId(100L + saved.size());
                saved.add(item);
                return item;
            });
            lenient().when(itemRepository.findByChecklistIdOrderByPositionAscIdAsc(anyLong())).thenAnswer(invocation -> saved.stream()
                    .filter(item -> item.getChecklistId().equals(invocation.getArgument(0))).toList());
            lenient().when(memberRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(REVIVAL_ID)).thenReturn(members);

            template = new ChecklistTemplate();
            template.setId(5L);
            template.setKind(ChecklistKind.EVENT);
            template.setNameSv("Rephelg");
            venue = templateItem(51L, "Boka replokal", null, RelativeDeadline.of(RelativeDeadlineKind.DAYS, -14, null), "FINANCE");
            keys = templateItem(52L, "Hämta nycklar", "Collect the keys", RelativeDeadline.of(RelativeDeadlineKind.WEEKDAY_WEEK_BEFORE, null,
                    DayOfWeek.MONDAY), null);
            lenient().when(templateRepository.findById(5L)).thenReturn(Optional.of(template));
            lenient().when(templateItemRepository.findByTemplateIdAndRetiredAtIsNullOrderByPositionAscIdAsc(5L)).thenReturn(List.of(venue, keys));
            lenient().when(templateItemRepository.findAllById(any())).thenReturn(List.of(venue, keys));
        }

        private static ChecklistTemplateItem templateItem(final long id, final String sv, final String en, final RelativeDeadline deadline,
                                                          final String capability) {
            final ChecklistTemplateItem item = new ChecklistTemplateItem();

            item.setId(id);
            item.setTemplateId(5L);
            item.setTextSv(sv);
            item.setTextEn(en);
            item.setGuideItemKey(id == 51L ? "tips-lokal" : null);
            item.setDeadline(deadline);
            item.setDefaultCapability(capability);
            return item;
        }

        private void member(final long personId, final RevivalRole role, final String... capabilities) {
            final RevivalMember member = new RevivalMember();

            member.setRevivalId(REVIVAL_ID);
            member.setPersonId(personId);
            member.setRole(role);
            member.setCapabilities(new HashSet<>(List.of(capabilities)));
            members.add(member);
        }

        private Checklist create(final LocalDate date, final Long templateId, final Locale locale) {
            service.createEventChecklist(new ChecklistService.NewEventChecklist(REVIVAL_ID, " Rephelg 2 ", date, templateId), locale);
            return checklists.get(EVENT_ID);
        }

        private ChecklistItem copyOf(final ChecklistTemplateItem templateItem) {
            return saved.stream().filter(item -> templateItem.getId().equals(item.getTemplateItemId())).findFirst().orElseThrow();
        }

        @Test
        void fromATemplateWithADateItemsGetTextDeadlinesAndLinks() {
            member(40L, RevivalRole.STEERING_GROUP, "FINANCE");

            final Checklist created = create(SATURDAY, 5L, Locale.ENGLISH);

            assertThat(created.getName()).isEqualTo("Rephelg 2");
            assertThat(created.getTemplateId()).isEqualTo(5L);
            assertThat(copyOf(venue)).satisfies(item -> {
                assertThat(item.getText()).isEqualTo("Boka replokal");
                assertThat(item.getDeadline()).isEqualTo(LocalDate.of(2027, 11, 6));
                assertThat(item.isDeadlineManual()).isFalse();
                assertThat(item.getGuideItemKey()).isEqualTo("tips-lokal");
                assertThat(item.getDefaultCapability()).isEqualTo("FINANCE");
                assertThat(item.getOwner()).isEqualTo(Owner.member(40L));
            });
            assertThat(copyOf(keys)).satisfies(item -> {
                assertThat(item.getText()).isEqualTo("Collect the keys");
                assertThat(item.getDeadline()).isEqualTo(LocalDate.of(2027, 11, 8));
                assertThat(item.getOwner().isSet()).isFalse();
            });
        }

        @Test
        void withoutATemplateTheChecklistIsEmptyAndWithoutADateThereAreNoDeadlines() {
            create(null, null, Locale.ENGLISH);
            assertThat(saved).isEmpty();

            checklists.clear();
            create(null, 5L, Locale.of("sv"));
            assertThat(saved).hasSize(2).allSatisfy(item -> assertThat(item.getDeadline()).isNull());
            assertThat(copyOf(keys).getText()).isEqualTo("Hämta nycklar");
        }

        @Test
        void aRetiredOrChapterTemplateIsRefused() {
            template.setRetiredAt(java.time.Instant.now());
            assertThatThrownBy(() -> create(SATURDAY, 5L, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);

            template.setRetiredAt(null);
            template.setKind(ChecklistKind.CHAPTER);
            assertThatThrownBy(() -> create(SATURDAY, 5L, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void aDefaultOwnerOnlyWhenExactlyOneSteeringGroupMemberHoldsTheCapability() {
            member(40L, RevivalRole.DIRECTEUR, "FINANCE");
            member(41L, RevivalRole.STEERING_GROUP, "FINANCE");
            member(42L, RevivalRole.STEERING_GROUP, "FINANCE", "COMMS");

            create(SATURDAY, 5L, Locale.ENGLISH);

            assertThat(copyOf(venue).getOwner().isSet()).isFalse();
            assertThat(copyOf(venue).getDefaultCapability()).isEqualTo("FINANCE");
        }

        @Test
        void aNewDateRecomputesDeadlinesNotSetByHandAndNoDateClearsThem() {
            final Checklist created = create(SATURDAY, 5L, Locale.ENGLISH);

            copyOf(keys).setDeadline(LocalDate.of(2027, 11, 1));
            copyOf(keys).setDeadlineManual(true);
            service.updateEventChecklist(new ChecklistService.EventChecklistChange(EVENT_ID, created.getVersion(), null, SATURDAY.plusWeeks(1), false),
                    Locale.ENGLISH);

            assertThat(copyOf(venue).getDeadline()).isEqualTo(LocalDate.of(2027, 11, 13));
            assertThat(copyOf(keys).getDeadline()).isEqualTo(LocalDate.of(2027, 11, 1));

            service.updateEventChecklist(new ChecklistService.EventChecklistChange(EVENT_ID, created.getVersion(), null, null, true), Locale.ENGLISH);

            assertThat(created.getDate()).isNull();
            assertThat(copyOf(venue).getDeadline()).isNull();
            assertThat(copyOf(keys).getDeadline()).isEqualTo(LocalDate.of(2027, 11, 1));
        }

        @Test
        void cancellingKeepsTheChecklistReadableAndPhaseChecklistsCannotBeCancelled() {
            final Checklist created = create(SATURDAY, 5L, Locale.ENGLISH);

            assertThat(service.cancelEventChecklist(EVENT_ID, 0L, Locale.ENGLISH).cancelled()).isTrue();
            assertThat(created.isCancelled()).isTrue();
            assertThat(service.get(EVENT_ID, Locale.ENGLISH).items()).hasSize(2);
            assertThatThrownBy(() -> service.addFromTemplate(EVENT_ID, 0L, 5L, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);

            checklist.setVersion(0L);
            assertThatThrownBy(() -> service.cancelEventChecklist(CHECKLIST_ID, 0L, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void addingFromATemplateAddsOnlyWhatIsMissingAndChangesNothingElse() {
            create(SATURDAY, null, Locale.ENGLISH);

            final ChecklistItem done = new ChecklistItem();

            done.setId(90L);
            done.setChecklistId(EVENT_ID);
            done.setRevivalId(REVIVAL_ID);
            done.setTemplateItemId(51L);
            done.setText("Replokalen är bokad");
            done.setState(ChecklistItemState.DONE);
            saved.add(done);

            service.addFromTemplate(EVENT_ID, 0L, 5L, Locale.ENGLISH);
            service.addFromTemplate(EVENT_ID, 0L, 5L, Locale.ENGLISH);

            assertThat(saved).hasSize(2);
            assertThat(done.getText()).isEqualTo("Replokalen är bokad");
            assertThat(done.getState()).isEqualTo(ChecklistItemState.DONE);
            assertThat(copyOf(keys).getPosition()).isEqualTo(1);
        }

        @Test
        void aChapterChecklistAddsFromItsChaptersTemplate() {
            checklist.setVersion(0L);
            template.setKind(ChecklistKind.CHAPTER);
            template.setChapterKey("B");
            venue.setDeadline(null);
            keys.setDeadline(null);
            when(templateRepository.findByChapterKeyAndRetiredAtIsNull("B")).thenReturn(Optional.of(template));

            service.addFromTemplate(CHECKLIST_ID, 0L, null, Locale.ENGLISH);

            assertThat(saved).hasSize(2).allSatisfy(item -> assertThat(item.getChecklistId()).isEqualTo(CHECKLIST_ID));

            template.setChapterKey("C");
            assertThatThrownBy(() -> service.addFromTemplate(CHECKLIST_ID, 0L, 5L, Locale.ENGLISH)).isInstanceOf(BadRequestException.class);
        }

        @Test
        void aTemplateChangedLaterLeavesExistingChecklistsAlone() {
            create(SATURDAY, 5L, Locale.ENGLISH);

            venue.setTextSv("Boka en annan lokal");
            venue.setDeadline(RelativeDeadline.of(RelativeDeadlineKind.DAYS, -30, null));

            assertThat(copyOf(venue).getText()).isEqualTo("Boka replokal");
            assertThat(copyOf(venue).getDeadline()).isEqualTo(LocalDate.of(2027, 11, 6));

            final Checklist created = checklists.get(EVENT_ID);

            service.updateEventChecklist(new ChecklistService.EventChecklistChange(EVENT_ID, created.getVersion(), null, SATURDAY.plusWeeks(1), false),
                    Locale.ENGLISH);

            assertThat(copyOf(venue).getDeadline()).isEqualTo(LocalDate.of(2027, 11, 13));
        }

        @Test
        void anEventsChecklistIsMadeFromItsTypesTemplateAndFollowsTheEvent() {
            final long event = 77L;
            final Long id = service.createForEvent(REVIVAL_ID, event, " Rephelg 2 ", SATURDAY, 5L, Locale.ENGLISH).orElseThrow();
            final Checklist created = checklists.get(id);

            assertThat(created.getEventId()).isEqualTo(event);
            assertThat(created.getKind()).isEqualTo(ChecklistKind.EVENT);
            assertThat(created.getName()).isEqualTo("Rephelg 2");
            assertThat(created.getDate()).isEqualTo(SATURDAY);
            assertThat(created.getTemplateId()).isEqualTo(5L);
            assertThat(saved).hasSize(2);
            when(checklistRepository.findByEventId(event)).thenReturn(Optional.of(created));

            copyOf(keys).setDeadline(LocalDate.of(2027, 11, 1));
            copyOf(keys).setDeadlineManual(true);
            service.followEvent(event, "Rephelg i Kemi", SATURDAY.plusWeeks(1));

            assertThat(created.getName()).isEqualTo("Rephelg i Kemi");
            assertThat(created.getDate()).isEqualTo(SATURDAY.plusWeeks(1));
            assertThat(copyOf(venue).getDeadline()).isEqualTo(LocalDate.of(2027, 11, 13));
            assertThat(copyOf(keys).getDeadline()).isEqualTo(LocalDate.of(2027, 11, 1));

            service.setCancelledWithEvent(event, true);
            assertThat(created.isCancelled()).isTrue();
            service.followEvent(event, "Något annat", SATURDAY);
            assertThat(created.getName()).isEqualTo("Rephelg i Kemi");

            service.setCancelledWithEvent(event, false);
            assertThat(created.isCancelled()).isFalse();
        }

        @Test
        void aRetiredOrChapterTemplateMakesNoChecklistForAnEvent() {
            template.setRetiredAt(java.time.Instant.now());
            assertThat(service.createForEvent(REVIVAL_ID, 77L, "Rephelg 2", SATURDAY, 5L, Locale.ENGLISH)).isEmpty();

            template.setRetiredAt(null);
            template.setKind(ChecklistKind.CHAPTER);
            assertThat(service.createForEvent(REVIVAL_ID, 77L, "Rephelg 2", SATURDAY, 5L, Locale.ENGLISH)).isEmpty();
            assertThat(checklists).doesNotContainKey(EVENT_ID);
        }

        @Test
        void aChecklistWithoutAnEventIsLeftAlone() {
            when(checklistRepository.findByEventId(77L)).thenReturn(Optional.empty());

            service.followEvent(77L, "Rephelg", SATURDAY);
            service.setCancelledWithEvent(77L, true);

            assertThat(checklists).doesNotContainKey(EVENT_ID);
        }

        @Test
        void onlyWritersCreateEventChecklists() {
            when(callerService.current()).thenReturn(TestCallers.observer());

            assertThatThrownBy(() -> create(SATURDAY, 5L, Locale.ENGLISH)).isInstanceOf(AccessDeniedException.class);
            assertThat(checklists).doesNotContainKey(EVENT_ID);
        }
    }
}
