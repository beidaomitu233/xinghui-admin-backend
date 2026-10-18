-- Only user description/context pointers and AST-approved candidates are durable.
-- Hidden system prompts, schema payloads and raw provider output are never stored here.
CREATE TABLE sys_ai_sql_assistance (
 request_id BIGINT NOT NULL PRIMARY KEY,
 owner_scope CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 mutation_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
 input_fingerprint CHAR(64) NOT NULL,
 scene_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 scene_revision INT NOT NULL,
 description VARCHAR(2048) NOT NULL,
 context_json LONGTEXT NOT NULL,
 schema_fingerprint VARCHAR(128) NOT NULL,
 scope_description VARCHAR(2000) NOT NULL,
 validation_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 approved_query_json LONGTEXT NULL,
 diagnostics_json TEXT NULL,
 error_code VARCHAR(64) NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_sql_assistance_mutation(owner_scope,mutation_id),
 FOREIGN KEY(request_id) REFERENCES sys_ai_request(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
