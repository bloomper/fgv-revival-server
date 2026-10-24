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


package nu.fgv.revival.server.calendar;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.PersonRepository;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.revival.RevivalTitles;
import nu.fgv.revival.server.util.error.ConflictException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Secret calendar links (M4-FR-5, M4-FR-6). A link holds 32 random bytes; only their SHA-256 is stored, so the link is
 * shown once. The feed is built for the link's person from their revival roles as they are now: a link stops working
 * when they lose their role or can no longer see the revival.
 *
 * @author Anders Jacobsson
 * @since 0.5
 */
@Service
public class CalendarFeedService {

    static final int TOKEN_BYTES = 32;
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final Locale SWEDISH = Locale.of("sv");

    private final CalendarFeedRepository repository;
    private final RevivalRepository revivalRepository;
    private final PersonRepository personRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final RevivalTitles titles;
    private final List<CalendarSource> sources;
    private final Clock clock;
    private final String webUrl;
    private final SecureRandom random = new SecureRandom();

    public CalendarFeedService(final CalendarFeedRepository repository,
                               final RevivalRepository revivalRepository,
                               final PersonRepository personRepository,
                               final CallerService callerService,
                               final AccessPolicy accessPolicy,
                               final RevivalTitles titles,
                               final List<CalendarSource> sources,
                               final Clock clock,
                               @Value("${fgv-revival.web-url}") final String webUrl) {
        this.repository = repository;
        this.revivalRepository = revivalRepository;
        this.personRepository = personRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.titles = titles;
        this.sources = List.copyOf(sources);
        this.clock = clock;
        this.webUrl = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    }

    /**
     * The caller's active link for the revival, without its token; null when they have none or may not have one.
     */
    @Transactional(readOnly = true)
    public @Nullable CalendarFeedDto current(final Long revivalId) {
        final Caller caller = callerService.current();
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        if (!accessPolicy.canSubscribeCalendar(caller, revival)) {
            return null;
        }
        return repository.findByRevivalIdAndPersonIdAndRevokedAtIsNull(revivalId, caller.person().getId())
                .map(feed -> new CalendarFeedDto(feed.getCreatedAt()))
                .orElse(null);
    }

    /**
     * A new link for the caller; their previous one for the revival stops working.
     */
    @Transactional
    public CalendarFeedLinkDto create(final Long revivalId) {
        final Caller caller = callerService.current();
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireSubscribeCalendar(caller, revival);
        AuditContext.operation("calendarFeedCreate");
        revokeActive(revivalId, caller.person().getId());

        final byte[] bytes = new byte[TOKEN_BYTES];

        random.nextBytes(bytes);

        final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        final CalendarFeed feed = new CalendarFeed();

        feed.setRevivalId(revivalId);
        feed.setPersonId(caller.person().getId());
        feed.setTokenHash(hash(token));
        feed.setCreatedAt(clock.instant());
        try {
            repository.saveAndFlush(feed);
        } catch (final DataIntegrityViolationException e) {
            // Two links created at once for the same person and revival
            throw new ConflictException("error.conflict");
        }
        return new CalendarFeedLinkDto("%s/api/calendar/%s.ics".formatted(webUrl, token), feed.getCreatedAt());
    }

    /**
     * Turns the caller's link for the revival off. Allowed whenever they can see the revival, also after losing the
     * right to a link.
     */
    @Transactional
    public void revoke(final Long revivalId) {
        final Caller caller = callerService.current();

        accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);
        AuditContext.operation("calendarFeedRevoke");
        revokeActive(revivalId, caller.person().getId());
    }

    /**
     * The feed behind a token, in Swedish or English. Empty for a malformed, unknown or revoked token, and when the
     * link's person no longer holds a role in a revival they can see.
     */
    @Transactional(readOnly = true)
    public Optional<String> render(final String token, final Locale locale) {
        if (!TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return repository.findByTokenHashAndRevokedAtIsNull(hash(token)).flatMap(feed -> {
            final Revival revival = revivalRepository.findById(feed.getRevivalId()).orElse(null);
            final Caller person = personRepository.findById(feed.getPersonId()).map(callerService::forPerson).orElse(null);

            if (revival == null || person == null || !accessPolicy.canSubscribeCalendar(person, revival)) {
                return Optional.empty();
            }

            final Locale language = "en".equals(locale.getLanguage()) ? Locale.ENGLISH : SWEDISH;
            final List<CalendarEntry> entries = new ArrayList<>();

            sources.forEach(source -> entries.addAll(source.entries(revival, person, language)));
            entries.sort(Comparator.comparing(this::start).thenComparing(entry -> entry.uid()));
            return Optional.of(IcsWriter.write(titles.ofCached(revival, language), clock.getZone().getId(), entries));
        });
    }

    private Instant start(final CalendarEntry entry) {
        return switch (entry.when()) {
            case CalendarEntry.AllDay allDay -> allDay.first().atStartOfDay(clock.getZone()).toInstant();
            case CalendarEntry.Timed timed -> timed.start();
        };
    }

    private void revokeActive(final Long revivalId, final Long personId) {
        repository.findByRevivalIdAndPersonIdAndRevokedAtIsNull(revivalId, personId).ifPresent(previous -> {
            previous.setRevokedAt(clock.instant());
            repository.saveAndFlush(previous);
        });
    }

    static String hash(final String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
