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


package nu.fgv.revival.server.maintenance;

import nu.fgv.revival.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
class MaintenanceGraphqlApiIntegrationTest extends AbstractIntegrationTest {

    private static final String JOBS = "{ maintenanceJobs { job schedule running lastStartedAt lastFinishedAt lastSucceeded } }";
    private static final String RUN = "mutation($job: MaintenanceJob!) { maintenanceJobRun(job: $job) }";

    @Test
    void theSuperAdminSeesTheJobs() {
        execute(SUPER_ADMIN, JOBS, Map.of())
                .path("maintenanceJobs[*].job").entityList(String.class).containsExactly("PARTICIPANT_RETENTION", "MAILING_REMINDERS")
                .path("maintenanceJobs[0].schedule").entity(String.class).isEqualTo("0 15 3 * * *");
    }

    @Test
    void theSuperAdminRunsAJob() {
        execute(SUPER_ADMIN, RUN, Map.of("job", "MAILING_REMINDERS"))
                .path("maintenanceJobRun").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void nobodyElseSeesOrRunsTheJobs() {
        for (final String user : new String[]{OBSERVER, DIRECTEUR, PLAIN_USER}) {
            expectError(execute(user, JOBS, Map.of()), "FORBIDDEN");
            expectError(execute(user, RUN, Map.of("job", "PARTICIPANT_RETENTION")), "FORBIDDEN");
        }
    }
}
