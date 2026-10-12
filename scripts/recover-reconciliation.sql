-- Only execute through recover-reconciliation.ps1 in a verified offline maintenance window.
-- The caller sets @execution_id to a positive integer. Never infer death from elapsed time.
SET time_zone = '+00:00';
START TRANSACTION;
SET @eligible = NULL;
SELECT e.JOB_EXECUTION_ID INTO @eligible
FROM BATCH_JOB_EXECUTION e
JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
WHERE e.JOB_EXECUTION_ID = @execution_id
  AND i.JOB_NAME = 'balanceReconciliationJob'
  AND e.STATUS IN ('STARTING', 'STARTED', 'STOPPING')
FOR UPDATE;
UPDATE BATCH_STEP_EXECUTION
SET STATUS = 'FAILED', EXIT_CODE = 'FAILED',
    EXIT_MESSAGE = 'Offline recovery after verified worker shutdown',
    END_TIME = UTC_TIMESTAMP(6), LAST_UPDATED = UTC_TIMESTAMP(6), VERSION = VERSION + 1
WHERE JOB_EXECUTION_ID = @eligible AND STATUS IN ('STARTING', 'STARTED', 'STOPPING');
UPDATE BATCH_JOB_EXECUTION
SET STATUS = 'FAILED', EXIT_CODE = 'FAILED',
    EXIT_MESSAGE = 'Offline recovery after verified worker shutdown',
    END_TIME = UTC_TIMESTAMP(6), LAST_UPDATED = UTC_TIMESTAMP(6), VERSION = VERSION + 1
WHERE JOB_EXECUTION_ID = @eligible AND STATUS IN ('STARTING', 'STARTED', 'STOPPING');
SET @recovered = ROW_COUNT();
UPDATE reconciliation_run
SET status = 'FAILED', completed_at = UTC_TIMESTAMP(6),
    mismatch_count = (SELECT COUNT(*) FROM reconciliation_mismatch WHERE run_id = @eligible)
WHERE id = @eligible;
COMMIT;
SELECT @recovered;
