-- Agent runs are UUIDs, never aliases of ordinary numeric AI requests.
ALTER TABLE sys_ai_call_log ADD COLUMN agent_session_id VARCHAR(36) NULL;
ALTER TABLE sys_ai_call_log ADD COLUMN agent_run_id VARCHAR(36) NULL;
ALTER TABLE sys_ai_call_log ADD COLUMN agent_model_call_id VARCHAR(36) NULL;
ALTER TABLE sys_ai_call_log ADD COLUMN agent_app_id BIGINT NULL;
ALTER TABLE sys_ai_call_log ADD COLUMN agent_scene_key VARCHAR(128) NULL;
CREATE INDEX idx_ai_call_agent_run ON sys_ai_call_log(agent_run_id,started_at);
