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

package nu.fgv.revival.server.member;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.person.UserDirectory;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.util.Locales;
import nu.fgv.revival.server.util.error.DirectoryUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tells the super admins, by email, that a member is waiting for access, so that one of them grants
 * {@code fgv-revival}'s {@code USER} in Keycloak (product spec, section 3). {@code fgv-keycloak}'s
 * {@code fgv-admin-notification} only reports users who register themselves, not users created through the admin API,
 * so the application sends this itself, through the same mail server as the minutes. One plain text message per super
 * admin, in their Keycloak language. Runs after the member is committed and never fails the add; addresses and names
 * are never logged.
 *
 * @author Anders Jacobsson
 * @since 0.4
 */
@Component
@Slf4j
public class AccessRequestMailer {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MessageSource messageSource;
    private final UserDirectory directory;
    private final RevivalRepository revivalRepository;
    private final RevivalTitles titles;
    private final String from;
    private final String webUrl;

    public AccessRequestMailer(final ObjectProvider<JavaMailSender> mailSender,
                               final MessageSource messageSource,
                               final UserDirectory directory,
                               final RevivalRepository revivalRepository,
                               final RevivalTitles titles,
                               @Value("${fgv-revival.mail.from}") final String from,
                               @Value("${fgv-revival.web-url}") final String webUrl) {
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.directory = directory;
        this.revivalRepository = revivalRepository;
        this.titles = titles;
        this.from = from;
        this.webUrl = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    }

    private static void send(final JavaMailSender sender, final SimpleMailMessage message) {
        try {
            sender.send(message);
        } catch (final MailException e) {
            log.warn("The mail server refused a message about a member waiting for access: {}", e.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void memberAwaitingAccess(final MemberAwaitingAccess event) {
        final JavaMailSender sender = mailSender.getIfAvailable();

        if (sender == null) {
            log.info("No mail server is configured, so the super admins are not told that a member of revival {} is waiting for access", event.revivalId());
            return;
        }
        try {
            final List<UserDirectory.DirectoryUser> superAdmins = directory.superAdmins();

            if (superAdmins.isEmpty()) {
                log.warn("No super admin to tell that a member of revival {} is waiting for access", event.revivalId());
            }
            superAdmins.forEach(admin -> send(sender, message(event, admin.email(), Locales.supported(admin.locale()))));
        } catch (final DirectoryUnavailableException e) {
            log.warn("Could not read the super admins from Keycloak, so they are not told that a member of revival {} is waiting for access: {}",
                    event.revivalId(), e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
        } catch (final RuntimeException e) {
            log.warn("Could not tell the super admins that a member of revival {} is waiting for access: {}", event.revivalId(), e.getClass().getSimpleName());
        }
    }

    /**
     * One message per super admin about everyone in the batch, e.g. a round of participant invitations (M6-FR-5).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void membersAwaitingAccess(final MembersAwaitingAccess event) {
        if (event.members().isEmpty()) {
            return;
        }

        final JavaMailSender sender = mailSender.getIfAvailable();

        if (sender == null) {
            log.info("No mail server is configured, so the super admins are not told that {} people of revival {} are waiting for access",
                    event.members().size(), event.revivalId());
            return;
        }
        try {
            final List<UserDirectory.DirectoryUser> superAdmins = directory.superAdmins();

            if (superAdmins.isEmpty()) {
                log.warn("No super admin to tell that people of revival {} are waiting for access", event.revivalId());
            }
            superAdmins.forEach(admin -> send(sender, message(event, admin.email(), Locales.supported(admin.locale()))));
        } catch (final DirectoryUnavailableException e) {
            log.warn("Could not read the super admins from Keycloak, so they are not told that people of revival {} are waiting for access: {}",
                    event.revivalId(), e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
        } catch (final RuntimeException e) {
            log.warn("Could not tell the super admins that people of revival {} are waiting for access: {}", event.revivalId(),
                    e.getClass().getSimpleName());
        }
    }

    SimpleMailMessage message(final MembersAwaitingAccess event, final String to, final Locale locale) {
        final SimpleMailMessage message = new SimpleMailMessage();
        final String revival = revivalRepository.findById(event.revivalId())
                .map(found -> titles.ofCached(found, locale))
                .orElseGet(() -> "#" + event.revivalId());
        final List<String> lines = new ArrayList<>();

        lines.add(text("member.awaitingAccess.batch.mail.added", locale, event.members().size(), revival,
                event.addedBy() == null ? text("person.unknown", locale) : event.addedBy()));
        lines.add("");
        lines.add(text("member.awaitingAccess.mail.grant", locale));
        for (final MembersAwaitingAccess.Waiting member : event.members()) {
            final String who = member.displayName() == null ? member.email() : "%s (%s)".formatted(member.displayName(), member.email());

            lines.add("");
            lines.add("%s, %s%s".formatted(who, text("member.role." + member.role().name(), locale),
                    member.invited() ? " " + text("member.awaitingAccess.batch.mail.invited", locale) : ""));
            lines.add(directory.roleMappingUrl(member.keycloakUserId()));
        }
        lines.add("");
        lines.add(text("member.awaitingAccess.mail.revival", locale, "%s/revivals/%d".formatted(webUrl, event.revivalId())));
        lines.add("");
        lines.add(text("member.awaitingAccess.mail.footer", locale));

        message.setFrom(from);
        message.setTo(to);
        message.setSubject(text("member.awaitingAccess.batch.mail.subject", locale, event.members().size(), revival));
        message.setText(String.join("\n", lines));
        return message;
    }

    SimpleMailMessage message(final MemberAwaitingAccess event, final String to, final Locale locale) {
        final SimpleMailMessage message = new SimpleMailMessage();
        final String who = event.displayName() == null ? event.email() : "%s (%s)".formatted(event.displayName(), event.email());
        final String revival = revivalRepository.findById(event.revivalId())
                .map(found -> titles.ofCached(found, locale))
                .orElseGet(() -> "#" + event.revivalId());
        final List<String> lines = new ArrayList<>();

        lines.add(text("member.awaitingAccess.mail.added", locale, who, text("member.role." + event.role().name(), locale), revival,
                event.addedBy() == null ? text("person.unknown", locale) : event.addedBy()));
        if (event.invited()) {
            lines.add("");
            lines.add(text("member.awaitingAccess.mail.invited", locale, event.email()));
        }
        lines.add("");
        lines.add(text("member.awaitingAccess.mail.grant", locale));
        lines.add(directory.roleMappingUrl(event.keycloakUserId()));
        lines.add("");
        lines.add(text("member.awaitingAccess.mail.revival", locale, "%s/revivals/%d".formatted(webUrl, event.revivalId())));
        lines.add("");
        lines.add(text("member.awaitingAccess.mail.footer", locale));

        message.setFrom(from);
        message.setTo(to);
        message.setSubject(text("member.awaitingAccess.mail.subject", locale, who));
        message.setText(String.join("\n", lines));
        return message;
    }

    private String text(final String key, final Locale locale, final Object... arguments) {
        return messageSource.getMessage(key, arguments, key, locale);
    }
}
