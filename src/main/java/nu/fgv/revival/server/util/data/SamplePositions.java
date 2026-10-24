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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.revival.server.spex.SpexregisterClient;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Spexregister's positions (tasks) and vocal parts that the sample participants, materials and mailings use: one on
 * stage, one off stage. Each is null when Spexregister has none or cannot be read, and the samples then do without.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
record SamplePositions(@Nullable String actorTaskId, @Nullable String crewTaskId, @Nullable String vocalPartId) {

    static final SamplePositions NONE = new SamplePositions(null, null, null);

    static SamplePositions read(final SpexregisterClient client) {
        try {
            final List<SpexregisterClient.Task> tasks = client.tasks();
            final String actor = tasks.stream().filter(SamplePositions::onStage).map(SpexregisterClient.Task::id).findFirst().orElse(null);
            final String crew = tasks.stream().filter(task -> !onStage(task)).map(SpexregisterClient.Task::id).findFirst().orElse(null);
            final String vocal = client.vocalParts().stream().map(SpexregisterClient.TypeNode::id)
                    .filter(id -> !SpexregisterClient.UNKNOWN_VOCAL.equals(id)).findFirst().orElse(null);

            return new SamplePositions(actor, crew, vocal);
        } catch (final RuntimeException e) {
            log.info("Spexregister's positions could not be read, so the sample data has none: {}", e.getClass().getSimpleName());
            return NONE;
        }
    }

    private static boolean onStage(final SpexregisterClient.Task task) {
        return task.category() != null && Boolean.TRUE.equals(task.category().actorPresent());
    }
}
