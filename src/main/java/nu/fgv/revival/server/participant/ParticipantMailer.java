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

package nu.fgv.revival.server.participant;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalShow;
import nu.fgv.revival.server.util.Amounts;
import nu.fgv.revival.server.util.MailSubjects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Nyuppsättning's own emails to participants, as plain text like the minutes: the invitation or a
 * reminder, with the link to their page and how they log in, and the confirmation of a first yes. Sent after the
 * transaction commits and never failing it; addresses and content are never logged.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@Slf4j
public class ParticipantMailer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MessageSource messageSource;
    private final String from;
    private final String webUrl;

    public ParticipantMailer(final ObjectProvider<JavaMailSender> mailSender,
                             final MessageSource messageSource,
                             @Value("${fgv-revival.mail.from}") final String from,
                             @Value("${fgv-revival.web-url}") final String webUrl) {
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.from = from;
        this.webUrl = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    }

    String link(final Long revivalId) {
        return "%s/revivals/%d/me".formatted(webUrl, revivalId);
    }

    SimpleMailMessage invitation(final Revival revival, final Invitation invitation, final String to, final Locale locale) {
        final List<String> lines = new ArrayList<>();
        final String prefix = invitation.reminder() ? "participant.mail.reminder." : "participant.mail.invitation.";

        lines.add(text(prefix + "intro", locale, invitation.inviterName(), invitation.revivalTitle()));
        lines.addAll(dates(revival, locale));
        lines.add("");
        lines.add(text("participant.mail.invitation.respond", locale));
        lines.add(link(revival.getId()));
        lines.add("");
        lines.add(text("participant.mail.invitation.login", locale, to));
        lines.add(text("participant.mail.invitation.newAccount", locale));
        lines.add(text("participant.mail.invitation.access", locale));
        lines.add("");
        lines.add(text("participant.mail.footer", locale, invitation.inviterName()));

        return message(to, text(prefix + "subject", locale, invitation.revivalTitle()), lines);
    }

    SimpleMailMessage confirmation(final Revival revival, final Confirmation confirmation, final String to, final Locale locale) {
        final List<String> lines = new ArrayList<>();

        lines.add(text("participant.mail.confirmation.intro", locale, confirmation.revivalTitle()));
        lines.addAll(dates(revival, locale));
        if (confirmation.fee() != null && confirmation.fee().signum() > 0) {
            lines.add("");
            lines.add(text("participant.mail.confirmation.fee", locale, Amounts.format(confirmation.fee())));
            if (confirmation.swishNumber() != null) {
                lines.add(text("participant.mail.confirmation.swish", locale, confirmation.swishNumber(), confirmation.swishMessage()));
            }
        }
        if (confirmation.whatsappLink() != null) {
            lines.add("");
            lines.add(text("participant.mail.confirmation.whatsapp", locale));
            lines.add(confirmation.whatsappLink());
        }
        lines.add("");
        lines.add(text("participant.mail.confirmation.page", locale));
        lines.add(link(revival.getId()));

        return message(to, text("participant.mail.confirmation.subject", locale, confirmation.revivalTitle()), lines);
    }

    /**
     * The premiere and the shows, each on its own line, yyyy-mm-dd and hh:mm as everywhere else.
     */
    private List<String> dates(final Revival revival, final Locale locale) {
        final List<String> lines = new ArrayList<>();

        if (revival.getPremiereDate() != null) {
            lines.add(text("participant.mail.premiere", locale, revival.getPremiereDate().toString()));
        }
        revival.getShows().stream()
                .sorted(Comparator.comparing(RevivalShow::getDate).thenComparing(show -> show.getTime() == null ? "" : show.getTime().format(TIME)))
                .forEach(show -> lines.add(text("participant.mail.show", locale, (show.getDate() + " " + (show.getTime() == null ? "" : show.getTime().format(TIME))
                        + " " + (show.getVenue() == null ? "" : show.getVenue())).strip().replaceAll("\\s+", " "))));
        return lines;
    }

    private SimpleMailMessage message(final String to, final String subject, final List<String> lines) {
        final SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(from);
        message.setTo(to);
        message.setSubject(MailSubjects.oneLine(subject));
        message.setText(String.join("\n", lines));
        return message;
    }

    /**
     * Sends the messages once the current transaction commits, or at once without one. Never fails.
     */
    void sendAfterCommit(final List<SimpleMailMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(messages);
                }
            });
        } else {
            send(messages);
        }
    }

    private void send(final List<SimpleMailMessage> messages) {
        final JavaMailSender sender = mailSender.getIfAvailable();

        if (sender == null) {
            log.info("No mail server is configured, so {} participant emails were not sent", messages.size());
            return;
        }
        for (final SimpleMailMessage message : messages) {
            try {
                sender.send(message);
            } catch (final MailException e) {
                log.warn("The mail server refused a participant email: {}", e.getClass().getSimpleName());
            } catch (final RuntimeException e) {
                log.warn("Could not send a participant email: {}", e.getClass().getSimpleName());
            }
        }
    }

    private String text(final String key, final Locale locale, final Object... arguments) {
        return messageSource.getMessage(key, arguments, key, locale);
    }

    /**
     * What an invitation says about the revival and who sends it.
     */
    public record Invitation(String revivalTitle, String inviterName, boolean reminder) {
    }

    /**
     * What a confirmation of a yes says; fee and Swish only when a fee is due, WhatsApp only when set.
     */
    public record Confirmation(String revivalTitle, @Nullable BigDecimal fee, @Nullable String swishNumber,
                               @Nullable String swishMessage,
                               @Nullable String whatsappLink) {
    }
}
