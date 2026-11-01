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

package nu.fgv.revival.server;

import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SqlTypeValue;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * A row's two ids, so a test can hand the same value to both sides: the APIs get the external id (as JSON, in a URL),
 * SQL gets the internal one (as a JDBC parameter).
 *
 * @param id   the internal id that rows refer to each other by
 * @param uuid the external id that the APIs speak
 * @author Anders Jacobsson
 * @since 1.0
 */
public record EntityId(long id, UUID uuid) implements SqlTypeValue {

    /**
     * @return the external id, as the APIs write it
     */
    @JsonValue
    public String external() {
        return uuid.toString();
    }

    @Override
    public void setTypeValue(final PreparedStatement ps, final int paramIndex, final int sqlType, final @Nullable String typeName) throws SQLException {
        ps.setLong(paramIndex, id);
    }

    @Override
    public String toString() {
        return external();
    }
}
