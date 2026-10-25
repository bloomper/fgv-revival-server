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

package nu.fgv.revival.server.mailing;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.audit.AuditSource;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.RevivalMember;
import nu.fgv.revival.server.member.RevivalMemberRepository;
import nu.fgv.revival.server.member.RevivalRole;
import nu.fgv.revival.server.person.Person;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.util.Locales;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasText;

/**
 * Reminds a revival's owners of its mailing plan: once per plan entry, its directeurs
 * and the steering group members with {@code COMMS} get an email when the entry is due within
 * {@value #DAYS_AHEAD} days or overdue by at most {@value #DAYS_OVERDUE} days and nothing has been sent for it. Only
 * revivals that are {@code ORGANISING} or {@code OPEN}. Runs daily at 07:30 Swedish time as the system, on the
 * scheduler the participants' daily job uses; nothing is sent to participants. Logs counts only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@Slf4j
public class MailingReminders {

    static final String OPERATION = "mailingReminders";
    static final int DAYS_AHEAD = 3;
    static final int DAYS_OVERDUE = 14;
    private static final Set<RevivalStatus> STATUSES = EnumSet.of(RevivalStatus.ORGANISING, RevivalStatus.OPEN);

    private final MailingService mailingService;
    private final MailingReminderRepository reminderRepository;
    private final MailingMailer mailer;
    private final RevivalRepository revivalRepository;
    private final RevivalMemberRepository memberRepository;
    private final PersonRepository personRepository;
    private final RevivalTitles titles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public MailingReminders(final MailingService mailingService, final MailingReminderRepository reminderRepository, final MailingMailer mailer,
                            final RevivalRepository revivalRepository, final RevivalMemberRepository memberRepository,
                            final PersonRepository personRepository, final RevivalTitles titles,
                            final PlatformTransactionManager transactionManager, final Clock clock) {
        this.mailingService = mailingService;
        this.reminderRepository = reminderRepository;
        this.mailer = mailer;
        this.revivalRepository = revivalRepository;
        this.memberRepository = memberRepository;
        this.personRepository = personRepository;
        this.titles = titles;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 7 * * *", zone = "Europe/Stockholm")
    public void daily() {
        if (!mailer.available()) {
            log.info("No mail server is configured, so no mailing reminders were sent");
            return;
        }

        int reminded = 0;

        for (final Long revivalId : revivalRepository.findAll().stream().filter(revival -> STATUSES.contains(revival.getStatus()))
                .map(Revival::getId).toList()) {
            try {
                reminded += Objects.requireNonNull(transactions.execute(_ -> {
                    AuditContext.stamp(new AuditContext.Origin(AuditSource.SYSTEM, OPERATION, null));
                    return remind(revivalRepository.findById(revivalId).orElseThrow());
                }));
            } catch (final RuntimeException e) {
                log.warn("The mailing reminders of revival {} failed: {}", revivalId, e.getClass().getSimpleName());
            }
        }
        if (reminded > 0) {
            log.info("Reminded the owners of {} planned mailings", reminded);
        }
    }

    /**
     * Reminds the revival's owners of its entries that are due and not reminded of yet; returns how many entries.
     */
    int remind(final Revival revival) {
        final LocalDate today = LocalDate.now(clock);
        final Set<String> done = reminderRepository.findByRevivalId(revival.getId()).stream()
                .map(reminder -> MailingService.key(reminder.getTemplateId(), reminder.getEventId()))
                .collect(Collectors.toSet());
        final List<MailingService.PlanItem> due = mailingService.planItems(revival).stream()
                .filter(item -> !item.sent() && !item.dueDate().isAfter(today.plusDays(DAYS_AHEAD))
                        && !item.dueDate().isBefore(today.minusDays(DAYS_OVERDUE)))
                .filter(item -> !done.contains(MailingService.key(item.template().getId(), item.event() == null ? null : item.event().getId())))
                .toList();

        if (due.isEmpty()) {
            return 0;
        }

        final List<String> owners = owners(revival);
        final List<MailingMailer.ReminderLine> lines = due.stream()
                .map(item -> new MailingMailer.ReminderLine(item.template().getNameSv() + (item.event() == null ? "" : " – " + item.event().getTitle()),
                        item.dueDate().toString()))
                .toList();
        final String title = titles.ofCached(revival, Locales.SWEDISH);
        boolean sent = false;

        for (final String owner : owners) {
            sent |= mailer.sendReminder(revival.getId(), title, lines, owner);
        }
        if (!sent) {
            return 0;
        }
        for (final MailingService.PlanItem item : due) {
            final MailingReminder reminder = new MailingReminder();

            reminder.setRevivalId(revival.getId());
            reminder.setTemplateId(item.template().getId());
            reminder.setEventId(item.event() == null ? null : item.event().getId());
            reminder.setSentOn(today);
            reminderRepository.saveAndFlush(reminder);
        }
        return due.size();
    }

    /**
     * The login addresses of the revival's directeurs and the steering group members with {@code COMMS}.
     */
    private List<String> owners(final Revival revival) {
        final List<Long> ids = memberRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revival.getId()).stream()
                .filter(member -> member.getPersonId() != null && (member.getRole() == RevivalRole.DIRECTEUR
                        || member.getRole() == RevivalRole.STEERING_GROUP && member.getCapabilities().contains(AccessPolicy.COMMS)))
                .map(RevivalMember::getPersonId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        return personRepository.findAllById(ids).stream().map(Person::getEmail).filter(email -> hasText(email)).distinct().toList();
    }
}
