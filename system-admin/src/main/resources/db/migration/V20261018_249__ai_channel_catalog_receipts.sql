-- Catalog metadata receipts only. No API keys, generated content, runtime admission or permission grants.
CREATE TABLE sys_ai_channel_catalog_scan (
    scan_id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_scope CHAR(64) NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    endpoint_url VARCHAR(2048) NOT NULL,
    catalog_endpoint_url VARCHAR(2048) NOT NULL,
    credential_fingerprint CHAR(64) NOT NULL,
    source_code VARCHAR(32) NOT NULL,
    refreshed_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    models_json MEDIUMTEXT NOT NULL,
    scanned_count INT NOT NULL,
    filtered_count INT NOT NULL,
    filter_reasons_json TEXT NOT NULL,
    KEY idx_ai_catalog_expiry (expires_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_channel_catalog_binding (
    config_id BIGINT NOT NULL,
    key_ref VARCHAR(32) NOT NULL,
    scan_id VARCHAR(36) NOT NULL,
    PRIMARY KEY(config_id,key_ref),
    KEY idx_ai_catalog_binding_scan(scan_id),
    CONSTRAINT fk_ai_catalog_binding_scan FOREIGN KEY(scan_id) REFERENCES sys_ai_channel_catalog_scan(scan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
