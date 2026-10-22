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

import nu.fgv.revival.server.decisionlog.LogEntryStatus;
import nu.fgv.revival.server.decisionlog.LogEntryType;
import nu.fgv.revival.server.member.OwnerDto;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.util.error.MailUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The minutes email's text in both languages (contracts/email.md).
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
class MinutesMailerTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    private final MinutesMailer mailer = new MinutesMailer(provider, messages(), "noreply@example.org", "https://revival.example.org/");
    private final MinutesMailer.Context context = new MinutesMailer.Context("Ali Baba 2028", "Stina Styrgrupp");

    private static ResourceBundleMessageSource messages() {
        final ResourceBundleMessageSource source = new ResourceBundleMessageSource();

        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static MeetingDto meeting(final String title, final List<MeetingDto.RowDto> todos) {
        final OwnerDto kim = new OwnerDto(new OwnerDto.OwnerPersonDto(11L, "Kim Svensson", true), null);
        final MeetingDto.MinutesDto minutes = new MeetingDto.MinutesDto(
                List.of(new MeetingDto.RowDto(7L, 7, LogEntryType.DECISION, "Vi spelar i RunAn", kim, null, LogEntryStatus.OPEN)),
                todos, List.of(), List.of(new MeetingDto.RowDto(2L, 2, LogEntryType.TODO, "Boka lokal", null, null, LogEntryStatus.DONE)),
                List.of("Budget"), List.of(new PersonRefDto(11L, "Kim Svensson"), new PersonRefDto(12L, "Stina Styrgrupp")),
                "Sam från lokalen", new PersonRefDto(12L, "Stina Styrgrupp"));

        return new MeetingDto(5L, 1L, LocalDate.of(2026, 10, 21), title, MeetingStatus.CLOSED, List.of(), null, List.of(), null, null, null,
                minutes, 3L);
    }

    @Test
    void writesTheSwedishMessage() {
        final SimpleMailMessage message = mailer.message(meeting("Direktionsmöte 4", List.of(new MeetingDto.RowDto(8L, 8, LogEntryType.TODO,
                "Skicka offert", new OwnerDto(null, "Sam"), LocalDate.of(2026, 11, 1), LogEntryStatus.OPEN))), context, Locale.of("sv"), "kim@example.org");

        assertThat(message.getFrom()).isEqualTo("noreply@example.org");
        assertThat(message.getTo()).containsExactly("kim@example.org");
        assertThat(message.getCc()).isNull();
        assertThat(message.getBcc()).isNull();
        assertThat(message.getSubject()).isEqualTo("Protokoll: Ali Baba 2028, Direktionsmöte 4");
        assertThat(message.getText()).isEqualTo("""
                Direktionsmöte 4 2026-10-21
                Närvarande: Kim Svensson, Stina Styrgrupp; Sam från lokalen
                Protokollförare: Stina Styrgrupp

                Beslut
                #7 Vi spelar i RunAn (Kim Svensson)

                Att göra
                #8 Skicka offert. Ansvarig: Sam. Klart senast: 2026-11-01

                Avslutat på mötet
                #2 Boka lokal (klar)

                Öppna mötet: https://revival.example.org/revivals/1/meetings/5

                Skickat av Stina Styrgrupp från Nyuppsättning.""");
    }

    @Test
    void writesTheEnglishMessageAndLeavesEmptySectionsOut() {
        final SimpleMailMessage message = mailer.message(meeting(null, List.of()), context, Locale.ENGLISH, "kim@example.org");

        assertThat(message.getSubject()).isEqualTo("Minutes: Ali Baba 2028, 2026-10-21");
        assertThat(message.getText()).startsWith("Steering group meeting 2026-10-21\nPresent:")
                .contains("Decisions\n#7 Vi spelar i RunAn (Kim Svensson)")
                .doesNotContain("To-dos")
                .doesNotContain("Questions")
                .contains("Closed in the meeting\n#2 Boka lokal (done)")
                .endsWith("Sent by Stina Styrgrupp from Nyuppsättning.");
    }

    @Test
    void sendingReportsARefusedMessage() {
        when(provider.getIfAvailable()).thenReturn(sender);
        doThrow(new MailSendException("refused")).when(sender).send(any(SimpleMailMessage.class));

        assertThat(mailer.available()).isTrue();
        assertThat(mailer.send(new SimpleMailMessage())).isFalse();
    }

    @Test
    void withoutAMailServerNothingCanBeSent() {
        assertThat(mailer.available()).isFalse();
        assertThatThrownBy(() -> mailer.send(new SimpleMailMessage())).isInstanceOf(MailUnavailableException.class);
    }
}
