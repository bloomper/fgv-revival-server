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

package nu.fgv.revival.server.revival;

import nu.fgv.revival.server.member.RevivalMemberDto;
import nu.fgv.revival.server.member.RevivalMemberService;
import nu.fgv.revival.server.member.RevivalRoleAssignmentDto;
import nu.fgv.revival.server.person.PersonNames;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * No authorization here: the services decide (constitution II).
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Controller
public class RevivalGraphqlApi {

    private final RevivalService service;
    private final RevivalMemberService memberService;
    private final PersonNames personNames;

    public RevivalGraphqlApi(final RevivalService service, final RevivalMemberService memberService, final PersonNames personNames) {
        this.service = service;
        this.memberService = memberService;
        this.personNames = personNames;
    }

    @QueryMapping
    public List<RevivalDto> revivals(@Argument final @Nullable List<RevivalStatus> status) {
        return service.findVisible(status);
    }

    @QueryMapping
    public RevivalDto revival(@Argument final Long id) {
        return service.findById(id);
    }

    @MutationMapping
    public RevivalDto revivalCreate(@Argument final RevivalCreateDto input) {
        return service.create(input);
    }

    @MutationMapping
    public RevivalDto revivalUpdate(@Argument final RevivalUpdateDto input) {
        return service.update(input);
    }

    @MutationMapping
    public RevivalDto revivalStatusChange(@Argument final RevivalStatusChangeDto input) {
        return service.changeStatus(input);
    }

    @SchemaMapping(typeName = "Revival", field = "members")
    public List<RevivalMemberDto> members(final RevivalDto revival) {
        return memberService.findByRevival(revival.id());
    }

    @SchemaMapping(typeName = "Revival", field = "statusHistory")
    public List<RevivalStatusChangeHistoryDto> statusHistory(final RevivalDto revival) {
        return service.statusHistory(revival.id());
    }

    @SchemaMapping(typeName = "Revival", field = "createdBy")
    public @Nullable String createdBy(final RevivalDto revival, final Locale locale) {
        return personNames.bySubject(revival.createdBy(), locale);
    }

    @SchemaMapping(typeName = "Revival", field = "lastModifiedBy")
    public @Nullable String lastModifiedBy(final RevivalDto revival, final Locale locale) {
        return personNames.bySubject(revival.lastModifiedBy(), locale);
    }

    @SchemaMapping(typeName = "RevivalStatusChange", field = "changedBy")
    public @Nullable String changedBy(final RevivalStatusChangeHistoryDto change, final Locale locale) {
        final Map<Long, String> names = personNames.byIds(List.of(change.changedBy()), locale);

        return names.get(change.changedBy());
    }

    @SchemaMapping(typeName = "RevivalRoleAssignment", field = "revival")
    public RevivalDto assignmentRevival(final RevivalRoleAssignmentDto assignment) {
        return service.findById(assignment.revivalId());
    }

}
