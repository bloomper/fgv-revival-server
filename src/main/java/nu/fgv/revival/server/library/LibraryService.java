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
package nu.fgv.revival.server.library;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.guide.GuideAccess;
import nu.fgv.revival.server.guide.GuideItemRepository;
import nu.fgv.revival.server.material.Links;
import nu.fgv.revival.server.member.AccessPolicy;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.person.CallerService;
import nu.fgv.revival.server.util.error.BadRequestException;
import nu.fgv.revival.server.util.error.ConflictException;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The reference library: whoever reads the guide reads it, the super admin creates, changes, retires and restores its
 * entries. Never deleted: guide items keep a retired entry.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class LibraryService {

    private static final Comparator<LibraryEntry> ORDER = Comparator
            .comparing(LibraryEntry::getKind)
            .thenComparing(LibraryEntry::getNameSv, Collator.getInstance(Locale.of("sv")))
            .thenComparing(LibraryEntry::getId);

    /**
     * As the list: by kind, then Swedish name.
     */
    static final Comparator<LibraryEntryDto> DTO_ORDER = Comparator
            .comparing(LibraryEntryDto::kind)
            .thenComparing((LibraryEntryDto entry) -> entry.name().sv(), Collator.getInstance(Locale.of("sv")))
            .thenComparing(LibraryEntryDto::id);

    private final LibraryEntryRepository repository;
    private final GuideItemRepository itemRepository;
    private final CallerService callerService;
    private final AccessPolicy accessPolicy;
    private final GuideAccess guideAccess;
    private final Clock clock;

    public LibraryService(final LibraryEntryRepository repository,
                          final GuideItemRepository itemRepository,
                          final CallerService callerService,
                          final AccessPolicy accessPolicy,
                          final GuideAccess guideAccess,
                          final Clock clock) {
        this.repository = repository;
        this.itemRepository = itemRepository;
        this.callerService = callerService;
        this.accessPolicy = accessPolicy;
        this.guideAccess = guideAccess;
        this.clock = clock;
    }

    private static String validName(final @Nullable String name) {
        final String trimmed = name == null ? "" : name.strip();

        if (trimmed.isEmpty() || trimmed.length() > LibraryEntry.NAME_MAX_LENGTH) {
            throw new BadRequestException("error.library.name", LibraryEntry.NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * Swedish and English, trimmed; both null when there is no Swedish text.
     */
    private static Text validText(final @Nullable Text text) {
        if (text == null || text.sv() == null || text.sv().isBlank()) {
            return new Text(null, null);
        }

        final String sv = text.sv().strip();
        final String en = text.en() == null || text.en().isBlank() ? null : text.en().strip();

        if (sv.length() > LibraryEntry.TEXT_MAX_LENGTH || en != null && en.length() > LibraryEntry.TEXT_MAX_LENGTH) {
            throw new BadRequestException("error.library.text", LibraryEntry.TEXT_MAX_LENGTH);
        }
        return new Text(sv, en);
    }

    @Transactional(readOnly = true)
    public List<LibraryEntryDto> list(final boolean includeRetired) {
        readable();
        return repository.findAll().stream()
                .filter(entry -> includeRetired || !entry.isRetired())
                .sorted(ORDER)
                .map(LibraryEntryDto::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public LibraryEntryDto get(final Long id) {
        readable();
        return LibraryEntryDto.of(repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(LibraryEntry.class, id)));
    }

    /**
     * Whether the caller reads the library, so guide items show their links only to them.
     */
    @Transactional(readOnly = true)
    public boolean canRead() {
        final Caller caller = callerService.current();

        return accessPolicy.canReadLibrary(caller, guideAccess.callerRevivals(caller));
    }

    /**
     * Entries by id, retired ones too, for what guide items link to. No caller check: callers have checked.
     */
    @Transactional(readOnly = true)
    public Map<Long, LibraryEntryDto> byIds(final Collection<Long> ids) {
        return ids.isEmpty() ? Map.of()
                : repository.findAllById(Set.copyOf(ids)).stream().map(LibraryEntryDto::of)
                .collect(Collectors.toMap(LibraryEntryDto::id, Function.identity()));
    }

    /**
     * The library entries each guide item links to, by item id. No caller check: callers have checked.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> linksOf(final Collection<Long> itemIds) {
        return itemIds.isEmpty() ? Map.of() : itemRepository.findLibraryLinks(Set.copyOf(itemIds)).stream()
                .collect(Collectors.groupingBy(pair -> (Long) pair[0], Collectors.mapping(pair -> (Long) pair[1], Collectors.toList())));
    }

    /**
     * The entries a guide item may link to: existing ones, not retired unless the item already links to them.
     */
    @Transactional(readOnly = true)
    public Set<Long> validLinks(final Collection<Long> ids, final Collection<Long> held) {
        final Set<Long> wanted = new LinkedHashSet<>(ids);
        final Map<Long, LibraryEntry> found = repository.findAllById(wanted).stream()
                .collect(Collectors.toMap(LibraryEntry::getId, Function.identity()));

        for (final Long id : wanted) {
            final LibraryEntry entry = found.get(id);

            if (entry == null) {
                throw new ResourceNotFoundException(LibraryEntry.class, id);
            }
            if (entry.isRetired() && !held.contains(id)) {
                throw new BadRequestException("error.library.retired", entry.getNameSv());
            }
        }
        return wanted;
    }

    @Transactional
    public LibraryEntryDto create(final Definition definition) {
        manageable();

        final LibraryEntry entry = new LibraryEntry();

        AuditContext.operation("libraryEntryCreate");
        entry.setKind(definition.kind());
        define(entry, definition);
        return LibraryEntryDto.of(repository.saveAndFlush(entry));
    }

    /**
     * Changes everything but the kind, which is fixed on create.
     */
    @Transactional
    public LibraryEntryDto update(final Long id, final Long version, final Definition definition) {
        manageable();

        final LibraryEntry entry = changeable(id, version);

        AuditContext.operation("libraryEntryUpdate");
        define(entry, definition);
        return LibraryEntryDto.of(repository.saveAndFlush(entry));
    }

    @Transactional
    public LibraryEntryDto retire(final Long id, final Long version) {
        manageable();

        final LibraryEntry entry = changeable(id, version);

        AuditContext.operation("libraryEntryRetire");
        entry.setRetiredAt(entry.isRetired() ? entry.getRetiredAt() : clock.instant());
        return LibraryEntryDto.of(repository.saveAndFlush(entry));
    }

    @Transactional
    public LibraryEntryDto restore(final Long id, final Long version) {
        manageable();

        final LibraryEntry entry = changeable(id, version);

        AuditContext.operation("libraryEntryRestore");
        entry.setRetiredAt(null);
        return LibraryEntryDto.of(repository.saveAndFlush(entry));
    }

    private void readable() {
        final Caller caller = callerService.current();

        accessPolicy.requireReadLibrary(caller, guideAccess.callerRevivals(caller));
    }

    private void manageable() {
        accessPolicy.requireManageLibrary(callerService.current());
    }

    private LibraryEntry changeable(final Long id, final Long version) {
        final LibraryEntry entry = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(LibraryEntry.class, id));

        if (!Objects.equals(version, entry.getVersion())) {
            throw new ConflictException("error.conflict");
        }
        return entry;
    }

    private void define(final LibraryEntry entry, final Definition definition) {
        final Text description = validText(definition.description());
        final Text pros = validText(definition.pros());
        final Text cons = validText(definition.cons());
        final Text booking = validText(definition.booking());
        final Text prices = validText(definition.prices());
        final Text contact = validText(definition.contact());

        entry.setNameSv(validName(definition.name().sv()));
        entry.setNameEn(definition.name().en() == null || definition.name().en().isBlank() ? null : validName(definition.name().en()));
        entry.setDescriptionSv(description.sv());
        entry.setDescriptionEn(description.en());
        entry.setProsSv(pros.sv());
        entry.setProsEn(pros.en());
        entry.setConsSv(cons.sv());
        entry.setConsEn(cons.en());
        entry.setBookingSv(booking.sv());
        entry.setBookingEn(booking.en());
        entry.setPricesSv(prices.sv());
        entry.setPricesEn(prices.en());
        entry.setContactSv(contact.sv());
        entry.setContactEn(contact.en());
        entry.setUrl(definition.url() == null || definition.url().isBlank() ? null : Links.requireHttps(definition.url(), LibraryEntry.URL_MAX_LENGTH));
    }

    /**
     * Swedish text and, optionally, English.
     */
    public record Text(@Nullable String sv, @Nullable String en) {
    }

    /**
     * Everything the super admin sets on an entry. A null or blank Swedish text clears that text. The kind is read only
     * on create.
     */
    public record Definition(LibraryEntryKind kind, Text name, @Nullable Text description, @Nullable Text pros, @Nullable Text cons,
                             @Nullable Text booking, @Nullable Text prices, @Nullable Text contact, @Nullable String url) {
    }
}
