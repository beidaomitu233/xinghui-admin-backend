-- Agent module owns these tables. Identity and business records remain in their own modules.
CREATE TABLE sys_agent_session (
  session_id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  title VARCHAR(160) NOT NULL,
  active_run_id VARCHAR(36) NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (session_id),
  KEY idx_agent_session_owner (tenant_id,user_id,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sys_agent_run (
  run_id VARCHAR(36) NOT NULL,
  session_id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  client_request_id VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  prompt_scene_key VARCHAR(128) NOT NULL,
  input_payload MEDIUMTEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  error_code VARCHAR(64) NULL,
  deadline TIMESTAMP(3) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (run_id),
  UNIQUE KEY uk_agent_submit (session_id,client_request_id),
  KEY idx_agent_run_owner (tenant_id,user_id,session_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sys_agent_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT,
  session_id VARCHAR(36) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (event_id),
  KEY idx_agent_event_cursor (session_id,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sys_agent_state (
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(36) NOT NULL,
  slot_key VARCHAR(128) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  version BIGINT NOT NULL,
  PRIMARY KEY (tenant_id,user_id,session_id,slot_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sys_agent_resource_ref (
  session_id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  PRIMARY KEY (session_id,resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE sys_agent_tool_receipt (
  session_id VARCHAR(36) NOT NULL,
  tool_call_id VARCHAR(128) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  arguments_hash VARCHAR(64) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (session_id,tool_call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
