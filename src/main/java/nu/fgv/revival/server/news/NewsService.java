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
package nu.fgv.revival.server.news;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * News: the super admin writes it, everyone with access reads what is published. Whether a piece is published follows
 * from its dates and today's date, so nothing has to publish or withdraw it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class NewsService {

    /**
     * Newest first: by the day it became visible, drafts (no such day) before everything else, then the latest written.
     */
    static final Comparator<News> NEWEST_FIRST = Comparator
            .comparing(News::getVisibleFrom, Comparator.nullsFirst(Comparator.<LocalDate>reverseOrder()))
            .thenComparing(News::getId, Comparator.reverseOrder());

    private final NewsRepository repository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public NewsService(final NewsRepository repository, final CallerService callerService, final AccessPolicy accessPolicy, final Clock clock) {
        this.repository = repository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    private static String validSubject(final @Nullable String subject) {
        final String trimmed = subject == null ? "" : subject.strip();

        if (trimmed.isEmpty() || trimmed.length() > News.SUBJECT_MAX_LENGTH) {
            throw new BadRequestException("error.news.subject", News.SUBJECT_MAX_LENGTH);
        }
        return trimmed;
    }

    private static String validText(final @Nullable String text) {
        final String trimmed = text == null ? "" : text.strip();

        if (trimmed.isEmpty() || trimmed.length() > News.TEXT_MAX_LENGTH) {
            throw new BadRequestException("error.news.text", News.TEXT_MAX_LENGTH);
        }
        return trimmed;
    }

    private static void validDates(final @Nullable LocalDate visibleFrom, final @Nullable LocalDate visibleTo) {
        if (visibleTo != null && (visibleFrom == null || visibleTo.isBefore(visibleFrom))) {
            throw new BadRequestException("error.news.dates");
        }
    }

    /**
     * What is published today, newest first; with {@code all}, for the super admin, drafts and withdrawn news as well.
     */
    @Transactional(readOnly = true)
    public List<NewsDto> list(final boolean all, final @Nullable Integer limit) {
        if (all) {
            manageable();
        } else {
            callerService.current();
        }

        final LocalDate today = LocalDate.now(clock);

        return repository.findAll().stream()
                .filter(news -> all || news.isPublishedOn(today))
                .sorted(NEWEST_FIRST)
                .limit(limit == null || limit < 0 ? Long.MAX_VALUE : limit)
                .map(news -> NewsDto.of(news, today))
                .toList();
    }

    @Transactional
    public NewsDto create(final String subject, final String text, final @Nullable LocalDate visibleFrom, final @Nullable LocalDate visibleTo) {
        manageable();
        validDates(visibleFrom, visibleTo);
        AuditContext.operation("newsCreate");

        final News created = new News();

        created.setSubject(validSubject(subject));
        created.setText(validText(text));
        created.setVisibleFrom(visibleFrom);
        created.setVisibleTo(visibleTo);
        return NewsDto.of(repository.saveAndFlush(created), LocalDate.now(clock));
    }

    @Transactional
    public NewsDto update(final Long id, final Long version, final String subject, final String text,
                          final @Nullable LocalDate visibleFrom, final @Nullable LocalDate visibleTo) {
        manageable();
        validDates(visibleFrom, visibleTo);

        final News existing = changeable(id, version);

        AuditContext.operation("newsUpdate");
        existing.setSubject(validSubject(subject));
        existing.setText(validText(text));
        existing.setVisibleFrom(visibleFrom);
        existing.setVisibleTo(visibleTo);
        return NewsDto.of(repository.saveAndFlush(existing), LocalDate.now(clock));
    }

    @Transactional
    public void delete(final Long id, final Long version) {
        manageable();

        final News existing = changeable(id, version);

        AuditContext.operation("newsDelete");
        repository.delete(existing);
        repository.flush();
    }

    private void manageable() {
        accessPolicy.requireManageNews(callerService.current());
    }

    private News changeable(final Long id, final Long version) {
        final News news = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(News.class, id));

        if (!Objects.equals(version, news.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return news;
    }
}
