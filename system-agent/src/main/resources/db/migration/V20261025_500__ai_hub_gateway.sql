ALTER TABLE sys_agent_run ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'EXECUTE';
ALTER TABLE sys_agent_session ADD COLUMN archived TINYINT NOT NULL DEFAULT 0;

CREATE TABLE sys_agent_change (
  change_id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(20) NOT NULL,
  user_id VARCHAR(20) NOT NULL,
  session_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  tool_call_id VARCHAR(160) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  arguments_payload MEDIUMTEXT NOT NULL,
  preview_payload MEDIUMTEXT NOT NULL,
  result_payload MEDIUMTEXT NULL,
  status VARCHAR(24) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  decided_at DATETIME(3) NULL,
  UNIQUE KEY uk_agent_change_call (run_id,tool_call_id),
  KEY idx_agent_change_owner (tenant_id,user_id,session_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_agent_tool_audit (
  audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(20) NOT NULL,
  user_id VARCHAR(20) NOT NULL,
  session_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  tool_call_id VARCHAR(160) NULL,
  operation VARCHAR(32) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  arguments_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  change_id VARCHAR(36) NULL,
  created_at DATETIME(3) NOT NULL,
  KEY idx_agent_tool_run (tenant_id,user_id,run_id,audit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
