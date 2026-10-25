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

import nu.fgv.revival.server.budget.BudgetCategory;
import nu.fgv.revival.server.budget.BudgetCategoryKind;
import nu.fgv.revival.server.budget.BudgetCategoryRole;
import nu.fgv.revival.server.budget.BudgetCategoryService;
import nu.fgv.revival.server.guide.GuideItemKind;
import nu.fgv.revival.server.guide.GuideItemType;
import nu.fgv.revival.server.guide.GuideItemTypeService;
import nu.fgv.revival.server.material.LinkService;
import nu.fgv.revival.server.material.LinkServiceCatalog;
import nu.fgv.revival.server.member.CapabilityService;
import nu.fgv.revival.server.util.catalog.CatalogEntry;
import nu.fgv.revival.server.util.catalog.CatalogService;
import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * The lists super admins keep, over MCP: areas of responsibility, guide item types, budget categories and the
 * services materials link to. Entries are retired and restored here, never deleted; anyone with access reads them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class CatalogMcpTools {

    private final CapabilityService capabilities;
    private final GuideItemTypeService itemTypes;
    private final BudgetCategoryService budgetCategories;
    private final LinkServiceCatalog linkServices;
    private final McpToolSupport support;

    public CatalogMcpTools(final CapabilityService capabilities, final GuideItemTypeService itemTypes, final BudgetCategoryService budgetCategories,
                           final LinkServiceCatalog linkServices, final McpToolSupport support) {
        this.capabilities = capabilities;
        this.itemTypes = itemTypes;
        this.budgetCategories = budgetCategories;
        this.linkServices = linkServices;
        this.support = support;
    }

    private static Entry toMcp(final CatalogEntry entry) {
        return switch (entry) {
            case final BudgetCategory category -> Entry.of(entry, category.getKind().name(), category.getRole() == null ? null : category.getRole().name(),
                    null, null);
            case final GuideItemType type -> Entry.of(entry, type.getKind().name(), null, null, null);
            case final LinkService service -> Entry.of(entry, null, null, service.getHosts().stream().sorted().toList(), service.getHomeUrl());
            default -> Entry.of(entry, null, null, null, null);
        };
    }

    private CatalogService<? extends CatalogEntry> service(final Catalog catalog) {
        return switch (catalog) {
            case CAPABILITIES -> capabilities;
            case GUIDE_ITEM_TYPES -> itemTypes;
            case BUDGET_CATEGORIES -> budgetCategories;
            case LINK_SERVICES -> linkServices;
        };
    }

    private CatalogEntry entry(final Catalog catalog, final String id) {
        return McpToolSupport.byId(List.copyOf(service(catalog).list(true)), CatalogEntry::getId, id, CatalogEntry.class);
    }

    private static <T> T required(final @Nullable T value, final String what) {
        if (value == null) {
            throw new BadRequestException("error.mcp.required", what);
        }
        return value;
    }

    @McpTool(name = "list_catalog_entries",
            description = "List one of the lists super admins keep: CAPABILITIES (areas of responsibility, e.g. Ekonomi), GUIDE_ITEM_TYPES (with their kind), BUDGET_CATEGORIES (income or cost, and role) or LINK_SERVICES (with hosts and home page). Rows hold an entry's id.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<Entry> listCatalogEntries(
            @McpToolParam(description = "CAPABILITIES, GUIDE_ITEM_TYPES, BUDGET_CATEGORIES or LINK_SERVICES") final Catalog catalog,
            @McpToolParam(required = false, description = "Also retired entries") final @Nullable Boolean includeRetired) {
        return support.call("list_catalog_entries", () -> new McpToolSupport.ListResult<>(service(catalog).list(Boolean.TRUE.equals(includeRetired)).stream()
                .map(CatalogMcpTools::toMcp).toList()));
    }

    @McpTool(name = "create_catalog_entry",
            description = "Super admins: add an entry to a list. A guide item type needs a kind (TIP, REQUIREMENT, QUESTION or TEXT) and a budget category a kind (INCOME or COST), both fixed once made; a budget category may have a role; a link service may have hosts and a home page.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Entry createCatalogEntry(
            @McpToolParam(description = "CAPABILITIES, GUIDE_ITEM_TYPES, BUDGET_CATEGORIES or LINK_SERVICES") final Catalog catalog,
            @McpToolParam(description = "The name in Swedish") final String nameSv,
            @McpToolParam(required = false, description = "The name in English") final @Nullable String nameEn,
            @McpToolParam(required = false, description = "GUIDE_ITEM_TYPES: TIP, REQUIREMENT, QUESTION or TEXT; BUDGET_CATEGORIES: INCOME or COST") final @Nullable String kind,
            @McpToolParam(required = false, description = "BUDGET_CATEGORIES: the role the budget gives it, e.g. SHOW_TICKETS") final @Nullable BudgetCategoryRole role,
            @McpToolParam(required = false, description = "LINK_SERVICES: hosts it is recognised by, e.g. drive.google.com") final @Nullable List<String> hosts,
            @McpToolParam(required = false, description = "LINK_SERVICES: its home page") final @Nullable String homeUrl) {
        return support.call("create_catalog_entry", () -> {
            final CatalogService.Names names = new CatalogService.Names(nameSv, nameEn);

            return toMcp(switch (catalog) {
                case CAPABILITIES -> capabilities.create(names);
                case GUIDE_ITEM_TYPES -> itemTypes.create(GuideItemKind.valueOf(required(kind, "kind")), names);
                case BUDGET_CATEGORIES -> budgetCategories.create(BudgetCategoryKind.valueOf(required(kind, "kind")), role, names);
                case LINK_SERVICES -> linkServices.create(names, hosts, homeUrl);
            });
        });
    }

    @McpTool(name = "update_catalog_entry",
            description = "Super admins: rename an entry, change a budget category's role or a link service's hosts and home page, and optionally move it to a position in the list (counting from 0). Leave out what stays as it is.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Entry updateCatalogEntry(
            @McpToolParam(description = "CAPABILITIES, GUIDE_ITEM_TYPES, BUDGET_CATEGORIES or LINK_SERVICES") final Catalog catalog,
            @McpToolParam(description = "The entry's id") final String id,
            @McpToolParam(required = false, description = "The name in Swedish") final @Nullable String nameSv,
            @McpToolParam(required = false, description = "The name in English") final @Nullable String nameEn,
            @McpToolParam(required = false, description = "BUDGET_CATEGORIES: the role") final @Nullable BudgetCategoryRole role,
            @McpToolParam(required = false, description = "LINK_SERVICES: the hosts, replacing the current ones") final @Nullable List<String> hosts,
            @McpToolParam(required = false, description = "LINK_SERVICES: its home page") final @Nullable String homeUrl,
            @McpToolParam(required = false, description = "Its place in the list, counting from 0") final @Nullable Integer position) {
        return support.call("update_catalog_entry", () -> {
            CatalogEntry entry = entry(catalog, id);

            if (nameSv != null || nameEn != null || role != null || hosts != null || homeUrl != null) {
                final CatalogService.Names names = new CatalogService.Names(Objects.requireNonNullElse(nameSv, entry.getNameSv()),
                        nameEn == null ? entry.getNameEn() : nameEn);

                entry = switch (entry) {
                    case final BudgetCategory category -> budgetCategories.update(id, category.getVersion(), names, role == null ? category.getRole() : role);
                    case final LinkService service -> linkServices.update(id, service.getVersion(), names,
                            hosts == null ? List.copyOf(service.getHosts()) : hosts, homeUrl == null ? service.getHomeUrl() : homeUrl);
                    default -> service(catalog).rename(id, entry.getVersion(), names);
                };
            }
            if (position != null) {
                service(catalog).move(id, entry.getVersion(), position);
                entry = entry(catalog, id);
            }
            return toMcp(entry);
        });
    }

    @McpTool(name = "set_catalog_entry_retired",
            description = "Super admins: retire an entry so it is no longer offered, or restore it. Rows that hold it keep it; entries are never deleted here.",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Entry setCatalogEntryRetired(
            @McpToolParam(description = "CAPABILITIES, GUIDE_ITEM_TYPES, BUDGET_CATEGORIES or LINK_SERVICES") final Catalog catalog,
            @McpToolParam(description = "The entry's id") final String id,
            @McpToolParam(description = "true to retire, false to restore") final Boolean retired) {
        return support.call("set_catalog_entry_retired", () -> {
            final Long version = entry(catalog, id).getVersion();

            return toMcp(retired ? service(catalog).retire(id, version) : service(catalog).restore(id, version));
        });
    }

    public enum Catalog {
        CAPABILITIES, GUIDE_ITEM_TYPES, BUDGET_CATEGORIES, LINK_SERVICES
    }

    /**
     * An entry of a list: its kind and role for guide item types and budget categories, hosts and home page for link
     * services.
     */
    public record Entry(String id, ChecklistMcpTools.Text name, int position, boolean retired, @Nullable String kind, @Nullable String role,
                        @Nullable List<String> hosts, @Nullable String homeUrl) {

        static Entry of(final CatalogEntry entry, final @Nullable String kind, final @Nullable String role, final @Nullable List<String> hosts,
                        final @Nullable String homeUrl) {
            return new Entry(entry.getId(), new ChecklistMcpTools.Text(entry.getNameSv(), entry.getNameEn()), entry.getPosition(), entry.isRetired(), kind,
                    role, hosts, homeUrl);
        }
    }
}
