-- Current-record tokens include composite typed keys; retain the complete reference in admission/history.
ALTER TABLE sys_ai_request MODIFY COLUMN business_id VARCHAR(2048) COLLATE utf8mb4_bin NOT NULL;

CREATE TABLE sys_ai_conversation (
 conversation_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 owner_scope CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 subject_ref VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 tenant_ref VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 scene_key VARCHAR(128) COLLATE utf8mb4_bin NULL,
 scene_name VARCHAR(128) NOT NULL,
 title VARCHAR(160) NOT NULL,
 source_label VARCHAR(160) NOT NULL,
 request_kind VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
 business_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 business_id VARCHAR(2048) COLLATE utf8mb4_bin NOT NULL,
 revision INT NOT NULL DEFAULT 0,
 last_sequence BIGINT NOT NULL DEFAULT 0,
 body_bytes BIGINT NOT NULL DEFAULT 0,
 active_request_id BIGINT NULL,
 last_status VARCHAR(24) COLLATE utf8mb4_bin NOT NULL,
 last_message VARCHAR(256) NOT NULL DEFAULT '',
 history_state VARCHAR(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'COMPLETE',
 legacy_request_id BIGINT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_conversation_legacy_request(legacy_request_id),
 KEY idx_conversation_owner(owner_scope,update_time,conversation_id),
 KEY idx_conversation_scene(scene_key,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_conversation_message (
 message_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 conversation_id BIGINT NOT NULL,
 sequence_number BIGINT NOT NULL,
 role VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
 content LONGTEXT NOT NULL,
 status VARCHAR(24) COLLATE utf8mb4_bin NOT NULL,
 request_id BIGINT NOT NULL,
 scene_version_id BIGINT NULL,
 error_code VARCHAR(64) COLLATE utf8mb4_bin NULL,
 partial_output TINYINT(1) NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finish_time DATETIME(3) NULL,
 UNIQUE KEY uk_conversation_sequence(conversation_id,sequence_number),
 UNIQUE KEY uk_conversation_request_role(request_id,role),
 FOREIGN KEY(conversation_id) REFERENCES sys_ai_conversation(conversation_id),
 FOREIGN KEY(request_id) REFERENCES sys_ai_request(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_conversation_turn (
 request_id BIGINT NOT NULL PRIMARY KEY,
 conversation_id BIGINT NOT NULL,
 user_message_id BIGINT NULL,
 assistant_message_id BIGINT NULL,
 context_truncated TINYINT(1) NOT NULL DEFAULT 0,
 context_start_message_id BIGINT NULL,
 KEY idx_conversation_turn(conversation_id,request_id),
 FOREIGN KEY(request_id) REFERENCES sys_ai_request(request_id),
 FOREIGN KEY(conversation_id) REFERENCES sys_ai_conversation(conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Legacy rows are real historical metadata, never fabricated message bodies.
INSERT INTO sys_ai_conversation(owner_scope,subject_ref,tenant_ref,scene_key,scene_name,title,source_label,request_kind,
 business_type,business_id,last_status,history_state,legacy_request_id,create_time,update_time)
SELECT r.owner_scope,r.subject_ref,r.tenant_ref,s.scene_key,COALESCE(s.name,a.name),COALESCE(s.name,a.name),'旧调用记录',r.request_kind,
 r.business_type,r.business_id,r.status,'LEGACY_NO_BODY',r.request_id,r.create_time,COALESCE(r.finish_time,r.update_time)
FROM sys_ai_request r JOIN sys_ai_application a ON a.app_id=r.app_id LEFT JOIN sys_ai_scene s ON s.app_id=r.app_id;
INSERT INTO sys_ai_conversation_turn(request_id,conversation_id)
SELECT legacy_request_id,conversation_id FROM sys_ai_conversation WHERE legacy_request_id IS NOT NULL;

-- Durable intent for the simple test-and-use UI. Existing encrypted key material stays in its original tables.
CREATE TABLE sys_ai_settings_test_intent (
 test_id BIGINT NOT NULL PRIMARY KEY,
 config_id BIGINT NOT NULL,
 config_version BIGINT NOT NULL,
 expected_revision INT NOT NULL,
 activate_on_success TINYINT(1) NOT NULL,
 activation_status VARCHAR(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
