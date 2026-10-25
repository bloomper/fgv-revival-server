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
package nu.fgv.revival.server.material;

import nu.fgv.revival.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules for links: a link is an absolute https URL with a host and without a user name or
 * password, and belongs to the first active link service, in list order, whose hosts include its host or a parent
 * domain of it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class Links {

    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern HOST = Pattern.compile("(?=.{1,253}$)" + LABEL + "(?:\\." + LABEL + ")+");
    private static final String HTTPS = "https";

    private Links() {
    }

    /**
     * The trimmed link, refused unless it is an absolute https URL of at most {@code maxLength} characters with a valid
     * host name and no user name or password.
     */
    public static String requireHttps(final @Nullable String url, final int maxLength) {
        final String trimmed = url == null ? "" : url.strip();

        if (trimmed.isEmpty()) {
            throw new BadRequestException("error.link.invalid");
        }
        if (trimmed.length() > maxLength) {
            throw new BadRequestException("error.link.tooLong", maxLength);
        }
        if (trimmed.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new BadRequestException("error.link.invalid");
        }

        final URI uri = parse(trimmed);

        if (!HTTPS.equalsIgnoreCase(uri.getScheme())) {
            throw new BadRequestException("error.link.https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new BadRequestException("error.link.credentials");
        }
        if (uri.getHost() == null || !HOST.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches()) {
            throw new BadRequestException("error.link.invalid");
        }
        return trimmed;
    }

    /**
     * The lower-cased host of a link {@link #requireHttps} accepted.
     */
    public static String host(final String url) {
        return parse(url).getHost().toLowerCase(Locale.ROOT);
    }

    /**
     * A service's host as stored: trimmed and lower-cased, a host name of at least two labels such as
     * {@code drive.google.com}.
     */
    public static String validHost(final @Nullable String host) {
        final String folded = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);

        if (!HOST.matcher(folded).matches()) {
            throw new BadRequestException("error.linkService.host.invalid", host == null ? "" : host.strip());
        }
        return folded;
    }

    /**
     * Whether a link's host is the service's host or a subdomain of it.
     */
    public static boolean belongs(final String host, final String serviceHost) {
        return host.equals(serviceHost) || host.endsWith("." + serviceHost);
    }

    /**
     * The first active service, in the list's order, that the host belongs to.
     */
    public static Optional<LinkService> recognise(final String host, final List<LinkService> services) {
        return services.stream()
                .filter(service -> !service.isRetired())
                .filter(service -> service.getHosts().stream().anyMatch(serviceHost -> belongs(host, serviceHost)))
                .findFirst();
    }

    private static URI parse(final String url) {
        try {
            return new URI(url);
        } catch (final URISyntaxException _) {
            throw new BadRequestException("error.link.invalid");
        }
    }
}
