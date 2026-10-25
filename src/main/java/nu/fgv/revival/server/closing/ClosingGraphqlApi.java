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

package nu.fgv.revival.server.closing;

import nu.fgv.revival.server.closing.ClosingDtos.ClosingStatusDto;
import nu.fgv.revival.server.closing.ClosingDtos.SyncFailureDto;
import nu.fgv.revival.server.closing.ClosingDtos.SyncPreviewDto;
import nu.fgv.revival.server.closing.ClosingDtos.SyncRunDto;
import nu.fgv.revival.server.member.PersonRefDto;
import nu.fgv.revival.server.person.PersonNames;
import nu.fgv.revival.server.revival.RevivalDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Locale;

/**
 * No authorization here: the services decide.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ClosingGraphqlApi {

    private final SyncService syncService;
    private final ClosingService closingService;
    private final PersonNames personNames;

    public ClosingGraphqlApi(final SyncService syncService, final ClosingService closingService, final PersonNames personNames) {
        this.syncService = syncService;
        this.closingService = closingService;
        this.personNames = personNames;
    }

    @QueryMapping
    public SyncPreviewDto syncPreview(@Argument final Long revivalId, final Locale locale) {
        return syncService.preview(revivalId, locale);
    }

    @QueryMapping
    public SyncRunDto syncRun(@Argument final Long id) {
        return syncService.run(id);
    }

    @MutationMapping
    public SyncRunDto syncStart(@Argument final Long revivalId, @Argument final String previewHash) {
        return syncService.start(revivalId, previewHash);
    }

    @SchemaMapping(typeName = "SyncRun", field = "startedBy")
    public @Nullable PersonRefDto startedBy(final SyncRunDto run, final Locale locale) {
        return run.startedById() == null ? null
                : new PersonRefDto(run.startedById(), personNames.byIds(List.of(run.startedById()), locale).get(run.startedById()));
    }

    @SchemaMapping(typeName = "SyncRun", field = "failures")
    public List<SyncFailureDto> failures(final SyncRunDto run, final Locale locale) {
        return syncService.failures(run, locale);
    }

    @SchemaMapping(typeName = "Revival", field = "closing")
    public @Nullable ClosingStatusDto closing(final RevivalDto revival) {
        return closingService.closing(revival.id());
    }
}
