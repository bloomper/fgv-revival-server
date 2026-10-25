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

import nu.fgv.revival.server.util.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What people may type for participants.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ParticipantRulesTest {

    @Test
    void namesAreTrimmedAndAtMostOneHundredCharacters() {
        assertThat(ParticipantRules.name("  Anna ")).isEqualTo("Anna");
        assertThatThrownBy(() -> ParticipantRules.name(" ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ParticipantRules.name("a".repeat(101))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void emailsAreLowerCaseAndValid() {
        assertThat(ParticipantRules.email(" Anna@Example.ORG ")).isEqualTo("anna@example.org");
        assertThat(ParticipantRules.email(" ")).isNull();
        assertThatThrownBy(() -> ParticipantRules.email("anna@")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void phonesHaveDigits() {
        assertThat(ParticipantRules.phone("+46 70-123 45 67")).isEqualTo("+46 70-123 45 67");
        assertThatThrownBy(() -> ParticipantRules.phone("call me")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ParticipantRules.phone("123")).isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123 456 78 90", "123-4567890", "0701234567"})
    void swishNumbersHaveTenDigits(final String number) {
        assertThat(ParticipantRules.swishNumber(number)).hasSize(10).containsOnlyDigits();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "123456789012", "070-ABC"})
    void otherSwishNumbersAreRefused(final String number) {
        assertThatThrownBy(() -> ParticipantRules.swishNumber(number)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void whatsappLinksAreGroupInvitations() {
        assertThat(ParticipantRules.whatsappLink(" https://chat.whatsapp.com/AbC123 ")).isEqualTo("https://chat.whatsapp.com/AbC123");
        assertThatThrownBy(() -> ParticipantRules.whatsappLink("https://evil.example.org/chat.whatsapp.com/x")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ParticipantRules.whatsappLink("http://chat.whatsapp.com/x")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void amountsAreKronorUpToOneHundredThousand() {
        assertThat(ParticipantRules.amount(new BigDecimal("1200"))).isEqualByComparingTo("1200.00");
        assertThatThrownBy(() -> ParticipantRules.amount(new BigDecimal("-1"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ParticipantRules.amount(new BigDecimal("100000.01"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ParticipantRules.amount(new BigDecimal("1.005"))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aReferralsNameIsSplitAtTheLastSpace() {
        assertThat(ParticipantRules.splitName("Anna Maria  Andersson")).containsExactly("Anna Maria", "Andersson");
        assertThat(ParticipantRules.splitName("Cher")).containsExactly("Cher", "—");
        assertThat(ParticipantRules.looksLikeEmail("anna@example.org")).isTrue();
        assertThat(ParticipantRules.looksLikeEmail("070-1234567")).isFalse();
    }
}
