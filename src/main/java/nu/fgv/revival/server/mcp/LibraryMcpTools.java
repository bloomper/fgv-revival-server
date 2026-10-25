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
package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.guide.GuideVersionView;
import nu.fgv.revival.server.library.LibraryEntryDto;
import nu.fgv.revival.server.library.LibraryEntryKind;
import nu.fgv.revival.server.library.LibraryService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The reference library over MCP: venues, ticket services, role descriptions and roles to contact. Whoever reads the
 * guide reads it; super admins keep it. Entries are retired and restored, never deleted.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class LibraryMcpTools {

    private final LibraryService libraryService;
    private final McpToolSupport support;

    public LibraryMcpTools(final LibraryService libraryService, final McpToolSupport support) {
        this.libraryService = libraryService;
        this.support = support;
    }

    private static LibraryService.@Nullable Text text(final @Nullable String sv, final @Nullable String en, final GuideVersionView.@Nullable Text current) {
        if (sv == null && en == null) {
            return current == null ? null : new LibraryService.Text(current.sv(), current.en());
        }
        return new LibraryService.Text(sv == null && current != null ? current.sv() : sv, en == null && current != null ? current.en() : en);
    }

    private static ChecklistMcpTools.@Nullable Text toMcp(final GuideVersionView.@Nullable Text text) {
        return text == null ? null : new ChecklistMcpTools.Text(text.sv(), text.en());
    }

    private LibraryEntryDto entry(final Long id) {
        return libraryService.get(id);
    }

    @McpTool(name = "list_library_entries",
            description = "List the reference library: venues (pros, cons, booking, prices from earlier revivals), ticket services, role descriptions and roles to contact (roles, never named people). Guide items link to its entries.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<Entry> listLibraryEntries(
            @McpToolParam(required = false, description = "VENUE, TICKET_SERVICE, ROLE_DESCRIPTION or CONTACT_ROLE; all kinds when left out") final @Nullable LibraryEntryKind kind,
            @McpToolParam(required = false, description = "Also retired entries") final @Nullable Boolean includeRetired) {
        return support.call("list_library_entries", () -> new McpToolSupport.ListResult<>(libraryService.list(Boolean.TRUE.equals(includeRetired)).stream()
                .filter(entry -> kind == null || entry.kind() == kind)
                .map(Entry::of)
                .toList()));
    }

    @McpTool(name = "create_library_entry",
            description = "Super admins: add an entry to the reference library. Texts are plain text in Swedish, with optional English. A contact is a role, e.g. this year's directeur of Chalmersspexet, never a named person.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Entry createLibraryEntry(
            @McpToolParam(description = "VENUE, TICKET_SERVICE, ROLE_DESCRIPTION or CONTACT_ROLE; fixed once made") final LibraryEntryKind kind,
            @McpToolParam(description = "The name in Swedish") final String nameSv,
            @McpToolParam(required = false, description = "The name in English") final @Nullable String nameEn,
            @McpToolParam(required = false, description = "What it is, in Swedish") final @Nullable String descriptionSv,
            @McpToolParam(required = false, description = "What it is, in English") final @Nullable String descriptionEn,
            @McpToolParam(required = false, description = "VENUE, TICKET_SERVICE: pros, in Swedish") final @Nullable String prosSv,
            @McpToolParam(required = false, description = "VENUE, TICKET_SERVICE: cons, in Swedish") final @Nullable String consSv,
            @McpToolParam(required = false, description = "VENUE: how and where it is booked, in Swedish") final @Nullable String bookingSv,
            @McpToolParam(required = false, description = "VENUE, TICKET_SERVICE: what earlier revivals paid, in Swedish") final @Nullable String pricesSv,
            @McpToolParam(required = false, description = "CONTACT_ROLE: how the role is reached, in Swedish") final @Nullable String contactSv,
            @McpToolParam(required = false, description = "An https link to its own page") final @Nullable String url) {
        return support.call("create_library_entry", () -> Entry.of(libraryService.create(new LibraryService.Definition(kind,
                new LibraryService.Text(nameSv, nameEn), text(descriptionSv, descriptionEn, null), text(prosSv, null, null), text(consSv, null, null),
                text(bookingSv, null, null), text(pricesSv, null, null), text(contactSv, null, null), url))));
    }

    @McpTool(name = "update_library_entry",
            description = "Super admins: change an entry of the reference library. Leave out what stays as it is; an empty Swedish text clears that text. The kind cannot change.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Entry updateLibraryEntry(
            @McpToolParam(description = "The entry's id") final Long id,
            @McpToolParam(required = false, description = "The name in Swedish") final @Nullable String nameSv,
            @McpToolParam(required = false, description = "The name in English") final @Nullable String nameEn,
            @McpToolParam(required = false, description = "What it is, in Swedish") final @Nullable String descriptionSv,
            @McpToolParam(required = false, description = "What it is, in English") final @Nullable String descriptionEn,
            @McpToolParam(required = false, description = "Pros, in Swedish") final @Nullable String prosSv,
            @McpToolParam(required = false, description = "Cons, in Swedish") final @Nullable String consSv,
            @McpToolParam(required = false, description = "How and where it is booked, in Swedish") final @Nullable String bookingSv,
            @McpToolParam(required = false, description = "What earlier revivals paid, in Swedish") final @Nullable String pricesSv,
            @McpToolParam(required = false, description = "How the role is reached, in Swedish") final @Nullable String contactSv,
            @McpToolParam(required = false, description = "An https link; an empty string clears it") final @Nullable String url) {
        return support.call("update_library_entry", () -> {
            final LibraryEntryDto current = entry(id);

            return Entry.of(libraryService.update(id, current.version(), new LibraryService.Definition(current.kind(),
                    new LibraryService.Text(nameSv == null ? current.name().sv() : nameSv, nameEn == null ? current.name().en() : nameEn),
                    text(descriptionSv, descriptionEn, current.description()), text(prosSv, null, current.pros()), text(consSv, null, current.cons()),
                    text(bookingSv, null, current.booking()), text(pricesSv, null, current.prices()), text(contactSv, null, current.contact()),
                    url == null ? current.url() : url)));
        });
    }

    @McpTool(name = "set_library_entry_retired",
            description = "Super admins: retire a reference library entry so guide items can no longer be linked to it, or restore it. Items that link to it keep it; entries are never deleted.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Entry setLibraryEntryRetired(
            @McpToolParam(description = "The entry's id") final Long id,
            @McpToolParam(description = "true to retire, false to restore") final Boolean retired) {
        return support.call("set_library_entry_retired", () -> {
            final Long version = entry(id).version();

            return Entry.of(retired ? libraryService.retire(id, version) : libraryService.restore(id, version));
        });
    }

    /**
     * An entry of the reference library, with the texts its kind uses.
     */
    public record Entry(Long id, LibraryEntryKind kind, ChecklistMcpTools.Text name, ChecklistMcpTools.@Nullable Text description,
                        ChecklistMcpTools.@Nullable Text pros, ChecklistMcpTools.@Nullable Text cons, ChecklistMcpTools.@Nullable Text booking,
                        ChecklistMcpTools.@Nullable Text prices, ChecklistMcpTools.@Nullable Text contact, @Nullable String url, boolean retired) {

        static Entry of(final LibraryEntryDto entry) {
            return new Entry(entry.id(), entry.kind(), new ChecklistMcpTools.Text(entry.name().sv(), entry.name().en()), toMcp(entry.description()),
                    toMcp(entry.pros()), toMcp(entry.cons()), toMcp(entry.booking()), toMcp(entry.prices()), toMcp(entry.contact()), entry.url(),
                    entry.retired());
        }
    }
}
