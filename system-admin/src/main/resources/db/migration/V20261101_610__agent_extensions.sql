CREATE TABLE sys_ai_agent_extension (
    extension_id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_ref VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    config_json MEDIUMTEXT NOT NULL,
    credential_ciphertext MEDIUMTEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_extension_code (tenant_ref,kind,code),
    KEY idx_ai_extension_active (tenant_ref,enabled,kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
