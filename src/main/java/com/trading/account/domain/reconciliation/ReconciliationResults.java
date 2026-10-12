package com.trading.account.domain.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class ReconciliationResults {
    private final JdbcTemplate jdbc;

    public ReconciliationResults(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        // Database gauges survive restarts and expose the same values on every replica.
        registry.gauge("reconciliation.last.completed.timestamp", this, r -> r.latestTimestamp(false));
        registry.gauge("reconciliation.last.success.timestamp", this, r -> r.latestTimestamp(true));
        registry.gauge("reconciliation.mismatches", this, r -> r.latestMismatchCount());
        // _total is a reserved Prometheus counter suffix and would collide with the latest-count gauge.
        registry.gauge("reconciliation.mismatches.recorded", this, r -> r.count("reconciliation_mismatch", ""));
        registry.gauge("reconciliation.runs.failed", this, r -> r.count("reconciliation_run", " WHERE status = 'FAILED'"));
    }

    @Transactional
    public void started(JobExecution execution) {
        jdbc.update("""
                INSERT INTO reconciliation_run
                (id, job_instance_id, schedule_date, basis, observed_started_at, status, mismatch_count)
                VALUES (?, ?, ?, 'CURRENT_LEDGER_CONSISTENCY', ?, 'STARTED', 0)
                """, execution.getId(), execution.getJobInstance().getId(),
                execution.getJobParameters().getLocalDate("scheduleDate"), utcNow());
    }

    @Transactional
    public void write(Long runId, Chunk<? extends BalanceMismatch> chunk) {
        // Unique key plus no-op upsert preserves original evidence when a chunk is replayed.
        jdbc.batchUpdate("""
                INSERT INTO reconciliation_mismatch
                (run_id, account_id, observed_at, actual_balance, expected_balance, resolution_status)
                VALUES (?, ?, ?, ?, ?, 'OPEN')
                ON DUPLICATE KEY UPDATE id = id
                """, chunk.getItems(), chunk.size(), (statement, mismatch) -> {
            statement.setLong(1, runId);
            statement.setLong(2, mismatch.accountId());
            statement.setTimestamp(3, Timestamp.valueOf(utcNow()));
            statement.setBigDecimal(4, mismatch.actualBalance());
            statement.setBigDecimal(5, mismatch.expectedBalance());
        });
    }

    @Transactional
    public void finished(JobExecution execution) {
        jdbc.update("""
                UPDATE reconciliation_run SET status = ?, completed_at = ?,
                mismatch_count = (SELECT COUNT(*) FROM reconciliation_mismatch WHERE run_id = ?)
                WHERE id = ?
                """, execution.getStatus() == BatchStatus.COMPLETED ? "COMPLETED" : "FAILED",
                utcNow(), execution.getId(), execution.getId());
    }

    private static LocalDateTime utcNow() { return LocalDateTime.now(ZoneOffset.UTC); }

    private double latestTimestamp(boolean onlyMatched) {
        Timestamp value = jdbc.queryForObject("SELECT MAX(completed_at) FROM reconciliation_run WHERE status = 'COMPLETED'"
                + (onlyMatched ? " AND mismatch_count = 0" : ""), Timestamp.class);
        return value == null ? 0 : value.toLocalDateTime().toEpochSecond(ZoneOffset.UTC);
    }

    private double latestMismatchCount() {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT mismatch_count FROM reconciliation_run WHERE status = 'COMPLETED'
                ORDER BY completed_at DESC, id DESC LIMIT 1), 0)
                """, Long.class);
    }

    private double count(String table, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + condition, Long.class);
    }
}
