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
import nu.fgv.revival.server.person.Caller;
import nu.fgv.revival.server.revival.Revival;
import nu.fgv.revival.server.revival.RevivalStatus;
import nu.fgv.revival.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
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
 * Every authorization rule in M1, in one place (constitution III, research R3). Anything not permitted here is refused.
 * The rules are the visibility table in data-model.md and the operation rules in contracts/README.md.
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

    public RevivalPermissionsDto permissions(final Caller caller, final Revival revival) {
        return new RevivalPermissionsDto(
                canEditCore(caller, revival),
                canEditDetails(caller, revival),
                allowedStatuses(caller, revival),
                canManageDirecteurs(caller, revival),
                canManageSteeringGroup(caller, revival)
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

    private static void require(final boolean permitted) {
        if (!permitted) {
            throw new AccessDeniedException("Not permitted");
        }
    }
}
