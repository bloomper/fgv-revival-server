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

package nu.fgv.revival.server.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stamps {@link AuditSource#WEB} on GraphQL requests, with the reason the web sends in {@code X-Audit-Reason}.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Component
public class AuditContextFilter extends OncePerRequestFilter {

    static final String REASON_HEADER = "X-Audit-Reason";

    private final String graphqlPath;

    public AuditContextFilter(@Value("${spring.graphql.http.path}") final String graphqlPath) {
        this.graphqlPath = graphqlPath;
    }

    private static @Nullable String reasonOf(final HttpServletRequest request) {
        final String reason = request.getHeader(REASON_HEADER);

        if (reason == null) {
            return null;
        }

        try {
            return UriUtils.decode(reason, StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException _) {
            return reason;
        }
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return !graphqlPath.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        AuditContext.set(new AuditContext.Origin(AuditSource.WEB, null, reasonOf(request)));

        try {
            chain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }
}
