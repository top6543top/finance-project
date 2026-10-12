-- Preserve the original legacy comparison rules before making new keys case-sensitive.
-- Use metadata because legacy Hibernate installations generated different index names.
SET @legacy_charset = (SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'idempotency_key' AND COLUMN_NAME = 'idempotency_key');
SET @legacy_collation = (SELECT COLLATION_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'idempotency_key' AND COLUMN_NAME = 'idempotency_key');
SET @add_legacy = CONCAT('ALTER TABLE idempotency_key ADD legacy_key VARCHAR(100) CHARACTER SET ',
    @legacy_charset, ' COLLATE ', @legacy_collation, ' NULL');
PREPARE add_legacy_column FROM @add_legacy;
EXECUTE add_legacy_column;
DEALLOCATE PREPARE add_legacy_column;
UPDATE idempotency_key SET legacy_key = idempotency_key;

ALTER TABLE idempotency_key
    -- NO PAD preserves distinct legacy values such as 'key' and 'key '.
    MODIFY idempotency_key VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD actor_id BIGINT NOT NULL DEFAULT 0,
    ADD operation VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
    ADD request_fingerprint VARCHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
    ADD CONSTRAINT uk_idempotency_actor_key UNIQUE(actor_id,idempotency_key),
    ADD INDEX ix_idempotency_legacy_key(actor_id,legacy_key);

SET @old_unique_indexes = (SELECT GROUP_CONCAT(CONCAT('DROP INDEX `', REPLACE(INDEX_NAME, '`', '``'), '`') SEPARATOR ', ')
    FROM (SELECT INDEX_NAME FROM information_schema.statistics
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='idempotency_key' AND NON_UNIQUE=0 AND INDEX_NAME<>'PRIMARY'
        GROUP BY INDEX_NAME HAVING COUNT(*)=1 AND MAX(COLUMN_NAME)='idempotency_key') old_indexes);
SET @drop_old = IF(@old_unique_indexes IS NULL, 'SELECT 1', CONCAT('ALTER TABLE idempotency_key ', @old_unique_indexes));
PREPARE drop_old_indexes FROM @drop_old;
EXECUTE drop_old_indexes;
DEALLOCATE PREPARE drop_old_indexes;

ALTER TABLE transaction_history
    ADD actor_id BIGINT NULL,
    ADD idempotency_key_id BIGINT NULL,
    ADD CONSTRAINT fk_history_actor FOREIGN KEY(actor_id) REFERENCES member(id),
    ADD CONSTRAINT fk_history_request FOREIGN KEY(idempotency_key_id) REFERENCES idempotency_key(id),
    ADD INDEX ix_history_from_id(from_account_id,id),
    ADD INDEX ix_history_to_id(to_account_id,id);
