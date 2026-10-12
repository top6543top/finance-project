package com.trading.account.domain.reconciliation;

import com.trading.account.domain.account.Account;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Restricted operator evidence: never return from a public endpoint or log this entity. */
@Entity
@Table(name = "reconciliation_mismatch", uniqueConstraints =
        @UniqueConstraint(name = "uk_reconciliation_run_account", columnNames = {"run_id", "account_id"}))
class ReconciliationMismatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "run_id", nullable = false)
    private ReconciliationRun run;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false)
    private Account account;
    @Column(nullable = false) private LocalDateTime observedAt;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal actualBalance;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal expectedBalance;
    @Column(nullable = false, length = 16) private String resolutionStatus;
}
