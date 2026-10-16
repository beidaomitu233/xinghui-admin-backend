-- AI-V04-B follow-up after the frozen V200. Historical unknown metadata stays NULL.
ALTER TABLE sys_ai_request_attempt ADD COLUMN provider_code varchar(64) DEFAULT NULL;
ALTER TABLE sys_ai_request_attempt ADD COLUMN model_code varchar(256) DEFAULT NULL;
ALTER TABLE sys_ai_request_attempt ADD COLUMN key_version varchar(64) DEFAULT NULL;
ALTER TABLE sys_ai_request_attempt ADD COLUMN provider_request_id varchar(128) DEFAULT NULL;
ALTER TABLE sys_ai_request_attempt ADD COLUMN output_token_limit int DEFAULT NULL;
