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
import nu.fgv.revival.server.decisionlog.LogEntryStatus;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.util.error.MailUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Writes and sends a meeting's minutes as plain text, one message per recipient, as Spexregister sends its mail.
 * Never logs addresses or content.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@Slf4j
public class MinutesMailer {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MessageSource messageSource;
    private final String from;
    private final String webUrl;

    public MinutesMailer(final ObjectProvider<JavaMailSender> mailSender,
                         final MessageSource messageSource,
                         @Value("${fgv-revival.mail.from}") final String from,
                         @Value("${fgv-revival.web-url}") final String webUrl) {
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.from = from;
        this.webUrl = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    }

    private static void section(final List<String> lines, final String heading, final List<MeetingDto.RowDto> rows, final RowText text) {
        if (rows.isEmpty()) {
            return;
        }
        lines.add("");
        lines.add(heading);
        rows.forEach(row -> lines.add(text.apply(row)));
    }

    private static @Nullable String owner(final @Nullable OwnerDto owner) {
        if (owner == null) {
            return null;
        }
        return owner.person() != null ? owner.person().displayName() : owner.name();
    }

    /**
     * Whether a mail server is configured at all.
     */
    public boolean available() {
        return mailSender.getIfAvailable() != null;
    }

    public SimpleMailMessage message(final MeetingDto meeting, final Context context, final Locale locale, final String to) {
        final SimpleMailMessage message = new SimpleMailMessage();
        final String heading = meeting.title() != null ? meeting.title() : text("minutes.mail.meeting", locale) + " " + meeting.date();

        message.setFrom(from);
        message.setTo(to);
        message.setSubject(text("minutes.mail.subject", locale, context.revivalTitle(),
                meeting.title() != null ? meeting.title() : meeting.date().toString()));
        message.setText(body(meeting, context, locale, heading));
        return message;
    }

    String body(final MeetingDto meeting, final Context context, final Locale locale, final String heading) {
        final MeetingDto.MinutesDto minutes = Objects.requireNonNull(meeting.minutes());
        final List<String> lines = new ArrayList<>();
        final String present = minutes.attendees().stream().map(PersonRefDto::displayName).filter(Objects::nonNull).collect(Collectors.joining(", "));

        lines.add(meeting.title() != null ? meeting.title() + " " + meeting.date() : heading);
        lines.add(text("minutes.mail.present", locale, minutes.othersPresent() == null ? present
                : present.isEmpty() ? minutes.othersPresent() : present + "; " + minutes.othersPresent()));
        if (minutes.recorder() != null && minutes.recorder().displayName() != null) {
            lines.add(text("minutes.mail.recorder", locale, minutes.recorder().displayName()));
        }
        section(lines, text("minutes.mail.decisions", locale), minutes.decisions(),
                row -> "#%d %s%s".formatted(row.number(), row.text(), owner(row.owner()) == null ? "" : " (" + owner(row.owner()) + ")"));
        section(lines, text("minutes.mail.todos", locale), minutes.todos(), row -> {
            final StringBuilder line = new StringBuilder("#%d %s".formatted(row.number(), row.text()));

            if (owner(row.owner()) != null) {
                line.append(". ").append(text("minutes.mail.owner", locale, owner(row.owner())));
            }
            if (row.deadline() != null) {
                line.append(". ").append(text("minutes.mail.deadline", locale, row.deadline().toString()));
            }
            return line.toString();
        });
        section(lines, text("minutes.mail.questions", locale), minutes.questions(), row -> "#%d %s".formatted(row.number(), row.text()));
        section(lines, text("minutes.mail.closed", locale), minutes.closed(),
                row -> "#%d %s (%s)".formatted(row.number(), row.text(), status(row.status(), locale)));
        lines.add("");
        lines.add(text("minutes.mail.link", locale, "%s/revivals/%d/meetings/%d".formatted(webUrl, meeting.revivalId(), meeting.id())));
        lines.add("");
        lines.add(text("minutes.mail.footer", locale, context.senderName()));
        return String.join("\n", lines);
    }

    /**
     * Sends one message. Returns false when the mail server refused it.
     */
    public boolean send(final SimpleMailMessage message) {
        final JavaMailSender sender = mailSender.getIfAvailable();

        if (sender == null) {
            throw new MailUnavailableException(null);
        }
        try {
            sender.send(message);
            return true;
        } catch (final MailException e) {
            log.warn("The mail server refused a message: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private String status(final LogEntryStatus status, final Locale locale) {
        return text("minutes.status." + status.name(), locale);
    }

    private String text(final String key, final Locale locale, final Object... arguments) {
        return messageSource.getMessage(key, arguments, key, locale);
    }

    private interface RowText {
        String apply(MeetingDto.RowDto row);
    }

    /**
     * What the revival and the sender look like in the message.
     */
    public record Context(String revivalTitle, String senderName) {
    }
}
