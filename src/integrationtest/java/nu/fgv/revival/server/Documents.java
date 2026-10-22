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

package nu.fgv.revival.server;

/**
 * GraphQL documents shared by the integration tests.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
public final class Documents {

    public static final String REVIVALS = "query($status: [RevivalStatus!]) { revivals(status: $status) { id title status spex { id title available } } }";
    public static final String REVIVAL = """
            query($id: ID!) {
                revival(id: $id) {
                    id year title premiereDate showDates venues status version createdBy createdAt lastModifiedBy
                    spex { id title year available }
                    members { id role capabilities state displayName email version }
                    statusHistory { from to changedBy changedAt }
                    permissions { canEditCore canEditDetails allowedStatuses canManageDirecteurs canManageSteeringGroup }
                }
            }""";
    public static final String CREATE = "mutation($input: RevivalCreateInput!) { revivalCreate(input: $input) { id status title spex { title } } }";
    public static final String UPDATE = "mutation($input: RevivalUpdateInput!) { revivalUpdate(input: $input) { id version title year premiereDate showDates venues spex { id } } }";
    public static final String STATUS = "mutation($input: RevivalStatusChangeInput!) { revivalStatusChange(input: $input) { id status } }";
    public static final String MEMBER_ADD = "mutation($input: RevivalMemberAddInput!) { revivalMemberAdd(input: $input) { id role state capabilities email version } }";
    public static final String MEMBER_CAPABILITIES = "mutation($input: RevivalMemberCapabilitiesInput!) { revivalMemberCapabilitiesSet(input: $input) { id capabilities version } }";
    public static final String MEMBER_REMOVE = "mutation($id: ID!) { revivalMemberRemove(id: $id) }";
    public static final String SPEX_SEARCH = "query($query: String!) { spexSearch(query: $query) { id title year available } }";

    private Documents() {
    }
}
