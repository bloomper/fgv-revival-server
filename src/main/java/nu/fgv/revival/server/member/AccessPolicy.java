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

package nu.fgv.revival.server.member;

import jakarta.persistence.criteria.Predicate;
import nu.fgv.revival.server.guide.GuideVersionSummaryDto;
import nu.fgv.revival.server.material.MaterialVisibility;
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static nu.fgv.revival.server.revival.RevivalStatus.APPROVED;
import static nu.fgv.revival.server.revival.RevivalStatus.ARCHIVED;
import static nu.fgv.revival.server.revival.RevivalStatus.CLOSING;
import static nu.fgv.revival.server.revival.RevivalStatus.OPEN;
import static nu.fgv.revival.server.revival.RevivalStatus.ORGANISING;
import static nu.fgv.revival.server.revival.RevivalStatus.PROPOSED;
import static nu.fgv.revival.server.revival.RevivalStatus.REJECTED;
import static nu.fgv.revival.server.revival.RevivalStatus.SETTLING;

/**
 * Every authorization rule, in one place. Anything not permitted here is refused.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class AccessPolicy {

    /**
     * The area of responsibility that lets a steering group member change the budget.
     */
    public static final String FINANCE = "FINANCE";
    /**
     * The area of responsibility that lets a steering group member read participants' dietary needs.
     */
    public static final String CATERING = "CATERING";
    /**
     * The area of responsibility that lets a steering group member plan shifts and assign people.
     */
    public static final String STAFFING = "STAFFING";
    /**
     * The area of responsibility that lets a steering group member send mailings.
     */
    public static final String COMMS = "COMMS";
    private static final Set<RevivalStatus> DIRECTEUR_CLOSING = EnumSet.of(CLOSING, SETTLING);
    private static final Set<RevivalStatus> BEFORE_WORK = EnumSet.of(PROPOSED, APPROVED, REJECTED);
    private static final Set<RevivalStatus> SUPER_ADMIN_ANONYMIZE = EnumSet.of(SETTLING, ARCHIVED, REJECTED);
    private static final Map<RevivalRole, Set<RevivalStatus>> VISIBLE_STATUSES = Map.of(
            RevivalRole.DIRECTEUR, EnumSet.of(APPROVED, ORGANISING, OPEN, CLOSING, SETTLING),
            RevivalRole.STEERING_GROUP, EnumSet.of(ORGANISING, OPEN, CLOSING),
            RevivalRole.PARTICIPANT, EnumSet.of(OPEN),
            RevivalRole.HELPER, EnumSet.of(OPEN)
    );

    /**
     * The statuses in which a revival role sees its revival. Participants and helpers see theirs while it is
     * {@code OPEN}; seeing it gives them their own page, nothing of the steering group's.
     */
    public static Set<RevivalStatus> visibleStatuses(final RevivalRole role) {
        return VISIBLE_STATUSES.get(role);
    }

    private static boolean seesEverything(final Caller caller) {
        return caller.isSuperAdmin() || caller.isObserver();
    }

    private static boolean isVisibleDirecteur(final Caller caller, final Revival revival) {
        final RevivalRoleAssignment assignment = caller.roleIn(revival.getId());

        return assignment != null
                && assignment.role() == RevivalRole.DIRECTEUR
                && visibleStatuses(RevivalRole.DIRECTEUR).contains(revival.getStatus());
    }

    private static boolean isArchived(final Revival revival) {
        return revival.getStatus() == ARCHIVED;
    }

    private static boolean holdsRevivalRole(final Caller caller, final Revival revival) {
        final RevivalRoleAssignment assignment = revival.getId() == null ? null : caller.roleIn(revival.getId());

        return assignment != null
                && (assignment.role() == RevivalRole.DIRECTEUR || assignment.role() == RevivalRole.STEERING_GROUP)
                && visibleStatuses(assignment.role()).contains(revival.getStatus());
    }

    private static boolean isVisibleParticipant(final Caller caller, final Revival revival, final RevivalRole role) {
        final RevivalRoleAssignment assignment = revival.getId() == null ? null : caller.roleIn(revival.getId());

        return assignment != null && assignment.role() == role && visibleStatuses(role).contains(revival.getStatus());
    }

    private static boolean holdsCapability(final Caller caller, final Revival revival, final String capability) {
        if (!holdsRevivalRole(caller, revival)) {
            return false;
        }

        final RevivalRoleAssignment assignment = caller.roleIn(revival.getId());

        return assignment != null && (assignment.holdsAll() || assignment.capabilities().contains(capability));
    }

    private static void require(final boolean permitted) {
        if (!permitted) {
            throw new AccessDeniedException("Not permitted");
        }
    }

    public boolean canSee(final Caller caller, final Revival revival) {
        if (seesEverything(caller)) {
            return true;
        }

        final RevivalRoleAssignment assignment = caller.roleIn(revival.getId());

        return assignment != null && visibleStatuses(assignment.role()).contains(revival.getStatus());
    }

    /**
     * The same rule as {@link #canSee} as a query predicate, so lists and single lookups agree.
     */
    public Specification<Revival> visibilityPredicate(final Caller caller) {
        if (seesEverything(caller)) {
            return (root, query, cb) -> cb.conjunction();
        }

        return (root, query, cb) -> {
            final List<Predicate> predicates = new ArrayList<>();

            for (final RevivalRole role : RevivalRole.values()) {
                final List<Long> revivalIds = caller.revivalRoles().entrySet().stream()
                        .filter(entry -> entry.getValue().role() == role)
                        .map(Map.Entry::getKey)
                        .toList();
                final Set<RevivalStatus> statuses = visibleStatuses(role);

                if (!revivalIds.isEmpty() && !statuses.isEmpty()) {
                    predicates.add(cb.and(root.get("id").in(revivalIds), root.get("status").in(statuses)));
                }
            }
            return predicates.isEmpty() ? cb.disjunction() : cb.or(predicates.toArray(Predicate[]::new));
        };
    }

    public boolean canEditCore(final Caller caller, final Revival revival) {
        return caller.isSuperAdmin() && !isArchived(revival);
    }

    public boolean canEditDetails(final Caller caller, final Revival revival) {
        return (caller.isSuperAdmin() || isVisibleDirecteur(caller, revival)) && !isArchived(revival);
    }

    /**
     * Super admins may set any other status, including an earlier one. A directeur may take only the next step.
     * Everyone else, nothing.
     */
    public List<RevivalStatus> allowedStatuses(final Caller caller, final Revival revival) {
        if (caller.isSuperAdmin()) {
            return Arrays.stream(RevivalStatus.values()).filter(status -> status != revival.getStatus()).toList();
        }

        final RevivalStatus next = revival.getStatus().nextDirecteurStep();

        return isVisibleDirecteur(caller, revival) && next != null ? List.of(next) : List.of();
    }

    public boolean canManageDirecteurs(final Caller caller, final Revival revival) {
        return caller.isSuperAdmin() && !isArchived(revival);
    }

    public boolean canManageSteeringGroup(final Caller caller, final Revival revival) {
        return (caller.isSuperAdmin() || isVisibleDirecteur(caller, revival)) && !isArchived(revival);
    }

    public boolean canManageMembers(final Caller caller, final Revival revival, final RevivalRole role) {
        return switch (role) {
            case DIRECTEUR -> canManageDirecteurs(caller, revival);
            case STEERING_GROUP -> canManageSteeringGroup(caller, revival);
            case PARTICIPANT, HELPER -> false;
        };
    }

    /**
     * Searching the FGV realm's users to pick a member: whoever may manage the steering group.
     */
    public boolean canSearchMemberCandidates(final Caller caller, final Revival revival) {
        return canManageSteeringGroup(caller, revival);
    }

    public boolean canSeeMemberEmails(final Caller caller, final Revival revival) {
        return caller.isSuperAdmin() || isVisibleDirecteur(caller, revival);
    }

    public boolean canSearchSpex(final Caller caller) {
        return caller.isSuperAdmin();
    }

    public boolean canCreateRevival(final Caller caller) {
        return caller.isSuperAdmin();
    }

    public boolean canDeleteRevival(final Caller caller) {
        return caller.isSuperAdmin();
    }

    /**
     * Super admins and observers read the guide, and so does anyone who is directeur or steering group of a revival
     * they can see. {@code callerRevivals} are the caller's revivals as {@link #visibilityPredicate}
     * finds them.
     */
    public boolean canReadGuide(final Caller caller, final Collection<Revival> callerRevivals) {
        return seesEverything(caller) || callerRevivals.stream().anyMatch(revival -> holdsRevivalRole(caller, revival));
    }

    /**
     * Super admins and observers, and its directeurs and steering group while they can see it, read the guide a revival
     * follows. Participants and helpers do not.
     */
    public boolean canReadRevivalGuide(final Caller caller, final Revival revival) {
        return canReadRevivalWork(caller, revival);
    }

    /**
     * Reading, editing, importing, publishing and discarding the draft.
     */
    public boolean canManageGuide(final Caller caller) {
        return caller.isSuperAdmin();
    }

    public boolean canSuggest(final Caller caller, final Revival revival) {
        return holdsRevivalRole(caller, revival) && !isArchived(revival);
    }

    public boolean canDecideSuggestion(final Caller caller) {
        return caller.isSuperAdmin();
    }

    public boolean canWithdrawSuggestion(final Caller caller, final Revival revival, final Long authorId) {
        return authorId.equals(caller.person().getId()) && canSuggest(caller, revival);
    }

    public boolean canSeeSuggestion(final Caller caller, final Revival revival) {
        return seesEverything(caller) || canSuggest(caller, revival);
    }

    /**
     * Super admins pin a revival that is not archived to any published version. A directeur only moves their revival to
     * a version published after its current one. Versions are compared by publish time, never by label.
     */
    public boolean canChangeGuideVersion(final Caller caller, final Revival revival,
                                         final @Nullable Instant fromPublishedAt, final Instant toPublishedAt) {
        if (isArchived(revival)) {
            return false;
        }
        if (caller.isSuperAdmin()) {
            return true;
        }
        return isVisibleDirecteur(caller, revival) && (fromPublishedAt == null || toPublishedAt.isAfter(fromPublishedAt));
    }

    /**
     * The published versions the caller may pin a revival to, other than the one it follows.
     */
    public List<GuideVersionSummaryDto> guideVersionsAllowed(final Caller caller, final Revival revival,
                                                             final List<GuideVersionSummaryDto> published) {
        final Instant current = published.stream()
                .filter(version -> version.id().equals(revival.getGuideVersionId()))
                .map(GuideVersionSummaryDto::publishedAt)
                .findFirst()
                .orElse(null);

        return published.stream()
                .filter(version -> version.publishedAt() != null && !version.id().equals(revival.getGuideVersionId()))
                .filter(version -> canChangeGuideVersion(caller, revival, current, version.publishedAt()))
                .toList();
    }

    /**
     * Reading a revival's decision log, meetings, checklists, members and history: super admins and observers, and its
     * directeurs and steering group while they can see it. Participants and helpers, who see an
     * {@code OPEN} revival only for their own page, never.
     */
    public boolean canReadRevivalWork(final Caller caller, final Revival revival) {
        return seesEverything(caller) || holdsRevivalRole(caller, revival);
    }

    /**
     * Whether the caller is a participant or helper of the revival, whatever its status.
     */
    public boolean isParticipant(final Caller caller, final Revival revival) {
        final RevivalRoleAssignment assignment = revival.getId() == null ? null : caller.roleIn(revival.getId());

        return assignment != null && (assignment.role() == RevivalRole.PARTICIPANT || assignment.role() == RevivalRole.HELPER);
    }

    /**
     * Reading a revival's events: whoever reads its work, and its participants while they can see it. Helpers get only
     * the events they hold shifts in, on their own page, never the revival's list.
     */
    public boolean canReadEvents(final Caller caller, final Revival revival) {
        return canReadRevivalWork(caller, revival) || isVisibleParticipant(caller, revival, RevivalRole.PARTICIPANT);
    }

    /**
     * Changing a revival's decision log, meetings and checklists: its directeurs and steering group members, while they
     * can see it and it is not archived. Super admins and observers only read.
     */
    public boolean canWriteRevivalWork(final Caller caller, final Revival revival) {
        return holdsRevivalRole(caller, revival) && !isArchived(revival);
    }

    /**
     * Marking a guide requirement not applicable: a directeur only.
     */
    public boolean canWaiveRequirement(final Caller caller, final Revival revival) {
        return revival.getId() != null && isVisibleDirecteur(caller, revival) && !isArchived(revival);
    }

    public boolean canReadChecklistTemplates(final Caller caller, final Collection<Revival> callerRevivals) {
        return canReadGuide(caller, callerRevivals);
    }

    public boolean canManageChecklistTemplates(final Caller caller) {
        return caller.isSuperAdmin();
    }

    /**
     * The lists the super admin keeps: areas of responsibility and guide item types.
     */
    public boolean canManageCatalogs(final Caller caller) {
        return caller.isSuperAdmin();
    }

    /**
     * The event types the super admin keeps. Everyone signed in reads them.
     */
    public boolean canManageEventTypes(final Caller caller) {
        return caller.isSuperAdmin();
    }

    /**
     * A secret calendar link for a revival: anyone holding a revival role in it while they can see it. Super
     * admins and observers without a role have none.
     */
    public boolean canSubscribeCalendar(final Caller caller, final Revival revival) {
        return revival.getId() != null && caller.roleIn(revival.getId()) != null && canSee(caller, revival);
    }

    /**
     * Steering group meetings in a calendar feed: its directeurs and steering group members, while they can see it.
     * Participants and helpers get the revival's events and shows only.
     */
    public boolean canSeeMeetingsInCalendar(final Caller caller, final Revival revival) {
        return holdsRevivalRole(caller, revival);
    }

    /**
     * Reading a revival's budget: super admins and observers, and its directeurs and steering group members while they
     * can see it. Participants and helpers never, whatever their status.
     */
    public boolean canReadBudget(final Caller caller, final Revival revival) {
        return seesEverything(caller) || holdsRevivalRole(caller, revival);
    }

    /**
     * Changing a revival's budget: a directeur, or a steering group member with the {@value #FINANCE} area, while they
     * can see it and it is not archived. Super admins and observers only read.
     */
    public boolean canWriteBudget(final Caller caller, final Revival revival) {
        if (isArchived(revival) || !holdsRevivalRole(caller, revival)) {
            return false;
        }

        final RevivalRoleAssignment assignment = caller.roleIn(revival.getId());

        return assignment != null && (assignment.holdsAll() || assignment.capabilities().contains(FINANCE));
    }

    /**
     * Budget categories and reference figures from earlier revivals: read by whoever reads the guide.
     */
    public boolean canReadReferenceFigures(final Caller caller, final Collection<Revival> callerRevivals) {
        return canReadGuide(caller, callerRevivals);
    }

    /**
     * The reference figures and the lines event types propose for budgets: the super admin keeps them.
     */
    public boolean canManageReferenceFigures(final Caller caller) {
        return caller.isSuperAdmin();
    }

    /**
     * The current Chalmersspex's show dates, for the cross-revival timeline: super admins and observers read them.
     */
    public boolean canReadSpexCalendar(final Caller caller) {
        return seesEverything(caller);
    }

    public boolean canManageSpexCalendar(final Caller caller) {
        return caller.isSuperAdmin();
    }

    /**
     * Writing news, and reading drafts and withdrawn news. Everyone with access reads what is published.
     */
    public boolean canManageNews(final Caller caller) {
        return caller.isSuperAdmin();
    }

    /**
     * Reading a revival's participants, their responses and contact details: super admins, and its directeurs and
     * steering group while they can see it. Observers do not.
     */
    public boolean canReadParticipants(final Caller caller, final Revival revival) {
        return caller.isSuperAdmin() || holdsRevivalRole(caller, revival);
    }

    /**
     * Adding, changing, inviting and removing participants: its directeurs and steering group, while they can see it
     * and it is not archived.
     */
    public boolean canWriteParticipants(final Caller caller, final Revival revival) {
        return holdsRevivalRole(caller, revival) && !isArchived(revival);
    }

    /**
     * Participants' dietary needs and allergies: a directeur, or a steering group member with {@value #CATERING}, while
     * they can see it. Nobody else, super admins included.
     */
    public boolean canReadDietary(final Caller caller, final Revival revival) {
        if (!holdsRevivalRole(caller, revival)) {
            return false;
        }

        final RevivalRoleAssignment assignment = caller.roleIn(revival.getId());

        return assignment != null && (assignment.holdsAll() || assignment.capabilities().contains(CATERING));
    }

    /**
     * The caller's own participation: a participant or helper of a revival they can see, which is then {@code OPEN}.
     */
    public boolean canParticipate(final Caller caller, final Revival revival) {
        return isVisibleParticipant(caller, revival, RevivalRole.PARTICIPANT) || isVisibleParticipant(caller, revival, RevivalRole.HELPER);
    }

    /**
     * Reading a revival's shift slots and who holds them: super admins, and its directeurs and steering group while
     * they can see it. Observers do not; participants see open slots and helpers their own shifts on their own page.
     */
    public boolean canReadStaffing(final Caller caller, final Revival revival) {
        return caller.isSuperAdmin() || holdsRevivalRole(caller, revival);
    }

    /**
     * Planning shifts and assigning people: a directeur, or a steering group member with {@value #STAFFING}, while they
     * can see it and it is not archived.
     */
    public boolean canWriteStaffing(final Caller caller, final Revival revival) {
        return !isArchived(revival) && holdsCapability(caller, revival, STAFFING);
    }

    /**
     * Reading a revival's mailings and its mailing plan: super admins, and its directeurs and steering group while they
     * can see it. Observers do not.
     */
    public boolean canReadMailings(final Caller caller, final Revival revival) {
        return caller.isSuperAdmin() || holdsRevivalRole(caller, revival);
    }

    /**
     * Drafting mailings: its directeurs and steering group, while they can see it and it is not archived.
     */
    public boolean canDraftMailings(final Caller caller, final Revival revival) {
        return holdsRevivalRole(caller, revival) && !isArchived(revival);
    }

    /**
     * Sending a mailing, and sending it again to those it failed for: a directeur, or a steering group member with
     * {@value #COMMS}, while they can see it and it is not archived.
     */
    public boolean canSendMailings(final Caller caller, final Revival revival) {
        return !isArchived(revival) && holdsCapability(caller, revival, COMMS);
    }

    /**
     * The mailing plan's templates: read by whoever reads the guide, as checklist templates.
     */
    public boolean canReadMailingTemplates(final Caller caller, final Collection<Revival> callerRevivals) {
        return canReadGuide(caller, callerRevivals);
    }

    public boolean canManageMailingTemplates(final Caller caller) {
        return caller.isSuperAdmin();
    }

    /**
     * Reading every material of a revival, and its inventory: super admins, and its directeurs and steering group while
     * they can see it. Observers and helpers do not.
     */
    public boolean canReadAllMaterials(final Caller caller, final Revival revival) {
        return caller.isSuperAdmin() || holdsRevivalRole(caller, revival);
    }

    /**
     * Reading a revival's materials at all: whoever reads them all, and its participants while they can see it, who
     * read those meant for them ({@link #canSeeMaterial}).
     */
    public boolean canReadMaterials(final Caller caller, final Revival revival) {
        return canReadAllMaterials(caller, revival) || isVisibleParticipant(caller, revival, RevivalRole.PARTICIPANT);
    }

    /**
     * One material: all for those who read them all; for a participant, one for every participant, or for the positions
     * that include {@code participantPosition}, their position's Spexregister task id. Removed ones are left
     * out by the caller.
     */
    public boolean canSeeMaterial(final Caller caller, final Revival revival, final MaterialVisibility visibility, final Collection<String> positions,
                                  final @Nullable String participantPosition) {
        if (canReadAllMaterials(caller, revival)) {
            return true;
        }
        if (!isVisibleParticipant(caller, revival, RevivalRole.PARTICIPANT)) {
            return false;
        }
        return switch (visibility) {
            case STEERING_GROUP -> false;
            case PARTICIPANTS -> true;
            case POSITIONS -> participantPosition != null && positions.contains(participantPosition);
        };
    }

    /**
     * Changing a revival's materials and inventory: its directeurs and steering group, while they can see it and it is
     * not archived. Super admins only read.
     */
    public boolean canWriteMaterials(final Caller caller, final Revival revival) {
        return holdsRevivalRole(caller, revival) && !isArchived(revival);
    }

    public boolean canReadInventory(final Caller caller, final Revival revival) {
        return canReadAllMaterials(caller, revival);
    }

    public boolean canWriteInventory(final Caller caller, final Revival revival) {
        return canWriteMaterials(caller, revival);
    }

    /**
     * Writing the revival's participation to Spexregister: a directeur who can see it while
     * it is {@code CLOSING} or {@code SETTLING}, or a super admin in any status from {@code ORGANISING} on, archived
     * included; never once it is anonymized, as nobody is left to write.
     */
    public boolean canSync(final Caller caller, final Revival revival) {
        if (revival.isAnonymized()) {
            return false;
        }
        if (caller.isSuperAdmin()) {
            return !BEFORE_WORK.contains(revival.getStatus());
        }
        return isVisibleDirecteur(caller, revival) && DIRECTEUR_CLOSING.contains(revival.getStatus());
    }

    /**
     * Reading the sync's preview and runs: whoever may start it, and the steering group while they can see the revival.
     */
    public boolean canReadSync(final Caller caller, final Revival revival) {
        return canSync(caller, revival) || holdsRevivalRole(caller, revival);
    }

    /**
     * Marking whose FGV membership the revival pays: as {@link #canSync}.
     */
    public boolean canSetMembership(final Caller caller, final Revival revival) {
        return canSync(caller, revival);
    }

    /**
     * Removing the people from the revival for good: a directeur who can see it while it is
     * {@code SETTLING}, or a super admin while it is {@code SETTLING}, {@code ARCHIVED} or {@code REJECTED}; only once.
     */
    public boolean canAnonymize(final Caller caller, final Revival revival) {
        if (revival.isAnonymized()) {
            return false;
        }
        if (caller.isSuperAdmin()) {
            return SUPER_ADMIN_ANONYMIZE.contains(revival.getStatus());
        }
        return isVisibleDirecteur(caller, revival) && revival.getStatus() == SETTLING;
    }

    public RevivalPermissionsDto permissions(final Caller caller, final Revival revival) {
        return permissions(caller, revival, List.of());
    }

    public RevivalPermissionsDto permissions(final Caller caller, final Revival revival, final List<GuideVersionSummaryDto> published) {
        return new RevivalPermissionsDto(
                revival.getId(),
                canEditCore(caller, revival),
                canEditDetails(caller, revival),
                allowedStatuses(caller, revival),
                canManageDirecteurs(caller, revival),
                canManageSteeringGroup(caller, revival),
                canSuggest(caller, revival),
                guideVersionsAllowed(caller, revival, published),
                canWriteRevivalWork(caller, revival),
                canWaiveRequirement(caller, revival),
                canDeleteRevival(caller),
                canSubscribeCalendar(caller, revival),
                canReadBudget(caller, revival),
                canWriteBudget(caller, revival),
                canReadParticipants(caller, revival),
                canWriteParticipants(caller, revival),
                canReadDietary(caller, revival),
                isParticipant(caller, revival),
                canReadRevivalWork(caller, revival),
                canReadStaffing(caller, revival),
                canWriteStaffing(caller, revival),
                canReadMailings(caller, revival),
                canDraftMailings(caller, revival),
                canSendMailings(caller, revival),
                canWriteMaterials(caller, revival),
                canSync(caller, revival),
                canAnonymize(caller, revival),
                canSetMembership(caller, revival),
                canReadAllMaterials(caller, revival)
        );
    }

    /**
     * Refuses a revival that is missing or not visible with the same error, so its existence is not revealed.
     */
    public Revival requireVisible(final Caller caller, final @Nullable Revival revival, final Object id) {
        if (revival == null || !canSee(caller, revival)) {
            throw new ResourceNotFoundException(Revival.class, id);
        }
        return revival;
    }

    public void requireCreateRevival(final Caller caller) {
        require(canCreateRevival(caller));
    }

    public void requireDeleteRevival(final Caller caller) {
        require(canDeleteRevival(caller));
    }

    public void requireEditCore(final Caller caller, final Revival revival) {
        require(canEditCore(caller, revival));
    }

    public void requireEditDetails(final Caller caller, final Revival revival) {
        require(canEditDetails(caller, revival));
    }

    public void requireStatusChange(final Caller caller, final Revival revival, final RevivalStatus to) {
        require(allowedStatuses(caller, revival).contains(to));
    }

    public void requireManageMembers(final Caller caller, final Revival revival, final RevivalRole role) {
        require(canManageMembers(caller, revival, role));
    }

    public void requireSearchMemberCandidates(final Caller caller, final Revival revival) {
        require(canSearchMemberCandidates(caller, revival));
    }

    public void requireSpexSearch(final Caller caller) {
        require(canSearchSpex(caller));
    }

    public void requireReadGuide(final Caller caller, final Collection<Revival> callerRevivals) {
        require(canReadGuide(caller, callerRevivals));
    }

    public void requireManageGuide(final Caller caller) {
        require(canManageGuide(caller));
    }

    public void requireSuggest(final Caller caller, final Revival revival) {
        require(canSuggest(caller, revival));
    }

    public void requireDecideSuggestion(final Caller caller) {
        require(canDecideSuggestion(caller));
    }

    public void requireWithdrawSuggestion(final Caller caller, final Revival revival, final Long authorId) {
        require(canWithdrawSuggestion(caller, revival, authorId));
    }

    public void requireGuideVersionChange(final Caller caller, final Revival revival,
                                          final @Nullable Instant fromPublishedAt, final Instant toPublishedAt) {
        require(canChangeGuideVersion(caller, revival, fromPublishedAt, toPublishedAt));
    }

    public void requireReadRevivalWork(final Caller caller, final Revival revival) {
        require(canReadRevivalWork(caller, revival));
    }

    public void requireReadEvents(final Caller caller, final Revival revival) {
        require(canReadEvents(caller, revival));
    }

    public void requireReadParticipants(final Caller caller, final Revival revival) {
        require(canReadParticipants(caller, revival));
    }

    public void requireWriteParticipants(final Caller caller, final Revival revival) {
        require(canWriteParticipants(caller, revival));
    }

    public void requireWriteRevivalWork(final Caller caller, final Revival revival) {
        require(canWriteRevivalWork(caller, revival));
    }

    public void requireWaiveRequirement(final Caller caller, final Revival revival) {
        require(canWaiveRequirement(caller, revival));
    }

    public void requireReadChecklistTemplates(final Caller caller, final Collection<Revival> callerRevivals) {
        require(canReadChecklistTemplates(caller, callerRevivals));
    }

    public void requireManageChecklistTemplates(final Caller caller) {
        require(canManageChecklistTemplates(caller));
    }

    public void requireManageCatalogs(final Caller caller) {
        require(canManageCatalogs(caller));
    }

    public void requireManageEventTypes(final Caller caller) {
        require(canManageEventTypes(caller));
    }

    public void requireSubscribeCalendar(final Caller caller, final Revival revival) {
        require(canSubscribeCalendar(caller, revival));
    }

    public void requireReadBudget(final Caller caller, final Revival revival) {
        require(canReadBudget(caller, revival));
    }

    public void requireWriteBudget(final Caller caller, final Revival revival) {
        require(canWriteBudget(caller, revival));
    }

    public void requireReadReferenceFigures(final Caller caller, final Collection<Revival> callerRevivals) {
        require(canReadReferenceFigures(caller, callerRevivals));
    }

    public void requireManageReferenceFigures(final Caller caller) {
        require(canManageReferenceFigures(caller));
    }

    public void requireReadSpexCalendar(final Caller caller) {
        require(canReadSpexCalendar(caller));
    }

    public void requireManageSpexCalendar(final Caller caller) {
        require(canManageSpexCalendar(caller));
    }

    public void requireManageNews(final Caller caller) {
        require(canManageNews(caller));
    }

    public void requireReadStaffing(final Caller caller, final Revival revival) {
        require(canReadStaffing(caller, revival));
    }

    public void requireWriteStaffing(final Caller caller, final Revival revival) {
        require(canWriteStaffing(caller, revival));
    }

    public void requireReadMailings(final Caller caller, final Revival revival) {
        require(canReadMailings(caller, revival));
    }

    public void requireDraftMailings(final Caller caller, final Revival revival) {
        require(canDraftMailings(caller, revival));
    }

    public void requireSendMailings(final Caller caller, final Revival revival) {
        require(canSendMailings(caller, revival));
    }

    public void requireReadMailingTemplates(final Caller caller, final Collection<Revival> callerRevivals) {
        require(canReadMailingTemplates(caller, callerRevivals));
    }

    public void requireManageMailingTemplates(final Caller caller) {
        require(canManageMailingTemplates(caller));
    }

    public void requireReadMaterials(final Caller caller, final Revival revival) {
        require(canReadMaterials(caller, revival));
    }

    public void requireReadAllMaterials(final Caller caller, final Revival revival) {
        require(canReadAllMaterials(caller, revival));
    }

    public void requireWriteMaterials(final Caller caller, final Revival revival) {
        require(canWriteMaterials(caller, revival));
    }

    public void requireReadInventory(final Caller caller, final Revival revival) {
        require(canReadInventory(caller, revival));
    }

    public void requireWriteInventory(final Caller caller, final Revival revival) {
        require(canWriteInventory(caller, revival));
    }

    public void requireSync(final Caller caller, final Revival revival) {
        require(canSync(caller, revival));
    }

    public void requireReadSync(final Caller caller, final Revival revival) {
        require(canReadSync(caller, revival));
    }

    public void requireSetMembership(final Caller caller, final Revival revival) {
        require(canSetMembership(caller, revival));
    }

    public void requireAnonymize(final Caller caller, final Revival revival) {
        require(canAnonymize(caller, revival));
    }
}
