package com.trading.account.domain.reconciliation;

import com.trading.account.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@SpringBatchTest
class BalanceReconciliationSchedulerIntegrationTest extends AbstractIntegrationTest {
    @Autowired private JobLauncher launcher;
    @Autowired private Job balanceReconciliationJob;
    @Autowired private JobRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReconciliationResults results;
    @Autowired private MeterRegistry meters;

    @Test
    void startupAfterOneAmCatchesUpTodaysMissingScanButDoesNotInventPastClosing() {
        LocalDate date = LocalDate.of(2041, 1, 1);
        schedulerAt(date, 0, 59).runDailyReconciliation();
        assertThat(repository.getLastJobExecution("balanceReconciliationJob", parameters(date))).isNull();
        schedulerAt(date, 7, 30).runDailyReconciliation();
        JobExecution execution = repository.getLastJobExecution("balanceReconciliationJob", parameters(date));
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(repository.getLastJobExecution("balanceReconciliationJob", parameters(date.minusDays(1)))).isNull();
        assertThat(meters.get("reconciliation.last.success.timestamp").gauge().value()).isPositive();
    }

    @Test
    void failedExecutionIsRetriedWithNewRunIdAndCompletedExecutionIsSkipped() throws Exception {
        LocalDate date = LocalDate.of(2041, 1, 2);
        JobExecution failed = repository.createJobExecution("balanceReconciliationJob", parameters(date));
        results.started(failed);
        failed.setStatus(BatchStatus.FAILED);
        failed.setExitStatus(ExitStatus.FAILED);
        failed.setEndTime(LocalDateTime.now());
        repository.update(failed);
        results.finished(failed);

        schedulerAt(date, 3, 0).runDailyReconciliation();
        JobExecution retried = repository.getLastJobExecution("balanceReconciliationJob", parameters(date));
        assertThat(retried.getId()).isNotEqualTo(failed.getId());
        assertThat(retried.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        schedulerAt(date, 3, 5).runDailyReconciliation();
        assertThat(repository.getLastJobExecution("balanceReconciliationJob", parameters(date)).getId()).isEqualTo(retried.getId());
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_run", Long.class)).isEqualTo(2L);
        assertThat(meters.get("reconciliation.runs.failed").gauge().value()).isEqualTo(1);
    }

    @Test
    void liveStartedExecutionIsNeverResetEvenWithAncientTimestamp() throws Exception {
        LocalDate date = LocalDate.of(2041, 1, 3);
        JobExecution live = repository.createJobExecution("balanceReconciliationJob", parameters(date));
        live.setStatus(BatchStatus.STARTED);
        live.setStartTime(LocalDateTime.of(2000, 1, 1, 1, 0));
        repository.update(live);

        schedulerAt(date, 8, 0).runDailyReconciliation();
        JobExecution stillLive = repository.getLastJobExecution("balanceReconciliationJob", parameters(date));
        assertThat(stillLive.getId()).isEqualTo(live.getId());
        assertThat(stillLive.getStatus()).isEqualTo(BatchStatus.STARTED);
        assertThat(jdbc.queryForObject("select count(*) from BATCH_JOB_EXECUTION where JOB_INSTANCE_ID = ?",
                Long.class, live.getJobInstance().getId())).isEqualTo(1L);
    }

    @Test
    void offlineRecoveryChangesOnlyChosenActiveExecutionAndAllowsFreshRetry() throws Exception {
        LocalDate date = LocalDate.of(2041, 1, 4);
        JobExecution orphan = repository.createJobExecution("balanceReconciliationJob", parameters(date));
        orphan.setStatus(BatchStatus.STARTED);
        orphan.setStartTime(LocalDateTime.now());
        repository.update(orphan);
        StepExecution step = orphan.createStepExecution("balanceReconciliationStep");
        step.setStatus(BatchStatus.STARTED);
        step.setStartTime(LocalDateTime.now());
        repository.add(step);
        results.started(orphan);

        assertThat(recover(orphan.getId())).isEqualTo(1);
        assertThat(repository.getLastJobExecution("balanceReconciliationJob", parameters(date)).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jdbc.queryForObject("select STATUS from BATCH_STEP_EXECUTION where STEP_EXECUTION_ID = ?",
                String.class, step.getId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from reconciliation_run where id = ?",
                String.class, orphan.getId())).isEqualTo("FAILED");
        assertThat(recover(orphan.getId())).isZero();
        schedulerAt(date, 2, 0).runDailyReconciliation();
        JobExecution retry = repository.getLastJobExecution("balanceReconciliationJob", parameters(date));
        assertThat(retry.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(retry.getId()).isNotEqualTo(orphan.getId());
        assertThat(recover(retry.getId())).isZero();
        assertThat(repository.getLastJobExecution("balanceReconciliationJob", parameters(date)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private int recover(long executionId) throws Exception {
        String script = Files.readString(Path.of("scripts/recover-reconciliation.sql"));
        return jdbc.execute((ConnectionCallback<Integer>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET @execution_id = " + executionId);
                int result = -1;
                for (String sql : script.split(";")) {
                    if (sql.isBlank()) continue;
                    if (statement.execute(sql)) {
                        try (ResultSet rows = statement.getResultSet()) {
                            if (rows.next()) result = rows.getInt(1);
                        }
                    }
                }
                return result;
            }
        });
    }

    private BalanceReconciliationScheduler schedulerAt(LocalDate date, int hour, int minute) {
        Clock clock = Clock.fixed(date.atTime(hour, minute).atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC);
        return new BalanceReconciliationScheduler(launcher, balanceReconciliationJob, meters, clock);
    }

    private JobParameters parameters(LocalDate date) {
        return new JobParametersBuilder().addLocalDate("scheduleDate", date).toJobParameters();
    }
}
