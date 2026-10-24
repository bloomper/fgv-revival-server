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

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.guide.GuideMarkdown;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.springframework.util.StringUtils.hasText;

/**
 * Writes and sends mailings (M6B-FR-9), one message per recipient: plain text with the Markdown as written, and HTML
 * rendered with the guide's safe Markdown subset in a minimal page with the revival's title and who sent it. Replies go
 * to the sender. Also the plan's reminders to the owners (M6B-FR-11). Never logs addresses or content.
 *
 * @author Anders Jacobsson
 * @since 0.8
 */
@Component
@Slf4j
public class MailingMailer {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final GuideMarkdown markdown;
    private final MessageSource messageSource;
    private final String from;
    private final String webUrl;

    public MailingMailer(final ObjectProvider<JavaMailSender> mailSender,
                         final GuideMarkdown markdown,
                         final MessageSource messageSource,
                         @Value("${fgv-revival.mail.from}") final String from,
                         @Value("${fgv-revival.web-url}") final String webUrl) {
        this.mailSender = mailSender;
        this.markdown = markdown;
        this.messageSource = messageSource;
        this.from = from;
        this.webUrl = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    }

    /**
     * Whether a mail server is configured at all.
     */
    public boolean available() {
        return mailSender.getIfAvailable() != null;
    }

    /**
     * A mailing as it goes out: the revival's title and the sender for the page and footer, the sender's address for
     * replies, and the footer's language.
     */
    record Content(String revivalTitle, String senderName, @Nullable String replyTo, String subject, String body, Locale locale) {
    }

    String plainText(final Content content) {
        return content.body().strip() + "\n\n-- \n" + footer(content);
    }

    String html(final Content content) {
        final String language = "en".equals(content.locale().getLanguage()) ? "en" : "sv";

        return """
                <!DOCTYPE html>
                <html lang="%s">
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>%s</title></head>
                <body style="margin:0;padding:16px;background:#ffffff;color:#1a1a1a;font-family:Helvetica,Arial,sans-serif;font-size:16px;line-height:1.5">
                <div style="max-width:640px;margin:0 auto">
                <p style="margin:0 0 16px;color:#555555;font-size:14px">%s</p>
                %s
                <hr style="border:none;border-top:1px solid #dddddd;margin:24px 0 12px">
                <p style="margin:0;color:#555555;font-size:13px">%s</p>
                </div>
                </body>
                </html>
                """.formatted(language, GuideMarkdown.escapeHtml(content.subject()), GuideMarkdown.escapeHtml(content.revivalTitle()),
                markdown.toHtml(content.body()), GuideMarkdown.escapeHtml(footer(content)));
    }

    private String footer(final Content content) {
        return text("mailing.mail.footer", content.locale(), content.senderName());
    }

    /**
     * Sends the mailing to one address. Returns false when the message could not be made or the mail server refused it.
     */
    boolean send(final Content content, final String to) {
        final JavaMailSender sender = mailSender.getIfAvailable();

        if (sender == null) {
            return false;
        }
        try {
            final MimeMessage message = sender.createMimeMessage();
            final MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(from);
            helper.setTo(to);
            if (hasText(content.replyTo())) {
                helper.setReplyTo(content.replyTo());
            }
            helper.setSubject(content.subject());
            helper.setText(plainText(content), html(content));
            sender.send(message);
            return true;
        } catch (final MessagingException | MailException e) {
            log.warn("A mailing could not be sent to one recipient: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * One entry of a reminder: what to send and when it is due.
     */
    record ReminderLine(String name, String dueDate) {
    }

    /**
     * Reminds an owner of the plan's mailings that are due or overdue, in Swedish and English, with the link to the
     * revival's mailings. Returns false when the mail server refused it.
     */
    boolean sendReminder(final Long revivalId, final String revivalTitle, final List<ReminderLine> lines, final String to) {
        final JavaMailSender sender = mailSender.getIfAvailable();

        if (sender == null) {
            return false;
        }

        final StringBuilder text = new StringBuilder();
        final String link = "%s/revivals/%d/mailings".formatted(webUrl, revivalId);

        for (final Locale locale : List.of(Locale.of("sv"), Locale.ENGLISH)) {
            if (!text.isEmpty()) {
                text.append("\n\n---\n\n");
            }
            text.append(text("mailing.reminder.intro", locale, revivalTitle)).append("\n\n");
            lines.forEach(line -> text.append("- ").append(text("mailing.reminder.line", locale, line.name(), line.dueDate())).append('\n'));
            text.append('\n').append(text("mailing.reminder.link", locale, link));
        }

        final SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(from);
        message.setTo(to);
        message.setSubject(text("mailing.reminder.subject", Locale.of("sv"), revivalTitle));
        message.setText(text.toString());
        try {
            sender.send(message);
            return true;
        } catch (final MailException e) {
            log.warn("A mailing reminder could not be sent: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private String text(final String key, final Locale locale, final Object... arguments) {
        return messageSource.getMessage(key, arguments, key, locale);
    }
}
