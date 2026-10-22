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

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.decisionlog.LogWorkbookParser.ParsedLog;
import nu.fgv.revival.server.decisionlog.LogWorkbookParser.ParsedRow;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.member.Owner;
import nu.fgv.revival.server.member.OwnerResolver;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalRepository;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one-time import of an Excel log into a revival's empty log (FR-036–FR-042, research R12). Parses the workbook
 * on every call, so nothing is stored between the preview and the import.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Service
public class LogImportService {

    private static final Set<LogEntryOrigin> ENTERED = EnumSet.of(LogEntryOrigin.MANUAL, LogEntryOrigin.IMPORT);

    private final LogEntryRepository entryRepository;
    private final LogImportRepository importRepository;
    private final LogEntryCounters counters;
    private final RevivalRepository revivalRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final OwnerResolver ownerResolver;
    private final PersonNames personNames;
    private final Clock clock;
    private final LogWorkbookParser parser = new LogWorkbookParser();

    public LogImportService(final LogEntryRepository entryRepository,
                            final LogImportRepository importRepository,
                            final LogEntryCounters counters,
                            final RevivalRepository revivalRepository,
                            final CallerService callerService,
                            final AccessPolicy accessPolicy,
                            final OwnerResolver ownerResolver,
                            final PersonNames personNames,
                            final Clock clock) {
        this.entryRepository = entryRepository;
        this.importRepository = importRepository;
        this.counters = counters;
        this.revivalRepository = revivalRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.ownerResolver = ownerResolver;
        this.personNames = personNames;
        this.clock = clock;
    }

    /**
     * The directeur's choices: each owner name from the preview mapped to a member's person id, or to null for a
     * free-text owner, and the type of Nobel rows.
     */
    public record Choices(Map<String, @Nullable Long> owners, @Nullable LogEntryType nobelType) {

        public static Choices none() {
            return new Choices(Map.of(), null);
        }
    }

    /**
     * Whether the caller may import into the revival now: a directeur, and the log has no row entered by a person.
     */
    @Transactional(readOnly = true)
    public boolean canImport(final Long revivalId) {
        final Caller caller = callerService.current();

        return revivalRepository.findById(revivalId)
                .filter(revival -> accessPolicy.canImportLog(caller, revival))
                .map(revival -> isEmpty(revivalId))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public LogImportPreviewDto preview(final Long revivalId, final String fileName, final byte[] workbook) {
        final Caller caller = callerService.current();

        requireImportable(caller, revivalId);

        final ParsedLog log = parser.parse(workbook);
        final Map<LogEntryType, Integer> byType = new EnumMap<>(LogEntryType.class);
        final Map<LogEntryStatus, Integer> byStatus = new EnumMap<>(LogEntryStatus.class);
        final Map<Long, @Nullable String> members = ownerResolver.activeMembers(revivalId);
        final List<LogImportPreviewDto.OwnerName> owners = new ArrayList<>();

        for (final LogEntryType type : LogEntryType.values()) {
            byType.put(type, 0);
        }
        for (final LogEntryStatus status : LogEntryStatus.values()) {
            byStatus.put(status, 0);
        }
        log.rows().forEach(row -> {
            byType.merge(row.type() == null ? LogEntryType.TODO : row.type(), 1, Integer::sum);
            byStatus.merge(row.status(), 1, Integer::sum);
        });
        log.owners().forEach((name, rows) -> owners.add(new LogImportPreviewDto.OwnerName(name, rows, suggested(revivalId, name, caller))));

        return new LogImportPreviewDto(fileName, log.layout(), log.sheet(), log.ignoredSheets(), log.rows().size(), byType, byStatus,
                new LogImportPreviewDto.Numbers(log.rows().getFirst().number(), log.rows().getLast().number()), owners,
                members.entrySet().stream().map(entry -> new PersonRefDto(entry.getKey(), entry.getValue())).toList(),
                log.warnings(), entryRepository.findByRevivalIdAndOriginOrderByNumberAsc(revivalId, LogEntryOrigin.GUIDE).size());
    }

    @Transactional
    public LogImportDto importLog(final Long revivalId, final String fileName, final byte[] workbook, final Choices choices) {
        final Caller caller = callerService.current();

        requireImportable(caller, revivalId);
        AuditContext.operation("logImport");

        final ParsedLog log = parser.parse(workbook);
        final Map<String, @Nullable Long> owners = owners(revivalId, choices);
        final LogEntryType nobelType = choices.nobelType() == null ? LogEntryType.TODO : choices.nobelType();
        final LocalDate today = LocalDate.now(clock);
        final int lastImported = log.rows().stream().mapToInt(ParsedRow::number).max().orElse(0);
        final LogEntryCounter counter = counters.locked(revivalId);

        counter.setLastNumber(Math.max(lastImported, renumberGuideRows(revivalId, lastImported)));
        for (final ParsedRow row : log.rows()) {
            final LogEntry entry = new LogEntry();

            entry.setRevivalId(revivalId);
            entry.setNumber(row.number());
            entry.setDate(row.date() == null ? today : row.date());
            entry.setType(row.type() == null ? nobelType : row.type());
            entry.setStatus(row.status());
            entry.setText(row.text());
            entry.setOwner(owner(row.ownerName(), owners));
            entry.setDeadline(row.deadline());
            entry.setComment(row.comment());
            entry.setTags(row.tag() == null ? new HashSet<>() : new HashSet<>(Set.of(row.tag())));
            entry.setOrigin(LogEntryOrigin.IMPORT);
            entryRepository.save(entry);
        }

        final LogImport record = new LogImport();

        record.setRevivalId(revivalId);
        record.setFileName(fileName);
        record.setLayout(log.layout());
        record.setRowCount(log.rows().size());
        record.setWarnings(new ArrayList<>(log.warnings()));
        record.setImportedBy(caller.person().getId());
        record.setImportedAt(clock.instant());

        final LogImport saved = importRepository.saveAndFlush(record);

        return toDto(saved, caller.person().getDisplayName());
    }

    @Transactional(readOnly = true)
    public Optional<LogImportDto> find(final Long revivalId, final Locale locale) {
        final Caller caller = callerService.current();
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireReadRevivalWork(caller, revival);
        return importRepository.findByRevivalId(revivalId)
                .map(found -> toDto(found, personNames.byIds(List.of(found.getImportedBy()), locale).get(found.getImportedBy())));
    }

    private static LogImportDto toDto(final LogImport logImport, final @Nullable String importerName) {
        return new LogImportDto(logImport.getFileName(), logImport.getLayout(), logImport.getRowCount(), logImport.getWarnings(),
                new PersonRefDto(logImport.getImportedBy(), importerName), logImport.getImportedAt());
    }

    private void requireImportable(final Caller caller, final Long revivalId) {
        final Revival revival = accessPolicy.requireVisible(caller, revivalRepository.findById(revivalId).orElse(null), revivalId);

        accessPolicy.requireImportLog(caller, revival);
        if (!isEmpty(revivalId)) {
            throw new ConflictException("error.log.import.logNotEmpty");
        }
    }

    private boolean isEmpty(final Long revivalId) {
        return !entryRepository.existsByRevivalIdAndOriginIn(revivalId, ENTERED) && !importRepository.existsByRevivalId(revivalId);
    }

    private @Nullable Long suggested(final Long revivalId, final String name, final Caller caller) {
        if ("me".equalsIgnoreCase(name.strip())) {
            return null;
        }
        return ownerResolver.resolve(revivalId, name, caller) instanceof OwnerResolver.Resolution.Member member ? member.personId() : null;
    }

    private Map<String, @Nullable Long> owners(final Long revivalId, final Choices choices) {
        final Set<Long> active = ownerResolver.activeMembers(revivalId).keySet();
        final Map<String, @Nullable Long> owners = new HashMap<>();

        choices.owners().forEach((name, personId) -> {
            if (personId != null && !active.contains(personId)) {
                throw new BadRequestException("error.owner.notMember");
            }
            owners.put(key(name), personId);
        });
        return owners;
    }

    private static Owner owner(final @Nullable String name, final Map<String, @Nullable Long> owners) {
        if (name == null) {
            return Owner.none();
        }

        final @Nullable Long personId = owners.get(key(name));

        return personId == null ? Owner.named(name) : Owner.member(personId);
    }

    private static String key(final String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Moves the rows that came from the guide after the imported ones, keeping their order, so the imported rows keep
     * their numbers (FR-010). Returns the last number used.
     */
    private int renumberGuideRows(final Long revivalId, final int lastImported) {
        final List<LogEntry> guideRows = entryRepository.findByRevivalIdAndOriginOrderByNumberAsc(revivalId, LogEntryOrigin.GUIDE);

        guideRows.forEach(entry -> {
            entry.setNumber(-entry.getNumber());
            entryRepository.saveAndFlush(entry);
        });

        int number = lastImported;

        for (final LogEntry entry : guideRows) {
            entry.setNumber(++number);
            entryRepository.saveAndFlush(entry);
        }
        return number;
    }
}
