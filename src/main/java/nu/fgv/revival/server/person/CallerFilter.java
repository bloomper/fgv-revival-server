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

import io.micrometer.context.ContextRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the caller once per request, after authentication and before any transaction, as Spexregister knows its
 * caller from the token alone. Services call {@link CallerService#current()} inside their transactions, and resolving
 * there would hold one connection while waiting for another, which exhausts the pool under concurrent requests.
 *
 * @author Anders Jacobsson
 * @since 0.3
 */
@Component
public class CallerFilter extends OncePerRequestFilter {

    private final CallerService callerService;

    public CallerFilter(final CallerService callerService) {
        this.callerService = callerService;
        ContextRegistry.getInstance().registerThreadLocalAccessor(new CallerContextAccessor());
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        callerService.resolveEarly().ifPresent(CallerContext::set);

        try {
            chain.doFilter(request, response);
        } finally {
            CallerContext.clear();
        }
    }
}
