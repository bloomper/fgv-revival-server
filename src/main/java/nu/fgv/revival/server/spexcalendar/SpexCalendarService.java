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
package nu.fgv.revival.server.spexcalendar;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The current Chalmersspex's show dates: the super admin keeps them, super admins and observers read them for the
 * cross-revival timeline (product spec 5.1).
 *
 * @author Anders Jacobsson
 * @since 0.6
 */
@Service
public class SpexCalendarService {

    private final SpexCalendarDateRepository repository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;

    public SpexCalendarService(final SpexCalendarDateRepository repository, final CallerService callerService, final AccessPolicy accessPolicy) {
        this.repository = repository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
    }

    /**
     * By date, within the dates given; either may be left open.
     */
    @Transactional(readOnly = true)
    public List<SpexCalendarDateDto> list(final @Nullable LocalDate from, final @Nullable LocalDate to) {
        accessPolicy.requireReadSpexCalendar(callerService.current());
        return repository.findAllByOrderByDateAscIdAsc().stream()
                .filter(date -> from == null || !date.getDate().isBefore(from))
                .filter(date -> to == null || !date.getDate().isAfter(to))
                .map(SpexCalendarDateDto::of)
                .toList();
    }

    @Transactional
    public SpexCalendarDateDto create(final LocalDate date, final String label) {
        manageable();
        AuditContext.operation("spexCalendarDateCreate");

        final SpexCalendarDate created = new SpexCalendarDate();

        created.setDate(Objects.requireNonNull(date));
        created.setLabel(validLabel(label));
        return SpexCalendarDateDto.of(repository.saveAndFlush(created));
    }

    @Transactional
    public SpexCalendarDateDto update(final Long id, final Long version, final LocalDate date, final String label) {
        manageable();

        final SpexCalendarDate existing = changeable(id, version);

        AuditContext.operation("spexCalendarDateUpdate");
        existing.setDate(Objects.requireNonNull(date));
        existing.setLabel(validLabel(label));
        return SpexCalendarDateDto.of(repository.saveAndFlush(existing));
    }

    @Transactional
    public void delete(final Long id, final Long version) {
        manageable();

        final SpexCalendarDate existing = changeable(id, version);

        AuditContext.operation("spexCalendarDateDelete");
        repository.delete(existing);
        repository.flush();
    }

    private void manageable() {
        accessPolicy.requireManageSpexCalendar(callerService.current());
    }

    private SpexCalendarDate changeable(final Long id, final Long version) {
        final SpexCalendarDate date = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(SpexCalendarDate.class, id));

        if (!Objects.equals(version, date.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return date;
    }

    private static String validLabel(final @Nullable String label) {
        final String trimmed = label == null ? "" : label.strip();

        if (trimmed.isEmpty() || trimmed.length() > SpexCalendarDate.LABEL_MAX_LENGTH) {
            throw new BadRequestException("error.spexCalendar.label", SpexCalendarDate.LABEL_MAX_LENGTH);
        }
        return trimmed;
    }
}
