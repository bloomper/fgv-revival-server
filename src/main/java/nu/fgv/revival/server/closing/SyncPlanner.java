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

import nu.fgv.revival.server.participant.Participant;
import nu.fgv.revival.server.participant.ParticipantKind;
import nu.fgv.revival.server.participant.ParticipantRepository;
import nu.fgv.revival.server.participant.ParticipantResponse;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.spex.SpexareDirectory;
import nu.fgv.revival.server.spex.SpexregisterClient;
import nu.fgv.revival.server.util.error.SpexregisterUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.springframework.util.StringUtils.hasText;

/**
 * Works out what the sync would write for a revival (M7-FR-3), from its participants and what Spexregister already has,
 * read fresh. Participants are those who answered yes, are participants (not helpers) and are not removed; only spexare
 * can be written, so pending people are listed as excluded. For each: the participation in Spexregister's revival, the
 * task for their position, the actor for an ensemble role, and the FGV membership when the revival paid it. A membership
 * counts as existing when an earlier run wrote or found it, since Spexregister does not let the application read
 * memberships. The hash covers what would be written, in a stable order, so a start can tell whether the preview the
 * caller confirmed is still what would be done.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SyncPlanner {

    private final ParticipantRepository participantRepository;
    private final SyncStepRepository stepRepository;
    private final SpexregisterClient client;
    private final SpexareDirectory directory;

    public SyncPlanner(final ParticipantRepository participantRepository, final SyncStepRepository stepRepository, final SpexregisterClient client,
                       final SpexareDirectory directory) {
        this.participantRepository = participantRepository;
        this.stepRepository = stepRepository;
        this.client = client;
        this.directory = directory;
    }

    /**
     * One operation of the plan; {@code key} identifies what it writes, for the hash.
     */
    public record Operation(@Nullable Long participantId, SyncOperation operation, @Nullable String detail, boolean exists, String key) {
    }

    public record ParticipantPlan(Participant participant, List<Operation> operations) {
    }

    /**
     * {@code revival} is the {@link SyncOperation#REVIVAL} operation; {@code spexregisterRevivalId} is Spexregister's
     * revival when it exists.
     */
    public record Plan(Long revivalId, @Nullable Long spexregisterRevivalId, Operation revival, List<ParticipantPlan> participants,
                       List<Participant> excluded, String hash) {

        /**
         * Everything that would be written, the revival first, then per participant in operation order.
         */
        public List<Operation> planned() {
            final List<Operation> planned = new ArrayList<>();

            if (!revival.exists()) {
                planned.add(revival);
            }
            participants.forEach(participant -> participant.operations().stream().filter(operation -> !operation.exists()).forEach(planned::add));
            return planned;
        }
    }

    /**
     * The participants the sync is about: they answered yes, are participants and are not removed.
     */
    public List<Participant> participants(final Long revivalId) {
        return participantRepository.findByRevivalIdAndRemovedAtIsNullOrderByIdAsc(revivalId).stream()
                .filter(participant -> participant.getKind() == ParticipantKind.PARTICIPANT && participant.getResponse() == ParticipantResponse.YES)
                .toList();
    }

    /**
     * The plan for the revival as things are now. SPEXREGISTER_UNAVAILABLE when Spexregister cannot be read.
     */
    public Plan plan(final Revival revival, final String actingPerson) {
        final List<Participant> all = participants(revival.getId());
        final List<Participant> spexare = all.stream().filter(participant -> participant.getSpexareId() != null).toList();
        final List<Participant> excluded = all.stream().filter(participant -> participant.getSpexareId() == null).toList();
        final @Nullable Long remoteRevival = revival.getSpexregisterRevivalId() != null ? revival.getSpexregisterRevivalId()
                : findRemoteRevival(revival).orElse(null);
        final Operation revivalOperation = new Operation(null, SyncOperation.REVIVAL, String.valueOf(revival.getYear()), remoteRevival != null,
                "%d/%d".formatted(revival.getSpexId(), revival.getYear()));
        final Map<Long, SpexregisterClient.SpexareActivities> activities = remoteRevival == null ? Map.of() : activities(spexare, actingPerson);
        final Map<String, SpexareDirectory.Position> positions = positions();
        final Map<String, SpexareDirectory.VocalPart> vocalParts = vocalParts();
        final Set<Long> memberships = memberships(spexare, revival.getMembershipYear());
        final List<ParticipantPlan> plans = new ArrayList<>();

        for (final Participant participant : spexare) {
            final SpexregisterClient.@Nullable ActivityDetail activity = activityFor(activities.get(participant.getSpexareId()), remoteRevival);

            plans.add(new ParticipantPlan(participant, operations(participant, activity, positions, vocalParts, revival.getMembershipYear(),
                    memberships.contains(participant.getId()))));
        }

        final Plan unhashed = new Plan(revival.getId(), remoteRevival, revivalOperation, List.copyOf(plans), List.copyOf(excluded), "");

        return new Plan(revival.getId(), remoteRevival, revivalOperation, unhashed.participants(), unhashed.excluded(), hash(unhashed.planned()));
    }

    /**
     * Spexregister's revival of the revival's spex in its year, if it records one.
     */
    public Optional<Long> findRemoteRevival(final Revival revival) {
        return unavailableOnFailure(() -> client.spexRevivals(revival.getSpexId())).stream()
                .filter(remote -> Objects.equals(remote.year(), revival.getYear()))
                .map(SpexregisterClient.SpexRevival::id)
                .findFirst();
    }

    /**
     * The participant's activity in Spexregister's revival, if any.
     */
    static SpexregisterClient.@Nullable ActivityDetail activityFor(final SpexregisterClient.@Nullable SpexareActivities spexare,
                                                                   final @Nullable Long remoteRevival) {
        if (spexare == null || remoteRevival == null) {
            return null;
        }
        return spexare.all().stream().filter(activity -> String.valueOf(remoteRevival).equals(activity.spexId())).findFirst().orElse(null);
    }

    static SpexregisterClient.@Nullable TaskActivity taskFor(final SpexregisterClient.@Nullable ActivityDetail activity, final @Nullable String taskId) {
        if (activity == null || taskId == null) {
            return null;
        }
        return activity.tasks().stream().filter(task -> task.task() != null && taskId.equals(task.task().id())).findFirst().orElse(null);
    }

    private static List<Operation> operations(final Participant participant, final SpexregisterClient.@Nullable ActivityDetail activity,
                                              final Map<String, SpexareDirectory.Position> positions,
                                              final Map<String, SpexareDirectory.VocalPart> vocalParts, final @Nullable Integer membershipYear,
                                              final boolean membershipRecorded) {
        final Long id = participant.getId();
        final List<Operation> operations = new ArrayList<>();

        operations.add(new Operation(id, SyncOperation.PARTICIPATION, null, activity != null, id + "/" + participant.getSpexareId()));

        final String taskId = participant.getPositionTaskId();

        if (taskId != null) {
            final SpexareDirectory.Position position = positions.get(taskId);
            final SpexregisterClient.TaskActivity task = taskFor(activity, taskId);

            operations.add(new Operation(id, SyncOperation.TASK, position == null ? taskId : position.name(), task != null, id + "/" + taskId));
            if (position != null && position.hasRole() && hasText(participant.getRoleName())) {
                final String role = participant.getRoleName().strip();
                final String vocal = participant.getVocalPartId();
                final SpexareDirectory.VocalPart part = vocal == null ? null : vocalParts.get(vocal);
                final String detail = vocal == null ? role : "%s (%s)".formatted(role, part == null ? vocal : part.name());

                operations.add(new Operation(id, SyncOperation.ACTOR, truncate(detail), task != null && !task.allActors().isEmpty(),
                        id + "/" + taskId + "/" + role + "/" + Objects.requireNonNullElse(vocal, SpexregisterClient.UNKNOWN_VOCAL)));
            }
        }
        if (participant.isMembershipPaid() && membershipYear != null) {
            operations.add(new Operation(id, SyncOperation.MEMBERSHIP, String.valueOf(membershipYear), membershipRecorded, id + "/" + membershipYear));
        }
        return List.copyOf(operations);
    }

    private static String truncate(final String detail) {
        return detail.length() <= SyncStep.DETAIL_MAX_LENGTH ? detail : detail.substring(0, SyncStep.DETAIL_MAX_LENGTH);
    }

    /**
     * SHA-256 over the operations that would be written, one line each, in plan order (which is stable: the revival,
     * then participants by id, operations in enum order).
     */
    static String hash(final List<Operation> planned) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");

            for (final Operation operation : planned) {
                digest.update("%s|%s\n".formatted(operation.operation(), operation.key()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<Long, SpexregisterClient.SpexareActivities> activities(final List<Participant> participants, final String actingPerson) {
        final List<Long> ids = participants.stream().map(Participant::getSpexareId).filter(Objects::nonNull).distinct().toList();
        final Map<Long, SpexregisterClient.SpexareActivities> found = new HashMap<>();

        for (int from = 0; from < ids.size(); from += SpexregisterClient.MAX_SPEXARE) {
            final List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + SpexregisterClient.MAX_SPEXARE));

            unavailableOnFailure(() -> client.spexareActivities(chunk, actingPerson)).forEach(spexare -> found.put(Long.valueOf(spexare.id()), spexare));
        }
        return found;
    }

    private Map<String, SpexareDirectory.Position> positions() {
        final Map<String, SpexareDirectory.Position> byId = new HashMap<>();

        directory.positions().forEach(position -> byId.put(position.id(), position));
        return byId;
    }

    private Map<String, SpexareDirectory.VocalPart> vocalParts() {
        return directory.vocalPartsOrEmpty();
    }

    private Set<Long> memberships(final List<Participant> participants, final @Nullable Integer year) {
        final List<Long> paid = participants.stream().filter(Participant::isMembershipPaid).map(Participant::getId).toList();

        if (year == null || paid.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(stepRepository.membershipsRecorded(paid, String.valueOf(year), List.of(SyncStepStatus.DONE, SyncStepStatus.EXISTED)));
    }

    private static <T> T unavailableOnFailure(final Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (final SpexregisterClient.SpexregisterCallException e) {
            throw new SpexregisterUnavailableException(e);
        }
    }
}
