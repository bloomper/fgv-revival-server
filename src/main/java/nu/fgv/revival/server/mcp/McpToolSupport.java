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

package nu.fgv.revival.server.mcp;

import nu.fgv.revival.server.audit.AuditContext;
import nu.fgv.revival.server.audit.AuditSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shared by the MCP tools, trimmed from Spexregister's: the audit stamp and the result record.
 *
 * @author Anders Jacobsson
 * @since 0.1
 */
@Component
public class McpToolSupport {

    /**
     * Records {@link AuditSource#MCP} for anything the tool changes (research R10).
     */
    void audit(final String tool) {
        AuditContext.stamp(AuditContext.currentOrSystem()
                .withSource(AuditSource.MCP)
                .withOperation("MCP %s".formatted(tool)));
    }

    public record ListResult<T>(List<T> items) {
    }
}
