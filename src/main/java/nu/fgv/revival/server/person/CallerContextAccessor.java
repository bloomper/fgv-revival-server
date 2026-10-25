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

import io.micrometer.context.ThreadLocalAccessor;
import org.jspecify.annotations.Nullable;

/**
 * Carries {@link CallerContext} to the threads GraphQL runs its controllers on.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public class CallerContextAccessor implements ThreadLocalAccessor<Caller> {

    @Override
    public Object key() {
        return CallerContextAccessor.class.getName();
    }

    @Override
    public @Nullable Caller getValue() {
        return CallerContext.current();
    }

    @Override
    public void setValue(final Caller caller) {
        CallerContext.set(caller);
    }

    @Override
    public void setValue() {
        CallerContext.clear();
    }
}
