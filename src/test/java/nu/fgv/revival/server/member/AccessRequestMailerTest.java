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

import nu.fgv.revival.server.TestCallers;
import nu.fgv.revival.server.person.UserDirectory;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.util.error.DirectoryUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static nu.fgv.revival.server.TestCallers.REVIVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 0.4
 */
@ExtendWith(MockitoExtension.class)
class AccessRequestMailerTest {

    private static final MemberAwaitingAccess EVENT = new MemberAwaitingAccess(REVIVAL_ID, "ny@example.org", "Nora Ny", "kc-1",
            RevivalRole.STEERING_GROUP, true, "Kim");

    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock
    private JavaMailSender mailSender;
    @Mock
    private UserDirectory directory;
    @Mock
    private RevivalRepository revivalRepository;
    @Mock
    private RevivalTitles titles;

    private AccessRequestMailer mailer;

    @BeforeEach
    void setUp() {
        final ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();

        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        mailer = new AccessRequestMailer(mailSenderProvider, messageSource, directory, revivalRepository, titles, "noreply@example.org",
                "https://revival.example.org/");

        final Revival revival = TestCallers.revival(RevivalStatus.ORGANISING);

        lenient().when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        lenient().when(revivalRepository.findById(REVIVAL_ID)).thenReturn(Optional.of(revival));
        lenient().when(titles.ofCached(any(), any())).thenReturn("Ali Baba 2028");
        lenient().when(directory.roleMappingUrl("kc-1")).thenReturn("https://auth.example.org/admin/master/console/#/fgv/users/kc-1/role-mapping");
    }

    private static UserDirectory.DirectoryUser admin(final String email, final Locale locale) {
        return new UserDirectory.DirectoryUser("kc-" + email, email, "Admin", true, locale);
    }

    @Test
    void everySuperAdminGetsOneMessageInTheirLanguage() {
        when(directory.superAdmins()).thenReturn(List.of(admin("sara@example.org", Locale.ENGLISH), admin("sven@example.org", null)));

        mailer.memberAwaitingAccess(EVENT);

        final ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);

        verify(mailSender, times(2)).send(sent.capture());
        assertThat(sent.getAllValues().getFirst()).satisfies(message -> {
            assertThat(message.getTo()).containsExactly("sara@example.org");
            assertThat(message.getFrom()).isEqualTo("noreply@example.org");
            assertThat(message.getSubject()).isEqualTo("Waiting for access to Nyuppsättning: Nora Ny (ny@example.org)");
            assertThat(message.getText())
                    .contains("Nora Ny (ny@example.org) has been added as steering group member in Ali Baba 2028 by Kim")
                    .contains("created an FGV account for ny@example.org")
                    .contains("role USER on the client fgv-revival")
                    .contains("https://auth.example.org/admin/master/console/#/fgv/users/kc-1/role-mapping")
                    .contains("https://revival.example.org/revivals/" + REVIVAL_ID);
        });
        assertThat(sent.getAllValues().getLast()).satisfies(message -> {
            assertThat(message.getTo()).containsExactly("sven@example.org");
            assertThat(message.getSubject()).startsWith("Väntar på åtkomst till Nyuppsättning");
            assertThat(message.getText()).contains("har lagts till som styrgruppsmedlem i Ali Baba 2028 av Kim");
        });
    }

    @Test
    void anExistingAccountIsNotSaidToBeInvited() {
        final MemberAwaitingAccess existing = new MemberAwaitingAccess(REVIVAL_ID, "ny@example.org", null, "kc-1", RevivalRole.DIRECTEUR, false, "Kim");

        assertThat(mailer.message(existing, "sara@example.org", Locale.ENGLISH).getText())
                .startsWith("ny@example.org has been added as directeur")
                .doesNotContain("created an FGV account");
    }

    @Test
    void nothingIsSentWithoutAMailServerAndNothingFailsWhenKeycloakOrTheMailServerDoes() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);
        mailer.memberAwaitingAccess(EVENT);
        verify(directory, never()).superAdmins();

        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(directory.superAdmins()).thenThrow(new DirectoryUnavailableException(new jakarta.ws.rs.ProcessingException("down")));
        mailer.memberAwaitingAccess(EVENT);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        doReturn(List.of(admin("sara@example.org", null), admin("sven@example.org", null))).when(directory).superAdmins();
        doThrow(new MailSendException("refused")).when(mailSender).send(any(SimpleMailMessage.class));
        mailer.memberAwaitingAccess(EVENT);
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
    }
}
