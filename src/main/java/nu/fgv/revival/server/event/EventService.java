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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.checklist.ChecklistService;
import nu.fgv.revival.server.checklist.ChecklistSummaryDto;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A revival's events: its directeurs and steering group create, change, cancel and restore them, and
 * everyone who reads the revival's work reads them. An event of a type with a checklist template gets an EVENT
 * checklist that follows its title, start date and cancellation. Never deleted.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class EventService {

    /**
     * How many events the revival page shows as upcoming.
     */
    static final int UPCOMING = 3;

    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Comparator<Event> ORDER = Comparator.comparing(Event::getStartDate)
            .thenComparing(Event::getStartTime, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Event::getId);

    private final EventRepository repository;
    private final EventTypeRepository typeRepository;
    private final RevivalRepository revivalRepository;
    private final ChecklistService checklistService;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public EventService(final EventRepository repository,
                        final EventTypeRepository typeRepository,
                        final RevivalRepository revivalRepository,
                        final ChecklistService checklistService,
                        final CallerService callerService,
                        final AccessPolicy accessPolicy,
                        final Clock clock) {
        this.repository = repository;
        this.typeRepository = typeRepository;
        this.revivalRepository = revivalRepository;
        this.checklistService = checklistService;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    private static String defaultTitle(final @Nullable EventType type, final Locale locale) {
        if (type == null) {
            throw new BadRequestException("error.event.title.blank");
        }
        return EventTypeDto.of(type).name(locale);
    }

    private static String validTitle(final String title) {
        final String trimmed = title.strip();

        if (trimmed.isEmpty()) {
            throw new BadRequestException("error.event.title.blank");
        }
        if (trimmed.length() > Event.TITLE_MAX_LENGTH) {
            throw new BadRequestException("error.event.title.tooLong", Event.TITLE_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * {@code HH:mm} from 00:00 to 23:59, trimmed, or null when blank, as a show's time.
     */
    private static @Nullable LocalTime validTime(final @Nullable String time) {
        if (time == null || time.isBlank()) {
            return null;
        }

        final String trimmed = time.strip();

        if (!TIME.matcher(trimmed).matches()) {
            throw new BadRequestException("error.event.time.invalid");
        }
        return LocalTime.parse(trimmed, TIME_FORMAT);
    }

    private static @Nullable String validLocation(final @Nullable String location) {
        if (location == null || location.isBlank()) {
            return null;
        }

        final String trimmed = location.strip();

        if (trimmed.length() > Event.LOCATION_MAX_LENGTH) {
            throw new BadRequestException("error.event.location.tooLong", Event.LOCATION_MAX_LENGTH);
        }
        return trimmed;
    }

    private static @Nullable String validDescription(final @Nullable String description) {
        if (description == null || description.isBlank()) {
            return null;
        }

        final String trimmed = description.strip();

        if (trimmed.length() > Event.DESCRIPTION_MAX_LENGTH) {
            throw new BadRequestException("error.event.description.tooLong", Event.DESCRIPTION_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * The end not before the start and at most {@value Event#MAX_SPAN_DAYS} days after it; an end time only with a
     * start time, and not before it on a one-day event.
     */
    private static void validSchedule(final Event event) {
        if (event.getEndDate().isBefore(event.getStartDate())) {
            throw new BadRequestException("error.event.endBeforeStart");
        }
        if (ChronoUnit.DAYS.between(event.getStartDate(), event.getEndDate()) > Event.MAX_SPAN_DAYS) {
            throw new BadRequestException("error.event.tooLong", Event.MAX_SPAN_DAYS);
        }
        if (event.getEndTime() != null && event.getStartTime() == null) {
            throw new BadRequestException("error.event.endTimeWithoutStart");
        }
        if (event.getEndTime() != null && event.getStartDate().equals(event.getEndDate()) && event.getEndTime().isBefore(event.getStartTime())) {
            throw new BadRequestException("error.event.endTimeBeforeStart");
        }
    }

    /**
     * By start date, then start time with all-day events first, then id.
     */
    @Transactional(readOnly = true)
    public List<EventDto> list(final Long revivalId, final boolean includeCancelled) {
        final Revival revival = eventsReadable(callerService.current(), revivalId);

        return repository.findByRevivalId(revivalId).stream()
                .filter(event -> includeCancelled || !event.isCancelled())
                .sorted(ORDER)
                .map(event -> EventDto.of(event, revival.getPremiereDate()))
                .toList();
    }

    /**
     * As {@link #list}, for readers of the revival's work only: what MCP offers, since participants get no tools.
     */
    @Transactional(readOnly = true)
    public List<EventDto> listAsWork(final Long revivalId, final boolean includeCancelled) {
        visible(callerService.current(), revivalId);
        return list(revivalId, includeCancelled);
    }

    @Transactional(readOnly = true)
    public EventDto get(final Long id) {
        final Event event = readable(callerService.current(), id);

        return EventDto.of(event, revivalRepository.findById(event.getRevivalId()).map(Revival::getPremiereDate).orElse(null));
    }

    /**
     * The next events that are not cancelled and have not ended, soonest first.
     */
    @Transactional(readOnly = true)
    public List<EventDto> upcoming(final Long revivalId) {
        final LocalDate today = LocalDate.now(clock);

        return list(revivalId, false).stream().filter(event -> !event.endDate().isBefore(today)).limit(UPCOMING).toList();
    }

    /**
     * Dates for the active types the revival has no event of that is not cancelled.
     */
    @Transactional(readOnly = true)
    public List<EventProposalDto> proposals(final Long revivalId) {
        final Revival revival = visible(callerService.current(), revivalId);
        final Set<Long> typed = repository.findByRevivalId(revivalId).stream()
                .filter(event -> !event.isCancelled() && event.getTypeId() != null)
                .map(Event::getTypeId)
                .collect(Collectors.toSet());

        return EventProposals.propose(revival.getPremiereDate(),
                typeRepository.findAllByOrderByPositionAscIdAsc().stream().filter(type -> !type.isRetired()).map(EventTypeDto::of).toList(), typed);
    }

    /**
     * The events' checklists, by event id, for those whose revival's work the caller reads: participants read events,
     * not their checklists. Callers have read the events.
     */
    @Transactional(readOnly = true)
    public Map<Long, ChecklistSummaryDto> checklists(final Collection<Long> eventIds) {
        final Caller caller = callerService.current();
        final Map<Long, Boolean> readsWork = new java.util.HashMap<>();
        final List<Long> readable = repository.findAllById(Set.copyOf(eventIds)).stream()
                .filter(event -> readsWork.computeIfAbsent(event.getRevivalId(), revivalId -> revivalRepository.findById(revivalId)
                        .map(revival -> accessPolicy.canReadRevivalWork(caller, revival)).orElse(false)))
                .map(Event::getId)
                .toList();

        return readable.isEmpty() ? Map.of() : checklistService.summariesByEvent(readable);
    }

    @Transactional
    public EventDto create(final NewEvent newEvent, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = writable(caller, newEvent.revivalId());
        final @Nullable EventType type = newEvent.typeId() == null ? null : activeType(newEvent.typeId());

        AuditContext.operation("eventCreate");

        final Event event = new Event();
        final LocalDate endDate = newEvent.endDate() != null ? newEvent.endDate()
                : newEvent.startDate().plusDays(type == null ? 0 : type.getDays() - 1L);
        final LocalTime startTime = validTime(newEvent.startTime());

        event.setRevivalId(revival.getId());
        event.setTypeId(type == null ? null : type.getId());
        event.setTitle(newEvent.title() == null || newEvent.title().isBlank()
                ? defaultTitle(type, locale)
                : validTitle(newEvent.title()));
        event.setStartDate(newEvent.startDate());
        event.setEndDate(endDate);
        event.setStartTime(startTime);
        event.setEndTime(validTime(newEvent.endTime()));
        event.setLocation(validLocation(newEvent.location()));
        event.setDescription(validDescription(newEvent.description()));
        event.setRevivalPays(newEvent.revivalPays() != null ? newEvent.revivalPays() : type != null && type.isRevivalPays());
        validSchedule(event);

        final Event saved = repository.saveAndFlush(event);

        if (type != null && type.getChecklistTemplateId() != null) {
            checklistService.createForEvent(revival.getId(), saved.getId(), saved.getTitle(), saved.getStartDate(), type.getChecklistTemplateId(), locale);
        }
        return EventDto.of(saved, revival.getPremiereDate());
    }

    /**
     * A new title renames the event's checklist, and a new start date re-dates it.
     */
    @Transactional
    public EventDto update(final Change change) {
        final Caller caller = callerService.current();
        final Event event = changeable(caller, change.id(), change.version());

        if (event.isCancelled()) {
            throw new BadRequestException("error.event.cancelled");
        }
        AuditContext.operation("eventUpdate");
        if (change.title() != null) {
            event.setTitle(validTitle(change.title()));
        }
        if (change.startDate() != null) {
            event.setStartDate(change.startDate());
        }
        if (change.endDate() != null) {
            event.setEndDate(change.endDate());
        }
        if (change.clearStartTime()) {
            event.setStartTime(null);
        } else if (change.startTime() != null) {
            event.setStartTime(validTime(change.startTime()));
        }
        if (change.clearEndTime()) {
            event.setEndTime(null);
        } else if (change.endTime() != null) {
            event.setEndTime(validTime(change.endTime()));
        }
        if (change.clearLocation()) {
            event.setLocation(null);
        } else if (change.location() != null) {
            event.setLocation(validLocation(change.location()));
        }
        if (change.clearDescription()) {
            event.setDescription(null);
        } else if (change.description() != null) {
            event.setDescription(validDescription(change.description()));
        }
        if (change.revivalPays() != null) {
            event.setRevivalPays(change.revivalPays());
        }
        validSchedule(event);

        final Event saved = repository.saveAndFlush(event);

        checklistService.followEvent(saved.getId(), saved.getTitle(), saved.getStartDate());
        return EventDto.of(saved, premiere(saved));
    }

    /**
     * Cancels the event and its checklist; both stay readable.
     */
    @Transactional
    public EventDto cancel(final Long id, final Long version) {
        final Caller caller = callerService.current();
        final Event event = changeable(caller, id, version);

        if (event.isCancelled()) {
            throw new BadRequestException("error.event.cancelled");
        }
        AuditContext.operation("eventCancel");
        event.setCancelledAt(clock.instant());

        final Event saved = repository.saveAndFlush(event);

        checklistService.setCancelledWithEvent(saved.getId(), true);
        return EventDto.of(saved, premiere(saved));
    }

    /**
     * Undoes a cancellation, and the checklist's.
     */
    @Transactional
    public EventDto restore(final Long id, final Long version) {
        final Caller caller = callerService.current();
        final Event event = changeable(caller, id, version);

        if (!event.isCancelled()) {
            throw new BadRequestException("error.event.notCancelled");
        }
        AuditContext.operation("eventRestore");
        event.setCancelledAt(null);

        final Event saved = repository.saveAndFlush(event);

        checklistService.setCancelledWithEvent(saved.getId(), false);
        return EventDto.of(saved, premiere(saved));
    }

    private @Nullable LocalDate premiere(final Event event) {
        return revivalRepository.findById(event.getRevivalId()).map(Revival::getPremiereDate).orElse(null);
    }

    private Revival visible(final Caller caller, final Long revivalId) {
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireReadRevivalWork(caller, revival);
        return revival;
    }

    /**
     * A revival whose events the caller reads: its work readers, and its participants.
     */
    private Revival eventsReadable(final Caller caller, final Long revivalId) {
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireReadEvents(caller, revival);
        return revival;
    }

    private Revival writable(final Caller caller, final Long revivalId) {
        final Revival revival = visible(caller, revivalId);

        accessPolicy.requireWriteRevivalWork(caller, revival);
        return revival;
    }

    private Event readable(final Caller caller, final Long id) {
        final Event event = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(Event.class, id));
        final Revival revival = revivalRepository.findById(event.getRevivalId()).orElse(null);

        if (revival == null || !accessPolicy.canReadEvents(caller, revival)) {
            throw new ResourceNotFoundException(Event.class, id);
        }
        return event;
    }

    private Event changeable(final Caller caller, final Long id, final Long version) {
        final Event event = readable(caller, id);

        writable(caller, event.getRevivalId());
        if (!Objects.equals(version, event.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return event;
    }

    /**
     * A type for a new event: one that exists and is not retired.
     */
    private EventType activeType(final Long typeId) {
        final EventType type = typeRepository.findById(typeId).orElseThrow(() -> new ResourceNotFoundException(EventType.class, typeId));

        if (type.isRetired()) {
            throw new BadRequestException("error.event.type.retired", type.getNameSv());
        }
        return type;
    }

    /**
     * A new event. Without a type the title is required; with one, its name in the request's language is the default
     * title, its revival pays the default, and its days give the default end date.
     */
    public record NewEvent(Long revivalId, @Nullable Long typeId, @Nullable String title, LocalDate startDate,
                           @Nullable LocalDate endDate,
                           @Nullable String startTime, @Nullable String endTime, @Nullable String location,
                           @Nullable String description,
                           @Nullable Boolean revivalPays) {
    }

    /**
     * Fields left null are unchanged; the {@code clear*} flags remove a value.
     */
    public record Change(Long id, Long version, @Nullable String title, @Nullable LocalDate startDate,
                         @Nullable LocalDate endDate,
                         @Nullable String startTime, boolean clearStartTime, @Nullable String endTime,
                         boolean clearEndTime,
                         @Nullable String location, boolean clearLocation, @Nullable String description,
                         boolean clearDescription,
                         @Nullable Boolean revivalPays) {
    }
}
