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

import io.micrometer.context.ThreadLocalAccessor;
import org.jspecify.annotations.Nullable;

/**
 * Carries the {@link AuditContext} onto the threads Spring for GraphQL runs data fetchers on, so revisions made from
 * GraphQL record {@link AuditSource#WEB}. Registered by {@link AuditContextFilter}.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
public class AuditContextAccessor implements ThreadLocalAccessor<AuditContext.Origin> {

    @Override
    public Object key() {
        return AuditContextAccessor.class.getName();
    }

    @Override
    public AuditContext.@Nullable Origin getValue() {
        return AuditContext.current();
    }

    @Override
    public void setValue(final AuditContext.Origin origin) {
        AuditContext.set(origin);
    }

    @Override
    public void setValue() {
        AuditContext.clear();
    }

}
