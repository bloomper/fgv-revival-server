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


package nu.fgv.revival.server.decisionlog;

import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.springframework.util.StringUtils.hasText;

/**
 * The log's filters as query predicates, combined with AND (FR-012, research R6). Text matching relies on the
 * columns' {@code utf8mb4_0900_ai_ci} collation, which ignores case and diacritics.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
final class LogEntrySpecifications {

    private static final Set<LogEntryStatus> OPEN = Set.of(LogEntryStatus.OPEN, LogEntryStatus.WAITING);

    private LogEntrySpecifications() {
    }

    static Specification<LogEntry> of(final Long revivalId, final LogEntryFilterDto filter, final Long callerPersonId, final LocalDate today) {
        final List<Specification<LogEntry>> parts = new ArrayList<>();

        parts.add(revival(revivalId));
        if (Boolean.TRUE.equals(filter.mine())) {
            parts.add(ownedBy(callerPersonId));
        }
        if (Boolean.TRUE.equals(filter.open())) {
            parts.add(open());
        }
        if (Boolean.TRUE.equals(filter.overdue())) {
            parts.add(overdue(today));
        }
        if (filter.types() != null && !filter.types().isEmpty()) {
            parts.add(types(filter.types()));
        }
        if (filter.meetingId() != null) {
            parts.add(meeting(filter.meetingId()));
        }
        if (filter.tags() != null && !filter.tags().isEmpty()) {
            parts.add(tags(filter.tags()));
        }
        if (hasText(filter.text())) {
            parts.add(text(filter.text().strip()));
        }
        return Specification.allOf(parts);
    }

    static Specification<LogEntry> revival(final Long revivalId) {
        return (root, query, cb) -> cb.equal(root.get("revivalId"), revivalId);
    }

    static Specification<LogEntry> ownedBy(final Long personId) {
        return (root, query, cb) -> cb.equal(root.get("owner").get("personId"), personId);
    }

    static Specification<LogEntry> open() {
        return (root, query, cb) -> root.get("status").in(OPEN);
    }

    /**
     * Open or waiting, with a deadline before today (FR-011).
     */
    static Specification<LogEntry> overdue(final LocalDate today) {
        return (root, query, cb) -> cb.and(root.get("status").in(OPEN), cb.lessThan(root.get("deadline"), today));
    }

    static Specification<LogEntry> types(final Collection<LogEntryType> types) {
        return (root, query, cb) -> root.get("type").in(types);
    }

    static Specification<LogEntry> meeting(final Long meetingId) {
        return (root, query, cb) -> cb.equal(root.get("meetingId"), meetingId);
    }

    static Specification<LogEntry> tags(final Collection<String> tags) {
        return (root, query, cb) -> {
            final var subquery = query.subquery(Long.class);
            final var entry = subquery.from(LogEntry.class);
            final Join<LogEntry, String> tag = entry.join("tags");

            subquery.select(entry.get("id")).where(cb.equal(entry.get("id"), root.get("id")), tag.in(tags));
            return cb.exists(subquery);
        };
    }

    static Specification<LogEntry> text(final String text) {
        return (root, query, cb) -> {
            final String pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

            return cb.or(
                    cb.like(root.get("text"), pattern, '\\'),
                    cb.like(root.get("comment"), pattern, '\\'),
                    cb.like(root.get("owner").get("name"), pattern, '\\'));
        };
    }
}
