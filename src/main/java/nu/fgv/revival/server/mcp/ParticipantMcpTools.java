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

import nu.fgv.revival.server.participant.InvitationState;
import nu.fgv.revival.server.participant.ParticipantDtos;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.participant.ParticipantResponse;
import nu.fgv.revival.server.participant.ParticipantService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * A revival's participants over MCP, read only, for its directeurs and steering group and the super admins: names,
 * positions, responses, invitations and whether fees are paid. Never contact details or dietary needs. Participants and
 * helpers get no MCP tools.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ParticipantMcpTools {

    private final ParticipantService participantService;
    private final McpToolSupport support;

    public ParticipantMcpTools(final ParticipantService participantService, final McpToolSupport support) {
        this.participantService = participantService;
        this.support = support;
    }

    static Participant toMcp(final ParticipantDtos.ParticipantDto participant) {
        return new Participant(participant.id(), (participant.firstName() + " " + participant.lastName()).strip(), participant.nickName(),
                participant.kind(), participant.position() == null ? null : participant.position().name(), participant.roleName(),
                participant.response(), participant.invitation(), participant.kind() == ParticipantKind.PARTICIPANT ? participant.feePaid() : null);
    }

    @McpTool(name = "list_participants",
            description = "List a revival's participants and helpers: name, kind, planned position and role, response to the invitation, how far the invitation has come, and whether the fee is paid. Never contact details or dietary needs.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<Participant> listParticipants(
            @McpToolParam(description = "A revival you can see") final Long revivalId,
            @McpToolParam(required = false, description = "Only this response: PENDING, YES, NO or MAYBE") final @Nullable ParticipantResponse response,
            @McpToolParam(required = false, description = "Only this position, by name or id") final @Nullable String position) {
        return support.call("list_participants", () -> new McpToolSupport.ListResult<>(participantService.list(revivalId, false, Locale.ENGLISH).stream()
                .filter(participant -> response == null || participant.response() == response)
                .filter(participant -> position == null || position.isBlank() || (participant.position() != null
                        && (participant.position().id().equals(position.strip()) || participant.position().name().equalsIgnoreCase(position.strip()))))
                .map(ParticipantMcpTools::toMcp)
                .toList()));
    }

    /**
     * Counts by response, for {@code get_revival_status}; null for callers who do not read participants.
     */
    @Nullable List<ParticipantDtos.ResponseCount> responseCounts(final Long revivalId) {
        final ParticipantDtos.Counts counts = participantService.counts(revivalId);

        return counts == null ? null : counts.byResponse();
    }

    public record Participant(Long id, String name, @Nullable String nickName, ParticipantKind kind,
                              @Nullable String position,
                              @Nullable String roleName, ParticipantResponse response, InvitationState invitation,
                              @Nullable Boolean feePaid) {
    }
}
