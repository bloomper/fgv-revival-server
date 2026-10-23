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
import static nu.fgv.revival.server.revival.RevivalStatus.SETTLING;

/**
 * Every authorization rule, in one place (constitution III, M1 research R3). Anything not permitted here is refused.
 * The rules are the visibility table in M1's data-model.md and the operation rules in M1's, M2's and M3's
 * contracts/README.md.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Component
public class AccessPolicy {

    private static final Map<RevivalRole, Set<RevivalStatus>> VISIBLE_STATUSES = Map.of(
            RevivalRole.DIRECTEUR, EnumSet.of(APPROVED, ORGANISING, OPEN, CLOSING, SETTLING),
            RevivalRole.STEERING_GROUP, EnumSet.of(ORGANISING, OPEN, CLOSING),
            RevivalRole.PARTICIPANT, EnumSet.noneOf(RevivalStatus.class),
            RevivalRole.HELPER, EnumSet.noneOf(RevivalStatus.class)
    );

    /**
     * The statuses in which a revival role sees its revival (FR-015). Participants and helpers see nothing in M1.
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

    public boolean canSee(final Caller caller, final Revival revival) {
        if (seesEverything(caller)) {
            return true;
        }

        final RevivalRoleAssignment assignment = caller.roleIn(revival.getId());

        return assignment != null && visibleStatuses(assignment.role()).contains(revival.getStatus());
    }

    /**
     * The same rule as {@link #canSee} as a query predicate, so lists and single lookups agree (SC-002).
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
     * Super admins may set any other status, including an earlier one (FR-013). A directeur may take only the next step
     * (FR-014). Everyone else, nothing.
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

    private static boolean holdsRevivalRole(final Caller caller, final Revival revival) {
        final RevivalRoleAssignment assignment = revival.getId() == null ? null : caller.roleIn(revival.getId());

        return assignment != null
                && (assignment.role() == RevivalRole.DIRECTEUR || assignment.role() == RevivalRole.STEERING_GROUP)
                && visibleStatuses(assignment.role()).contains(revival.getStatus());
    }

    /**
     * Super admins and observers read the guide, and so does anyone who is directeur or steering group of a revival
     * they can see (M2 research R10). {@code callerRevivals} are the caller's revivals as {@link #visibilityPredicate}
     * finds them.
     */
    public boolean canReadGuide(final Caller caller, final Collection<Revival> callerRevivals) {
        return seesEverything(caller) || callerRevivals.stream().anyMatch(revival -> holdsRevivalRole(caller, revival));
    }

    /**
     * Everyone who can see a revival may read its guide.
     */
    public boolean canReadRevivalGuide(final Caller caller, final Revival revival) {
        return canSee(caller, revival);
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
     * a version published after its current one (FR-020). Versions are compared by publish time, never by label.
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
     * The published versions the caller may pin a revival to, other than the one it follows (FR-020).
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
     * Reading a revival's decision log, meetings and checklists: everyone who can see it (M3 research R13).
     */
    public boolean canReadRevivalWork(final Caller caller, final Revival revival) {
        return canSee(caller, revival);
    }

    /**
     * Changing a revival's decision log, meetings and checklists: its directeurs and steering group members, while they
     * can see it and it is not archived (FR-001). Super admins and observers only read (FR-002).
     */
    public boolean canWriteRevivalWork(final Caller caller, final Revival revival) {
        return holdsRevivalRole(caller, revival) && !isArchived(revival);
    }

    /**
     * Importing an Excel log: a directeur who can see the revival, not archived (FR-004). Whether the log is still empty
     * is checked by the import.
     */
    public boolean canImportLog(final Caller caller, final Revival revival) {
        return revival.getId() != null && isVisibleDirecteur(caller, revival) && !isArchived(revival);
    }

    /**
     * Marking a guide requirement not applicable: a directeur only (FR-025).
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
                canDeleteRevival(caller)
        );
    }

    /**
     * Refuses a revival that is missing or not visible with the same error, so its existence is not revealed (FR-017).
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

    public void requireWriteRevivalWork(final Caller caller, final Revival revival) {
        require(canWriteRevivalWork(caller, revival));
    }

    public void requireImportLog(final Caller caller, final Revival revival) {
        require(canImportLog(caller, revival));
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

    private static void require(final boolean permitted) {
        if (!permitted) {
            throw new AccessDeniedException("Not permitted");
        }
    }
}
