-- Baseline of the pre-hardening business schema. Existing installations must
-- explicitly baseline at 1 after a backup and schema comparison; never auto-baseline.
CREATE TABLE member (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    created_at DATETIME(6),
    CONSTRAINT uk_member_email UNIQUE(email)
) ENGINE=InnoDB;
CREATE TABLE account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_number VARCHAR(20) NOT NULL,
    balance DECIMAL(19,2) NOT NULL,
    version BIGINT,
    member_id BIGINT NOT NULL,
    created_at DATETIME(6),
    CONSTRAINT uk_account_number UNIQUE(account_number),
    CONSTRAINT fk_account_member FOREIGN KEY(member_id) REFERENCES member(id)
) ENGINE=InnoDB;
CREATE TABLE idempotency_key (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    status ENUM('IN_PROGRESS','COMPLETED') NOT NULL,
    response_body TEXT,
    created_at DATETIME(6),
    CONSTRAINT uk_legacy_idempotency_key UNIQUE(idempotency_key)
) ENGINE=InnoDB;
CREATE TABLE transaction_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    from_account_id BIGINT,
    to_account_id BIGINT,
    amount DECIMAL(19,2) NOT NULL,
    type ENUM('DEPOSIT','WITHDRAW','TRANSFER') NOT NULL,
    created_at DATETIME(6),
    CONSTRAINT fk_history_from FOREIGN KEY(from_account_id) REFERENCES account(id),
    CONSTRAINT fk_history_to FOREIGN KEY(to_account_id) REFERENCES account(id)
) ENGINE=InnoDB;
