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

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
class MaintenanceJobsTest {

    @Test
    void recordsASuccessfulRun() {
        final AtomicInteger runs = new AtomicInteger();
        final MaintenanceJobs jobs = new MaintenanceJobs(Map.of(MaintenanceJob.MAILING_REMINDERS, runs::incrementAndGet));

        assertThat(jobs.lastRun(MaintenanceJob.MAILING_REMINDERS)).isEmpty();

        jobs.mailingReminders();

        assertThat(runs).hasValue(1);
        assertThat(jobs.isRunning(MaintenanceJob.MAILING_REMINDERS)).isFalse();
        assertThat(jobs.lastRun(MaintenanceJob.MAILING_REMINDERS)).hasValueSatisfying(run -> {
            assertThat(run.finishedAt()).isNotNull();
            assertThat(run.succeeded()).isTrue();
        });
    }

    @Test
    void recordsAFailedRunWithoutThrowing() {
        final MaintenanceJobs jobs = new MaintenanceJobs(Map.of(MaintenanceJob.PARTICIPANT_RETENTION, () -> {
            throw new IllegalStateException("boom");
        }));

        jobs.participantRetention();

        assertThat(jobs.lastRun(MaintenanceJob.PARTICIPANT_RETENTION)).hasValueSatisfying(run -> assertThat(run.succeeded()).isFalse());
    }

    @Test
    void doesNotStartAJobThatIsRunning() throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean secondRan = new AtomicBoolean();
        final MaintenanceJobs jobs = new MaintenanceJobs(Map.of(MaintenanceJob.MAILING_REMINDERS, () -> {
            started.countDown();
            try {
                release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        final Thread first = Thread.ofVirtual().start(() -> jobs.run(MaintenanceJob.MAILING_REMINDERS));

        started.await();
        assertThat(jobs.isRunning(MaintenanceJob.MAILING_REMINDERS)).isTrue();
        assertThat(jobs.lastRun(MaintenanceJob.MAILING_REMINDERS)).hasValueSatisfying(run -> assertThat(run.succeeded()).isNull());
        assertThat(jobs.run(MaintenanceJob.MAILING_REMINDERS)).isFalse();
        assertThat(secondRan).isFalse();

        release.countDown();
        first.join();

        assertThat(jobs.isRunning(MaintenanceJob.MAILING_REMINDERS)).isFalse();
    }
}
