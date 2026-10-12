package com.trading.account.domain.reconciliation;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Schema mapping; writes use JDBC in ReconciliationResults within the Batch transaction. */
@Entity
@Table(name = "reconciliation_run", indexes = {
        @Index(name = "idx_reconciliation_completed", columnList = "completed_at"),
        @Index(name = "idx_reconciliation_schedule", columnList = "schedule_date")})
class ReconciliationRun {
    @Id private Long id;
    @Column(nullable = false) private Long jobInstanceId;
    @Column(nullable = false) private LocalDate scheduleDate;
    @Column(nullable = false, length = 48) private String basis;
    @Column(nullable = false) private LocalDateTime observedStartedAt;
    private LocalDateTime completedAt;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false) private long mismatchCount;
}
