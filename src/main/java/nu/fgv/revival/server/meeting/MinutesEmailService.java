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


package nu.fgv.revival.server.meeting;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.MailUnavailableException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Emails a closed meeting's minutes to the revival's directeurs and steering group members who can log in and see it.
 * The mail goes out first, outside any transaction; then the send and each recipient's
 * result are saved together. When every recipient fails, nothing is saved.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
@Slf4j
public class MinutesEmailService {

    private static final Set<RevivalRole> RECIPIENT_ROLES = EnumSet.of(RevivalRole.DIRECTEUR, RevivalRole.STEERING_GROUP);
    private static final Set<String> LANGUAGES = Set.of("sv", "en");

    private final MinutesEmailRepository repository;
    private final MeetingService meetingService;
    private final MinutesMailer mailer;
    private final RevivalRepository revivalRepository;
    private final RevivalMemberRepository memberRepository;
    private final PersonRepository personRepository;
    private final PersonNames personNames;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final RevivalTitles titles;

    public MinutesEmailService(final MinutesEmailRepository repository,
                               final MeetingService meetingService,
                               final MinutesMailer mailer,
                               final RevivalRepository revivalRepository,
                               final RevivalMemberRepository memberRepository,
                               final PersonRepository personRepository,
                               final PersonNames personNames,
                               final CallerService callerService,
                               final AccessPolicy accessPolicy,
                               final TransactionTemplate transactions,
                               final Clock clock,
                               final RevivalTitles titles) {
        this.repository = repository;
        this.meetingService = meetingService;
        this.mailer = mailer;
        this.revivalRepository = revivalRepository;
        this.memberRepository = memberRepository;
        this.personRepository = personRepository;
        this.personNames = personNames;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.transactions = transactions;
        this.clock = clock;
        this.titles = titles;
    }

    /**
     * Whether the caller may email this meeting's minutes now: it is closed, they may write the log, and mail is
     * configured.
     */
    @Transactional(readOnly = true)
    public boolean canEmail(final Long meetingId, final Long revivalId, final MeetingStatus status) {
        final Caller caller = callerService.current();

        return status == MeetingStatus.CLOSED && mailer.available()
                && revivalRepository.findById(revivalId).map(revival -> accessPolicy.canWriteRevivalWork(caller, revival)).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<MinutesEmailDto> list(final Long meetingId, final Locale locale) {
        meetingService.readable(meetingId);
        return toDtos(repository.findByMeetingIdOrderBySentAtDescIdDesc(meetingId), locale);
    }

    public MinutesEmailDto send(final Long meetingId, final String language, final Locale locale) {
        final Prepared prepared = prepare(meetingId, language);
        final Recipients recipients = recipients(prepared.revival());

        return sendTo(prepared, recipients.people(), recipients.waiting(), null, locale);
    }

    public MinutesEmailDto retry(final Long emailId, final Locale locale) {
        final Earlier earlier = Objects.requireNonNull(transactions.execute(status -> {
            final MinutesEmail email = repository.findById(emailId).orElseThrow(() -> new ResourceNotFoundException(MinutesEmail.class, emailId));
            final Meeting meeting = readableMeeting(email);

            accessPolicy.requireWriteRevivalWork(callerService.current(), revivalRepository.findById(meeting.getRevivalId()).orElseThrow());
            if (repository.existsByRetryOf(email.getId())) {
                throw new BadRequestException("error.mail.nothingToRetry");
            }
            return new Earlier(email.getId(), email.getMeetingId(), email.getLanguage(), email.getRecipients().stream()
                    .filter(recipient -> recipient.status() == MinutesEmailRecipientStatus.FAILED)
                    .map(MinutesEmailRecipient::personId)
                    .collect(Collectors.toSet()));
        }));
        final Prepared prepared = prepare(earlier.meetingId(), earlier.language());

        if (earlier.failed().isEmpty()) {
            throw new BadRequestException("error.mail.nothingToRetry");
        }

        final List<Person> people = recipients(prepared.revival()).people().stream()
                .filter(person -> earlier.failed().contains(person.getId()))
                .toList();

        if (people.isEmpty()) {
            throw new BadRequestException("error.mail.nothingToRetry");
        }
        return sendTo(prepared, people, 0, earlier.id(), locale);
    }

    /**
     * The send's meeting, or not found for the send itself, so a refusal does not tell whether the send exists.
     */
    private Meeting readableMeeting(final MinutesEmail email) {
        try {
            return meetingService.readable(email.getMeetingId());
        } catch (final ResourceNotFoundException e) {
            throw new ResourceNotFoundException(MinutesEmail.class, email.getId());
        }
    }

    private Prepared prepare(final Long meetingId, final String language) {
        if (!LANGUAGES.contains(language)) {
            throw new BadRequestException("error.mail.language");
        }
        if (!mailer.available()) {
            throw new MailUnavailableException(null);
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            final Caller caller = callerService.current();
            final Meeting meeting = meetingService.readable(meetingId);
            final Revival revival = revivalRepository.findById(meeting.getRevivalId()).orElseThrow();

            accessPolicy.requireWriteRevivalWork(caller, revival);
            if (meeting.getStatus() != MeetingStatus.CLOSED) {
                throw new BadRequestException("error.meeting.notClosed");
            }
            return new Prepared(caller, meetingService.toDto(meeting, Locale.of(language)), revival, language);
        }));
    }

    /**
     * Active directeurs and steering group members who can see the revival in its status; waiting members are counted.
     */
    private Recipients recipients(final Revival revival) {
        return Objects.requireNonNull(transactions.execute(status -> {
            final List<RevivalMember> members = memberRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revival.getId()).stream()
                    .filter(member -> RECIPIENT_ROLES.contains(member.getRole()))
                    .toList();
            final List<Long> ids = members.stream()
                    .filter(member -> member.getPersonId() != null && AccessPolicy.visibleStatuses(member.getRole()).contains(revival.getStatus()))
                    .map(RevivalMember::getPersonId)
                    .filter(Objects::nonNull)
                    .toList();
            final Map<Long, Person> people = personRepository.findAllById(ids).stream().collect(Collectors.toMap(Person::getId, Function.identity()));

            return new Recipients(ids.stream().map(people::get).filter(Objects::nonNull).toList(),
                    (int) members.stream().filter(member -> member.getPersonId() == null).count());
        }));
    }

    private MinutesEmailDto sendTo(final Prepared prepared, final List<Person> people, final int waiting, final @Nullable Long retryOf,
                                   final Locale locale) {
        final MinutesMailer.Context context = new MinutesMailer.Context(titles.ofCached(prepared.revival(), Locale.of(prepared.language())),
                Objects.requireNonNullElse(prepared.caller().person().getDisplayName(), ""));
        final Map<Long, MinutesEmailRecipientStatus> results = new LinkedHashMap<>();

        for (final Person person : people) {
            final boolean sent = mailer.send(mailer.message(prepared.meeting(), context, Locale.of(prepared.language()), person.getEmail()));

            results.put(person.getId(), sent ? MinutesEmailRecipientStatus.SENT : MinutesEmailRecipientStatus.FAILED);
            if (!sent) {
                log.warn("Minutes of meeting {} could not be sent to person {}", prepared.meeting().id(), person.getId());
            }
        }
        if (!results.isEmpty() && !results.containsValue(MinutesEmailRecipientStatus.SENT)) {
            log.warn("Minutes of meeting {} could not be sent to anyone", prepared.meeting().id());
            throw new MailUnavailableException(null);
        }
        log.info("Sent the minutes of meeting {} to {} recipients, {} failed", prepared.meeting().id(),
                results.values().stream().filter(status -> status == MinutesEmailRecipientStatus.SENT).count(),
                results.values().stream().filter(status -> status == MinutesEmailRecipientStatus.FAILED).count());

        return Objects.requireNonNull(transactions.execute(status -> {
            AuditContext.operation(retryOf == null ? "minutesEmailSend" : "minutesEmailRetry");

            final MinutesEmail email = new MinutesEmail();

            email.setMeetingId(prepared.meeting().id());
            email.setLanguage(prepared.language());
            email.setSentBy(prepared.caller().person().getId());
            email.setSentAt(clock.instant());
            email.setRetryOf(retryOf);
            email.setWaitingLeftOut(waiting);
            email.setRecipients(new ArrayList<>(results.entrySet().stream()
                    .map(entry -> new MinutesEmailRecipient(entry.getKey(), entry.getValue())).toList()));
            return toDtos(List.of(repository.save(email)), locale).getFirst();
        }));
    }

    private List<MinutesEmailDto> toDtos(final List<MinutesEmail> emails, final Locale locale) {
        final Set<Long> people = new HashSet<>();

        emails.forEach(email -> {
            people.add(email.getSentBy());
            email.getRecipients().forEach(recipient -> people.add(recipient.personId()));
        });

        final Map<Long, String> names = people.isEmpty() ? Map.of() : personNames.byIds(people, locale);
        final Function<Long, PersonRefDto> person = id -> new PersonRefDto(id, names.get(id));

        return emails.stream().map(email -> new MinutesEmailDto(email.getId(), email.getLanguage(), person.apply(email.getSentBy()),
                email.getSentAt(), email.getRecipients().stream()
                .map(recipient -> new MinutesEmailDto.Recipient(person.apply(recipient.personId()), recipient.status())).toList(),
                email.getWaitingLeftOut())).toList();
    }

    private record Earlier(Long id, Long meetingId, String language, Set<Long> failed) {
    }

    private record Prepared(Caller caller, MeetingDto meeting, Revival revival, String language) {
    }

    private record Recipients(List<Person> people, int waiting) {
    }
}
