-- Upstream observations only. No request prompts, credentials or historical backfill.
CREATE TABLE sys_ai_call_log (
    call_id VARCHAR(36) NOT NULL PRIMARY KEY,
    attempt_id BIGINT NULL,
    request_id BIGINT NULL,
    configuration_test_id BIGINT NULL,
    configuration_pair_id BIGINT NULL,
    entry_type VARCHAR(32) NOT NULL,
    owner_scope CHAR(64) NOT NULL,
    tenant_ref VARCHAR(64) NOT NULL,
    subject_ref VARCHAR(128) NOT NULL,
    model_ref VARCHAR(128) NULL,
    provider_code VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    capture_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CAPTURED',
    response_available BOOLEAN NOT NULL DEFAULT FALSE,
    metadata_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_ai_call_attempt (attempt_id),
    UNIQUE KEY uk_ai_call_pair (configuration_pair_id),
    KEY idx_ai_call_owner (owner_scope,started_at),
    KEY idx_ai_call_time (started_at),
    KEY idx_ai_call_request (request_id),
    KEY idx_ai_call_provider (provider_code,started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_call_response (
    call_id VARCHAR(36) NOT NULL PRIMARY KEY,
    response_format VARCHAR(8) NOT NULL,
    response_body MEDIUMTEXT NULL,
    headers_json MEDIUMTEXT NULL,
    usage_json MEDIUMTEXT NULL,
    received_bytes BIGINT NULL,
    archived_bytes BIGINT NULL,
    CONSTRAINT fk_ai_call_response FOREIGN KEY (call_id) REFERENCES sys_ai_call_log(call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE sys_ai_configuration_test
    ADD COLUMN initiator_subject_ref VARCHAR(128) NULL;
ALTER TABLE sys_ai_configuration_test
    ADD COLUMN initiator_tenant_ref VARCHAR(64) NULL;
