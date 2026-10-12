CREATE TABLE reconciliation_run (
    id BIGINT NOT NULL PRIMARY KEY,
    job_instance_id BIGINT NOT NULL,
    schedule_date DATE NOT NULL,
    basis VARCHAR(48) NOT NULL,
    observed_started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    status VARCHAR(16) NOT NULL,
    mismatch_count BIGINT NOT NULL DEFAULT 0,
    INDEX ix_reconciliation_completed(completed_at),
    INDEX ix_reconciliation_schedule(schedule_date)
) ENGINE=InnoDB;
CREATE TABLE reconciliation_mismatch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    actual_balance DECIMAL(19,2) NOT NULL,
    expected_balance DECIMAL(19,2) NOT NULL,
    resolution_status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    CONSTRAINT uk_reconciliation_run_account UNIQUE(run_id,account_id),
    CONSTRAINT fk_mismatch_run FOREIGN KEY(run_id) REFERENCES reconciliation_run(id),
    CONSTRAINT fk_mismatch_account FOREIGN KEY(account_id) REFERENCES account(id)
) ENGINE=InnoDB;
