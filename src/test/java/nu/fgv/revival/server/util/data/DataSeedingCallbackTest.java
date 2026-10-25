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

package nu.fgv.revival.server.util.data;

import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
class DataSeedingCallbackTest {

    private final SampleDataImporter sampleDataImporter = mock(SampleDataImporter.class);
    private final Context context = mock(Context.class);

    @Test
    void onlySupportsAfterMigrate() {
        final DataSeedingCallback callback = new DataSeedingCallback(true, sampleDataImporter);

        assertThat(callback.supports(Event.AFTER_MIGRATE, context)).isTrue();
        assertThat(callback.supports(Event.BEFORE_MIGRATE, context)).isFalse();
        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, context)).isFalse();
    }

    @Test
    void doesNothingWhenDisabled() {
        new DataSeedingCallback(false, sampleDataImporter).handle(Event.AFTER_MIGRATE, context);

        verifyNoInteractions(sampleDataImporter);
    }

    @Test
    void importsSampleDataWhenEnabled() {
        final Connection connection = mock(Connection.class);

        when(context.getConnection()).thenReturn(connection);

        new DataSeedingCallback(true, sampleDataImporter).handle(Event.AFTER_MIGRATE, context);

        verify(sampleDataImporter).importSampleData(connection);
    }
}
