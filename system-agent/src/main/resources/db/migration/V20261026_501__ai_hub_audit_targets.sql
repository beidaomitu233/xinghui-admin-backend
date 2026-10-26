ALTER TABLE sys_agent_tool_audit ADD COLUMN target_ids_json MEDIUMTEXT NULL;
ALTER TABLE sys_agent_tool_audit ADD COLUMN duration_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_agent_tool_audit ADD COLUMN error_code VARCHAR(64) NULL;
