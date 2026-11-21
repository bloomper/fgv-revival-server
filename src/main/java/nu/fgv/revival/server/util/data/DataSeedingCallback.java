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

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Imports sample data after the migrations when {@code revival.data.import-sample-data} is set. The sample data
 * replaces everything, so it is refused in the {@code production} profile.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class DataSeedingCallback implements Callback {

    private final boolean importSampleData;
    private final SampleDataImporter sampleDataImporter;
    private final Environment environment;

    public DataSeedingCallback(@Value("${revival.data.import-sample-data:false}") final boolean importSampleData,
                               final SampleDataImporter sampleDataImporter,
                               final Environment environment) {
        this.importSampleData = importSampleData;
        this.sampleDataImporter = sampleDataImporter;
        this.environment = environment;
    }

    @Override
    public boolean supports(final Event event, final Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(final Event event, final Context context) {
        return false;
    }

    @Override
    public void handle(final Event event, final Context context) {
        if (importSampleData) {
            if (environment.acceptsProfiles(Profiles.of("production"))) {
                throw new IllegalStateException("Refusing to import sample data in the production profile");
            }
            sampleDataImporter.importSampleData(context.getConnection());
        }
    }

    @Override
    public String getCallbackName() {
        return DataSeedingCallback.class.getSimpleName();
    }
}
