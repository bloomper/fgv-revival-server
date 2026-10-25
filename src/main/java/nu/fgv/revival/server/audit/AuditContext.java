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

import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The source, operation and comment of the change being made on the current thread. Adapters set the source, services
 * set the operation.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class AuditContext {

    static final int MAX_OPERATION_LENGTH = 255;
    static final int MAX_COMMENT_LENGTH = 1000;

    private static final ThreadLocal<@Nullable Origin> CURRENT = new ThreadLocal<>();

    private AuditContext() {
    }

    public static @Nullable Origin current() {
        return CURRENT.get();
    }

    public static Origin currentOrSystem() {
        final Origin current = CURRENT.get();

        return current == null ? new Origin(AuditSource.SYSTEM, null, null) : current;
    }

    static void set(final Origin origin) {
        CURRENT.set(origin);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static void stamp(final Origin origin) {
        final Origin previous = CURRENT.get();

        CURRENT.set(origin);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(final int status) {
                    if (previous == null) {
                        CURRENT.remove();
                    } else {
                        CURRENT.set(previous);
                    }
                }
            });
        }
    }

    /**
     * Keeps the source set by the adapter and records the service operation.
     */
    public static void operation(final String operation) {
        stamp(currentOrSystem().withOperation(operation));
    }

    private static @Nullable String truncate(final @Nullable String value, final int max) {
        if (value == null) {
            return null;
        }

        final String trimmed = value.trim();

        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public record Origin(AuditSource source, @Nullable String operation, @Nullable String comment) {

        public Origin {
            operation = truncate(operation, MAX_OPERATION_LENGTH);
            comment = truncate(comment, MAX_COMMENT_LENGTH);
        }

        public Origin withSource(final AuditSource source) {
            return new Origin(source, operation, comment);
        }

        public Origin withOperation(final @Nullable String operation) {
            return new Origin(source, operation, comment);
        }
    }
}
