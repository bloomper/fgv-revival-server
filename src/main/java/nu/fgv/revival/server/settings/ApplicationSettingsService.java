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
package nu.fgv.revival.server.settings;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.Amounts;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * The application settings: the super admin reads and changes them; participations and new budgets use them without a
 * caller check.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ApplicationSettingsService {

    static final BigDecimal MAX_FEE = new BigDecimal("10000000");

    private final ApplicationSettingsRepository repository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;

    public ApplicationSettingsService(final ApplicationSettingsRepository repository, final CallerService callerService, final AccessPolicy accessPolicy) {
        this.repository = repository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
    }

    private static @Nullable String validTerms(final @Nullable String terms) {
        if (terms == null || terms.isBlank()) {
            return null;
        }

        final String trimmed = terms.strip();

        if (trimmed.length() > ApplicationSettings.TERMS_MAX_LENGTH) {
            throw new BadRequestException("error.settings.terms", ApplicationSettings.TERMS_MAX_LENGTH);
        }
        return trimmed;
    }

    private static @Nullable BigDecimal validFee(final @Nullable BigDecimal fee) {
        if (fee == null) {
            return null;
        }

        final BigDecimal exact = Amounts.exact(fee);

        if (exact == null || exact.signum() < 0 || exact.compareTo(MAX_FEE) > 0) {
            throw new BadRequestException("error.settings.fee", Amounts.format(MAX_FEE));
        }
        return exact;
    }

    @Transactional(readOnly = true)
    public ApplicationSettingsDto get() {
        accessPolicy.requireManageApplicationSettings(callerService.current());
        return ApplicationSettingsDto.of(settings());
    }

    @Transactional
    public ApplicationSettingsDto update(final Long version, final @Nullable String termsSv, final @Nullable String termsEn,
                                         final @Nullable BigDecimal defaultParticipationFee) {
        accessPolicy.requireManageApplicationSettings(callerService.current());

        final ApplicationSettings settings = settings();

        if (!Objects.equals(version, settings.getVersion() == null ? 0L : settings.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        AuditContext.operation("applicationSettingsUpdate");

        final String sv = validTerms(termsSv);

        settings.setTermsSv(sv);
        settings.setTermsEn(sv == null ? null : validTerms(termsEn));
        settings.setDefaultParticipationFee(validFee(defaultParticipationFee));
        return ApplicationSettingsDto.of(repository.saveAndFlush(settings));
    }

    /**
     * The terms every participant accepts, in the language when there is an English text. No caller check.
     */
    @Transactional(readOnly = true)
    public @Nullable String terms(final Locale locale) {
        final ApplicationSettings settings = settings();

        return "en".equals(locale.getLanguage()) && settings.getTermsEn() != null ? settings.getTermsEn() : settings.getTermsSv();
    }

    /**
     * The fee a new revival's budget starts with. No caller check.
     */
    @Transactional(readOnly = true)
    public @Nullable BigDecimal defaultParticipationFee() {
        return settings().getDefaultParticipationFee();
    }

    private ApplicationSettings settings() {
        return repository.findById(ApplicationSettings.ID).orElseGet(() -> {
            final ApplicationSettings settings = new ApplicationSettings();

            settings.setId(ApplicationSettings.ID);
            return settings;
        });
    }
}
