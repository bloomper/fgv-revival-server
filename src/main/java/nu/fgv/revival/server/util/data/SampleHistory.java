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
package nu.fgv.revival.server.util.data;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.audit.AuditSource;
import nu.fgv.revival.server.budget.BudgetLine;
import nu.fgv.revival.server.budget.BudgetTransaction;
import nu.fgv.revival.server.checklist.ChecklistItem;
import nu.fgv.revival.server.decisionlog.LogEntry;
import nu.fgv.revival.server.event.Event;
import nu.fgv.revival.server.material.Material;
import nu.fgv.revival.server.news.News;
import nu.fgv.revival.server.util.security.SecurityUtil;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Gives the sample records whose history the web shows a history to read. The import writes each of them in one go,
 * so Envers records a single creation by the system at import time. This moves that creation into the revival's
 * own time, made by one of its directeurs or steering group members (or by the system, for what the guide sync
 * makes), and adds earlier edits to about a third of them: the row as it was before a status change, a cancellation,
 * a corrected amount and the like, written into the audit rows that precede the edit. Rows that already have more
 * than one revision, and anonymized revivals, are left as they are.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SampleHistory {

    private static final int PERCENTAGE_OF_ROWS_WITH_EDITS = 35;
    private static final String SYSTEM_ROW = "TRUE";
    private static final String NO_SYSTEM_ROW = "FALSE";
    private static final List<String> REVISION_COLUMNS = List.of("rev", "revend", "revtype", "revend_tstmp");

    private static final List<Table> TABLES = List.of(
            new Table(LogEntry.class, "log_entry", true, "origin = 'GUIDE'", "logEntryCreate", List.of(
                    new Edit("status <> 'OPEN'", "status = 'OPEN'", null, "logEntryUpdate"),
                    new Edit("deadline IS NOT NULL", "deadline = DATE_SUB(deadline, INTERVAL 7 DAY)", null, "logEntryUpdate"),
                    new Edit("comment IS NOT NULL", "comment = NULL", null, "logEntryUpdate"))),
            new Table(ChecklistItem.class, "checklist_item", true, SYSTEM_ROW, "guideSync", List.of(
                    new Edit("state <> 'OPEN'",
                            "state = 'OPEN', reason = NULL, reason_key = NULL, reason_arg = NULL, state_changed_by = NULL, state_changed_at = NULL",
                            "state_changed_by = :person, state_changed_at = :at", "checklistItemSetState"),
                    new Edit("owner_person_id IS NOT NULL", "owner_person_id = NULL", null, "checklistItemUpdate"))),
            new Table(Event.class, "event", true, NO_SYSTEM_ROW, "eventCreate", List.of(
                    new Edit("cancelled_at IS NOT NULL", "cancelled_at = NULL", "cancelled_at = :at", "eventCancel"),
                    new Edit("location IS NOT NULL", "location = NULL", null, "eventUpdate"),
                    new Edit("start_time IS NOT NULL", "start_time = NULL, end_time = NULL", null, "eventUpdate"))),
            new Table(BudgetLine.class, "budget_line", true, NO_SYSTEM_ROW, "budgetLineCreate", List.of(
                    new Edit("removed_at IS NOT NULL", "removed_at = NULL, removed_by = NULL", "removed_at = :at, removed_by = :person", "budgetLineRemove"),
                    new Edit("unit_amount >= 100", "unit_amount = ROUND(unit_amount * 0.8, -1)", null, "budgetLineUpdate"),
                    new Edit("comment IS NOT NULL", "comment = NULL", null, "budgetLineUpdate"))),
            new Table(BudgetTransaction.class, "budget_transaction", true, NO_SYSTEM_ROW, "transactionCreate", List.of(
                    new Edit("cancelled_at IS NOT NULL", "cancelled_at = NULL, cancelled_by = NULL", "cancelled_at = :at, cancelled_by = :person", "transactionCancel"),
                    new Edit("reimbursed_at IS NOT NULL", "reimbursed_at = NULL, reimbursed_by = NULL", "reimbursed_at = :at, reimbursed_by = :person",
                            "transactionReimburse"),
                    new Edit("ABS(amount) >= 100", "amount = ROUND(amount * 1.1, 0)", null, "transactionUpdate"))),
            new Table(Material.class, "material", true, NO_SYSTEM_ROW, "materialCreate", List.of(
                    new Edit("removed_at IS NOT NULL", "removed_at = NULL, removed_by = NULL", "removed_at = :at, removed_by = :person", "materialRemove"),
                    new Edit("comment IS NOT NULL", "comment = NULL", null, "materialUpdate"))),
            new Table(News.class, "news", false, NO_SYSTEM_ROW, "newsCreate", List.of(
                    new Edit("visible_from IS NOT NULL", "subject = LEFT(CONCAT('Utkast: ', subject), 255), visible_from = NULL, visible_to = NULL", null,
                            "newsUpdate"))));

    private final JdbcClient jdbcClient;
    private final Random rnd = new SecureRandom();

    public SampleHistory(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static Timestamp timestamp(final long millis) {
        return Timestamp.from(Instant.ofEpochMilli(millis));
    }

    /**
     * Run once the sample activity is in place, in its transaction.
     */
    public void seed() {
        final Map<Long, Window> revivals = revivalWindows();
        final List<Step> steps = new ArrayList<>();

        for (final Table table : TABLES) {
            steps.addAll(plan(table, revivals));
        }
        steps.sort(Comparator.comparingLong(Step::at));

        final Map<String, String> columns = new HashMap<>();

        for (final Step step : steps) {
            final String columnList = columns.computeIfAbsent(step.table().auditTable(), this::columnList);

            apply(step, columnList);
        }
        log.info("Seeded the sample history: {} revisions", steps.size());
    }

    /**
     * The revivals that are not anonymized: from their creation up to their archiving, or today, with the people who
     * may have changed their records.
     */
    private Map<Long, Window> revivalWindows() {
        final long now = Instant.now().toEpochMilli();
        final Map<Long, List<Editor>> editors = jdbcClient
                .sql("""
                        SELECT m.revival_id, p.id, p.external_id FROM revival_member m JOIN person p ON p.id = m.person_id
                        WHERE m.role IN ('DIRECTEUR', 'STEERING_GROUP') AND m.removed_at IS NULL
                        ORDER BY m.revival_id, m.id
                        """)
                .query((rs, _) -> Map.entry(rs.getLong(1), new Editor(rs.getLong(2), rs.getString(3))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        final Map<Long, Window> windows = new HashMap<>();

        jdbcClient
                .sql("""
                        SELECT r.id, r.created_at,
                               (SELECT MIN(c.changed_at) FROM revival_status_change c WHERE c.revival_id = r.id AND c.to_status = 'ARCHIVED') AS archived_at
                        FROM revival r WHERE r.anonymized_at IS NULL
                        """)
                .query((rs, _) -> {
                    final long id = rs.getLong(1);
                    final long start = rs.getTimestamp(2).getTime();
                    final Timestamp archived = rs.getTimestamp(3);
                    final long end = Math.max(start + 1, archived == null ? now : Math.min(now, archived.getTime()));

                    return new Window(id, start, end, editors.getOrDefault(id, List.of()));
                })
                .list()
                .stream()
                .filter(window -> !window.editors().isEmpty())
                .forEach(window -> windows.put(window.key(), window));
        return windows;
    }

    private List<Step> plan(final Table table, final Map<Long, Window> revivals) {
        final List<Row> rows = jdbcClient
                .sql("""
                        SELECT a.id, %s, %s FROM %s a
                        WHERE a.revtype = 0 AND NOT EXISTS (SELECT 1 FROM %s b WHERE b.id = a.id AND b.rev <> a.rev)
                        """.formatted(table.revivalScoped() ? "a.revival_id" : "NULL", table.systemRow(), table.auditTable(), table.auditTable()))
                .query((rs, _) -> new Row(rs.getLong(1), rs.getObject(2, Long.class), rs.getBoolean(3)))
                .list();
        final List<Step> steps = new ArrayList<>();
        final Map<Long, Window> news = table.revivalScoped() ? Map.of() : newsWindows();

        for (final Row row : rows) {
            final Window window = row.revivalId() == null ? news.get(row.id()) : revivals.get(row.revivalId());

            if (window == null) {
                continue;
            }

            // News is written when it says it was; what the guide sync makes comes with the revival
            final long created = table.revivalScoped()
                    ? window.start() + (row.system() ? rnd.nextInt(60_000) : (long) (rnd.nextDouble() * (window.end() - window.start()) * 0.6))
                    : window.start();

            steps.add(new Step(table, row.id(), created, row.system() ? null : pick(window), null));

            final boolean edited = !table.revivalScoped() || rnd.nextInt(100) < PERCENTAGE_OF_ROWS_WITH_EDITS;

            if (edited) {
                for (final Edit edit : table.edits()) {
                    if (matches(table, row.id(), edit)) {
                        steps.add(new Step(table, row.id(), created + 1 + (long) (rnd.nextDouble() * (window.end() - created - 1)), pick(window), edit));
                    }
                }
            }
        }
        return steps;
    }

    private Map<Long, Window> newsWindows() {
        final long now = Instant.now().toEpochMilli();

        return jdbcClient
                .sql("SELECT n.id, n.created_at, p.id, p.external_id FROM news n LEFT JOIN person p ON p.external_id = n.created_by")
                .query((rs, _) -> {
                    final long start = rs.getTimestamp(2).getTime();
                    final Long personId = rs.getObject(3, Long.class);

                    return new Window(rs.getLong(1), start, Math.max(start + 1, now),
                            List.of(new Editor(personId == null ? 0L : personId, rs.getString(4) == null ? SecurityUtil.SYSTEM : rs.getString(4))));
                })
                .list()
                .stream()
                .collect(Collectors.toMap(Window::key, window -> window));
    }

    private Editor pick(final Window window) {
        return window.editors().get(rnd.nextInt(window.editors().size()));
    }

    private boolean matches(final Table table, final long id, final Edit edit) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM %s WHERE id = ? AND (%s)".formatted(table.auditTable(), edit.where()))
                .param(id)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * The audited columns other than Envers' own, in table order.
     */
    private String columnList(final String auditTable) {
        return String.join(", ", jdbcClient
                .sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position
                        """)
                .param(auditTable)
                .query(String.class)
                .list()
                .stream()
                .filter(column -> !REVISION_COLUMNS.contains(column))
                .map(column -> "`" + column + "`")
                .toList());
    }

    private void apply(final Step step, final String columnList) {
        final Edit edit = step.edit();
        final Table table = step.table();
        final String by = step.editor() == null ? SecurityUtil.SYSTEM : step.editor().externalId();
        final AuditSource source = step.editor() == null ? AuditSource.SYSTEM : AuditSource.WEB;
        final long rev = insertRevision(step.at(), by, source, edit == null ? table.createOperation() : edit.operation());
        final Timestamp at = timestamp(step.at());

        if (edit == null) {
            jdbcClient.sql("UPDATE %s SET rev = ? WHERE id = ? AND revtype = 0".formatted(table.auditTable())).param(rev).param(step.id()).update();
            jdbcClient
                    .sql("UPDATE %s SET created_by = :by, created_at = :at, last_modified_by = :by, last_modified_at = :at WHERE id = :id"
                            .formatted(table.table()))
                    .param("by", by)
                    .param("at", at.toInstant().atOffset(ZoneOffset.UTC))
                    .param("id", step.id())
                    .update();
        } else {
            jdbcClient
                    .sql("INSERT INTO %s (%s, rev, revtype) SELECT %s, ?, 1 FROM %s WHERE id = ? AND revend IS NULL"
                            .formatted(table.auditTable(), columnList, columnList, table.auditTable()))
                    .param(rev)
                    .param(step.id())
                    .update();
            jdbcClient
                    .sql("UPDATE %s SET revend = ?, revend_tstmp = ? WHERE id = ? AND revend IS NULL AND rev <> ?".formatted(table.auditTable()))
                    .param(rev)
                    .param(at)
                    .param(step.id())
                    .param(rev)
                    .update();
            // The rows before the edit held the earlier values
            jdbcClient.sql("UPDATE %s SET %s WHERE id = ? AND rev <> ?".formatted(table.auditTable(), edit.before()))
                    .param(step.id())
                    .param(rev)
                    .update();
            // The edit happened when its revision says, by whoever made it
            if (edit.after() != null) {
                for (final String sql : List.of("UPDATE %s SET %s WHERE id = :id AND rev = :rev".formatted(table.auditTable(), edit.after()),
                        "UPDATE %s SET %s WHERE id = :id".formatted(table.table(), edit.after()))) {
                    jdbcClient
                            .sql(sql)
                            .param("id", step.id())
                            .param("rev", rev)
                            .param("at", at.toInstant().atOffset(ZoneOffset.UTC))
                            .param("person", Objects.requireNonNull(step.editor()).personId())
                            .update();
                }
            }
            jdbcClient
                    .sql("UPDATE %s SET last_modified_by = :by, last_modified_at = :at WHERE id = :id".formatted(table.table()))
                    .param("by", by)
                    .param("at", at.toInstant().atOffset(ZoneOffset.UTC))
                    .param("id", step.id())
                    .update();
        }
        jdbcClient.sql("INSERT INTO revchanges (rev, entityname) VALUES (?, ?)").param(rev).param(table.entity().getName()).update();
    }

    private long insertRevision(final long at, final String by, final AuditSource source, final String operation) {
        final KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcClient
                .sql("INSERT INTO revinfo (modified_at, modified_by, source, operation) VALUES (?, ?, ?, ?)")
                .param(at)
                .param(by)
                .param(source.name())
                .param(operation)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * @param systemRow a condition on the audit row: true when the system, not a person, made it
     */
    private record Table(Class<?> entity, String table, boolean revivalScoped, String systemRow, String createOperation, List<Edit> edits) {

        String auditTable() {
            return table + "_audit";
        }
    }

    /**
     * An edit made after the row was created.
     *
     * @param where  which rows can have had it, on their current values
     * @param before what the rows before the edit held, as SQL assignments
     * @param after  assignments that date the edit, with {@code :at} and {@code :person}
     */
    private record Edit(String where, String before, @Nullable String after, String operation) {
    }

    private record Editor(long personId, String externalId) {
    }

    /**
     * @param key the revival, or for news the piece of news
     */
    private record Window(long key, long start, long end, List<Editor> editors) {
    }

    private record Row(long id, @Nullable Long revivalId, boolean system) {
    }

    private record Step(Table table, long id, long at, @Nullable Editor editor, @Nullable Edit edit) {
    }
}
