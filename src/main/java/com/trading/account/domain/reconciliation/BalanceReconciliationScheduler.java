package com.trading.account.domain.reconciliation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Component
@ConditionalOnProperty(name = "reconciliation.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class BalanceReconciliationScheduler {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final JobLauncher launcher;
    private final Job job;
    private final Clock clock;
    private final Counter launchFailures;

    @Autowired
    public BalanceReconciliationScheduler(JobLauncher launcher, Job balanceReconciliationJob, MeterRegistry registry) {
        this(launcher, balanceReconciliationJob, registry, Clock.systemUTC());
    }

    BalanceReconciliationScheduler(JobLauncher launcher, Job job, MeterRegistry registry, Clock clock) {
        this.launcher = launcher;
        this.job = job;
        this.clock = clock;
        this.launchFailures = registry.counter("reconciliation.launch.failures");
    }

    // Polling also catches application startup after 01:00 and retries FAILED executions.
    @Scheduled(fixedDelayString = "${reconciliation.scheduler.poll-delay-ms:300000}")
    public void runDailyReconciliation() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZONE);
        if (now.toLocalTime().isBefore(LocalTime.of(1, 0))) return;
        try {
            // A stable date gives every replica the same JobInstance. Batch protects running/completed instances.
            JobExecution execution = launcher.run(job, new JobParametersBuilder()
                    .addLocalDate("scheduleDate", now.toLocalDate()).toJobParameters());
            if (execution.getStatus() != BatchStatus.COMPLETED && !execution.isRunning()) {
                log.error("Current reconciliation scan failed: scheduleDate={}, runId={}, status={}",
                        now.toLocalDate(), execution.getId(), execution.getStatus());
            }
        } catch (JobInstanceAlreadyCompleteException | JobExecutionAlreadyRunningException normalOverlap) {
            // Never time out or reset STARTED: an old timestamp cannot prove the worker is dead.
            log.debug("Current reconciliation scan already running or complete: scheduleDate={}", now.toLocalDate());
        } catch (Exception failure) {
            launchFailures.increment();
            // Exception messages/stack traces may contain SQL values; log only a bounded error category.
            log.error("Current reconciliation launch failed: scheduleDate={}, errorType={}",
                    now.toLocalDate(), failure.getClass().getSimpleName());
        }
    }
}
