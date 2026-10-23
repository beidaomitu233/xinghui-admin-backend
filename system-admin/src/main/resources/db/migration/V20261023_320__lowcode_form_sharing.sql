-- Sharing is opt-in. Existing forms, immutable versions and records stay intact.
CREATE TABLE IF NOT EXISTS sys_lowcode_form_share (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    form_id BIGINT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    issuer_id VARCHAR(20) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    access_mode VARCHAR(16) NOT NULL DEFAULT 'login',
    token_hash CHAR(64) NULL,
    token_ciphertext VARCHAR(512) NULL,
    token_version INT NOT NULL DEFAULT 0,
    revision INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lowcode_share_form (form_id),
    UNIQUE KEY uk_lowcode_share_token (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_lowcode_submission (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    share_id BIGINT NOT NULL,
    token_version INT NOT NULL,
    actor_key VARCHAR(100) NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    receipt VARCHAR(64) NOT NULL,
    record_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lowcode_submission_once (share_id, token_version, actor_key, request_key),
    UNIQUE KEY uk_lowcode_submission_receipt (receipt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
