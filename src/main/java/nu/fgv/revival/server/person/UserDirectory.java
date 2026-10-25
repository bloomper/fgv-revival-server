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

package nu.fgv.revival.server.person;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nu.fgv.revival.server.util.error.DirectoryUnavailableException;
import nu.fgv.revival.server.util.security.SecurityUtil;
import org.jspecify.annotations.Nullable;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.util.StringUtils.hasText;

/**
 * The users of the FGV realm, through Keycloak's admin API as {@code fgv-admin}. It needs the {@code realm-management}
 * roles {@code query-users} and {@code view-users} to read users, {@code view-clients} to find {@code fgv-revival}'s
 * roles and {@code manage-users} to create and invite users. It never grants a role: access to Nyuppsättning stays a
 * super admin's decision in Keycloak. Every failure, unreachable or refused, is a
 * {@link DirectoryUnavailableException}, logged without personal data.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class UserDirectory {

    /**
     * How long an invitation link is valid.
     */
    public static final Duration INVITATION_LIFESPAN = Duration.ofDays(7);

    static final List<String> INVITATION_ACTIONS = List.of("VERIFY_EMAIL", "UPDATE_PASSWORD");
    private static final String ROLE_USER = "USER";
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    private static final int MAX_SUPER_ADMINS = 100;

    private final Keycloak keycloak;
    private final String realm;
    private final String consoleUrl;
    private volatile @Nullable String clientUuid;

    public UserDirectory(final Keycloak keycloak,
                         @Value("${fgv-revival.keycloak.realm}") final String realm,
                         @Value("${fgv-revival.keycloak.admin.console-url}") final String consoleUrl) {
        this.keycloak = keycloak;
        this.realm = realm;
        this.consoleUrl = consoleUrl.endsWith("/") ? consoleUrl.substring(0, consoleUrl.length() - 1) : consoleUrl;
    }

    private static @Nullable String displayName(final @Nullable String firstName, final @Nullable String lastName) {
        final String name = Stream.of(firstName, lastName).filter(StringUtils::hasText).map(String::trim).collect(Collectors.joining(" "));

        return name.isEmpty() ? null : name;
    }

    private static @Nullable Locale locale(final UserRepresentation user) {
        final String locale = user.firstAttribute("locale");

        return hasText(locale) ? Locale.forLanguageTag(locale) : null;
    }

    /**
     * Runs an admin API call; unreachable, refused and unexpected answers all mean the directory is unavailable.
     */
    private static <T extends @Nullable Object> T call(final Supplier<T> action) {
        try {
            return action.get();
        } catch (final ProcessingException | WebApplicationException | IllegalStateException e) {
            throw new DirectoryUnavailableException(e);
        }
    }

    /**
     * Enabled users with an email address whose name, username or email starts with the query's words, at most
     * {@code max}.
     */
    public List<DirectoryUser> search(final String query, final int max) {
        final String search = query.replaceAll("[*\"]", " ").trim();

        return call(() -> realm().users().search(search, 0, max * 3, true).stream()
                .filter(user -> Boolean.TRUE.equals(user.isEnabled()) && hasText(user.getEmail()))
                .limit(max)
                .map(this::toDirectoryUser)
                .toList());
    }

    /**
     * The user with exactly this email address, if any.
     */
    public Optional<DirectoryUser> findByEmail(final String email) {
        return call(() -> realm().users().searchByEmail(email, true).stream()
                .filter(user -> email.equalsIgnoreCase(user.getEmail()))
                .findFirst()
                .map(this::toDirectoryUser));
    }

    /**
     * Creates an enabled user with the email as username, unverified and without any role, then asks Keycloak to email
     * them a link to verify the address and choose a password. A failed email still leaves the user created.
     */
    public Invitation invite(final String email, final @Nullable String firstName, final @Nullable String lastName) {
        final UserRepresentation user = new UserRepresentation();

        user.setUsername(email);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEnabled(true);
        user.setEmailVerified(false);

        final String id = call(() -> {
            try (final Response response = realm().users().create(user)) {
                if (response.getStatus() != Response.Status.CREATED.getStatusCode() || response.getLocation() == null) {
                    throw new WebApplicationException("Keycloak did not create the user", response.getStatus());
                }

                final String path = response.getLocation().getPath();

                return path.substring(path.lastIndexOf('/') + 1);
            }
        });
        final DirectoryUser created = new DirectoryUser(id, email, displayName(firstName, lastName), false, null);

        try {
            call(() -> {
                realm().users().get(id).executeActionsEmail(SecurityUtil.CLIENT_ID, null, (int) INVITATION_LIFESPAN.toSeconds(), INVITATION_ACTIONS);
                return null;
            });
            return new Invitation(created, true);
        } catch (final DirectoryUnavailableException e) {
            return new Invitation(created, false);
        }
    }

    /**
     * Enabled users holding {@code fgv-revival}'s {@code SUPER_ADMIN} directly, with an email address.
     */
    public List<DirectoryUser> superAdmins() {
        return call(() -> realm().clients().get(clientUuid()).roles().get(ROLE_SUPER_ADMIN).getUserMembers(false, 0, MAX_SUPER_ADMINS).stream()
                .filter(user -> Boolean.TRUE.equals(user.isEnabled()) && hasText(user.getEmail()))
                .map(user -> new DirectoryUser(user.getId(), user.getEmail(), displayName(user.getFirstName(), user.getLastName()), true, locale(user)))
                .toList());
    }

    /**
     * Where a super admin grants roles to the user in Keycloak's admin console.
     */
    public String roleMappingUrl(final String userId) {
        return "%s/admin/master/console/#/%s/users/%s/role-mapping".formatted(consoleUrl, realm, userId);
    }

    private DirectoryUser toDirectoryUser(final UserRepresentation user) {
        final boolean hasAccess = realm().users().get(user.getId()).roles().clientLevel(clientUuid()).listEffective(true).stream()
                .map(RoleRepresentation::getName)
                .anyMatch(ROLE_USER::equals);

        return new DirectoryUser(user.getId(), user.getEmail(), displayName(user.getFirstName(), user.getLastName()), hasAccess, locale(user));
    }

    private String clientUuid() {
        String uuid = clientUuid;

        if (uuid == null) {
            final List<ClientRepresentation> clients = realm().clients().findByClientId(SecurityUtil.CLIENT_ID);

            if (clients.isEmpty()) {
                throw new IllegalStateException("The realm has no client " + SecurityUtil.CLIENT_ID);
            }
            uuid = clients.getFirst().getId();
            clientUuid = uuid;
        }
        return uuid;
    }

    private RealmResource realm() {
        return keycloak.realm(realm);
    }

    /**
     * A user of the realm. {@code hasAccess} means they hold {@code fgv-revival}'s {@code USER}, directly or through a
     * group or composite role, so they can log in to Nyuppsättning.
     */
    public record DirectoryUser(String id, String email, @Nullable String displayName, boolean hasAccess,
                                @Nullable Locale locale) {
    }

    /**
     * A user the application created, and whether Keycloak sent them the invitation.
     */
    public record Invitation(DirectoryUser user, boolean emailed) {
    }
}
