CREATE TABLE IF NOT EXISTS payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no VARCHAR(64) NOT NULL UNIQUE,
    idem_key VARCHAR(128) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    fee_rate_bp INT NOT NULL,
    captured_amount BIGINT NOT NULL DEFAULT 0,
    refunded_amount BIGINT NOT NULL DEFAULT 0,
    fee_total BIGINT NOT NULL DEFAULT 0,
    fee_refunded BIGINT NOT NULL DEFAULT 0,
    settled_amount BIGINT NOT NULL DEFAULT 0,
    fee_settled BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS refund_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(64) NOT NULL UNIQUE,
    payment_no VARCHAR(64) NOT NULL,
    idem_key VARCHAR(128) NOT NULL UNIQUE,
    amount BIGINT NOT NULL,
    fee_reversed BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ledger_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_code VARCHAR(48) NOT NULL UNIQUE,
    account_name VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64),
    balance BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS ledger_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no VARCHAR(64),
    refund_no VARCHAR(64),
    batch_no VARCHAR(64),
    account_code VARCHAR(48) NOT NULL,
    direction VARCHAR(4) NOT NULL,
    amount BIGINT NOT NULL,
    txn_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_entry_payment ON ledger_entry(payment_no);

-- 幂等请求记录：同一 idem_key 只接受一次，重复请求回放首次结果
CREATE TABLE IF NOT EXISTS idempotent_request (
    idem_key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    response_code INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recon_batch (
    batch_no VARCHAR(64) PRIMARY KEY,
    status VARCHAR(24) NOT NULL,
    total_entries INT NOT NULL DEFAULT 0,
    matched INT NOT NULL DEFAULT 0,
    diff_count INT NOT NULL DEFAULT 0,
    settled_payments INT NOT NULL DEFAULT 0,
    settled_amount BIGINT NOT NULL DEFAULT 0,
    report CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recon_diff (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    payment_no VARCHAR(64),
    channel_payment_no VARCHAR(64),
    diff_type VARCHAR(32) NOT NULL,
    detail VARCHAR(512) NOT NULL,
    internal_amount BIGINT,
    channel_amount BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
