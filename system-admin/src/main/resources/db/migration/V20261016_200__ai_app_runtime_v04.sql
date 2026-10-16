-- AI-V04-B. Development draft: apply only in the PM-assigned schema window after A180/C190.
-- No business inputs, rendered messages, output fragments or credentials are stored here.
CREATE TABLE sys_ai_reference_guard (
  guard_id int NOT NULL,
  PRIMARY KEY (guard_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_ai_reference_guard (guard_id) VALUES (1);

CREATE TABLE sys_ai_application (
  app_id bigint NOT NULL AUTO_INCREMENT,
  name varchar(128) NOT NULL,
  purpose varchar(512) NOT NULL,
  owner_id bigint NOT NULL,
  entry_key varchar(128) COLLATE utf8mb4_bin NOT NULL,
  draft_json longtext NOT NULL,
  draft_revision int NOT NULL DEFAULT 1,
  published_version_id bigint DEFAULT NULL,
  enabled tinyint(1) NOT NULL DEFAULT 0,
  revision int NOT NULL DEFAULT 0,
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (app_id),
  KEY idx_ai_application_entry (entry_key, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_application_version (
  app_version_id bigint NOT NULL AUTO_INCREMENT,
  app_id bigint NOT NULL,
  version_number int NOT NULL,
  draft_revision int NOT NULL,
  snapshot_json longtext NOT NULL,
  tested_request_id bigint NOT NULL,
  created_by bigint NOT NULL,
  create_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (app_version_id),
  UNIQUE KEY uk_ai_application_version (app_id, version_number),
  CONSTRAINT fk_ai_app_version_app FOREIGN KEY (app_id) REFERENCES sys_ai_application(app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Normalized references let A/C inspect online dependencies without reading B policy JSON.
-- Changes to publication and references must share one transaction and dependency locks.
CREATE TABLE sys_ai_application_reference (
  app_version_id bigint NOT NULL,
  reference_kind varchar(24) COLLATE utf8mb4_bin NOT NULL,
  reference_id varchar(128) COLLATE utf8mb4_bin NOT NULL,
  reference_version varchar(64) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (app_version_id, reference_kind, reference_id, reference_version),
  KEY idx_ai_app_reference (reference_kind, reference_id, reference_version),
  CONSTRAINT fk_ai_app_reference_version FOREIGN KEY (app_version_id)
    REFERENCES sys_ai_application_version(app_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Shared cursor is separate from immutable version content; never use a JVM counter.
CREATE TABLE sys_ai_application_cursor (
  app_version_id bigint NOT NULL,
  next_index bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (app_version_id),
  CONSTRAINT fk_ai_app_cursor_version FOREIGN KEY (app_version_id)
    REFERENCES sys_ai_application_version(app_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_request (
  request_id bigint NOT NULL AUTO_INCREMENT,
  app_id bigint NOT NULL,
  app_version_id bigint DEFAULT NULL COMMENT 'Null only for audited draft tests',
  draft_revision int DEFAULT NULL,
  request_kind varchar(16) COLLATE utf8mb4_bin NOT NULL,
  owner_scope char(64) COLLATE utf8mb4_bin NOT NULL,
  subject_ref varchar(128) COLLATE utf8mb4_bin NOT NULL,
  tenant_ref varchar(64) COLLATE utf8mb4_bin NOT NULL,
  business_type varchar(64) COLLATE utf8mb4_bin NOT NULL,
  business_id varchar(128) COLLATE utf8mb4_bin NOT NULL,
  idempotency_scope char(64) COLLATE utf8mb4_bin NOT NULL,
  input_fingerprint char(64) COLLATE utf8mb4_bin NOT NULL,
  status varchar(24) COLLATE utf8mb4_bin NOT NULL,
  stage varchar(24) COLLATE utf8mb4_bin NOT NULL,
  error_code varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
  output_started tinyint(1) NOT NULL DEFAULT 0,
  attempt_count int NOT NULL DEFAULT 0,
  input_tokens bigint DEFAULT NULL,
  output_tokens bigint DEFAULT NULL,
  usage_known tinyint(1) NOT NULL DEFAULT 0,
  deadline datetime(3) NOT NULL,
  create_time datetime(3) NOT NULL,
  update_time datetime(3) NOT NULL,
  finish_time datetime(3) DEFAULT NULL,
  PRIMARY KEY (request_id),
  UNIQUE KEY uk_ai_request_idempotency (idempotency_scope),
  KEY idx_ai_request_owner (owner_scope, request_id),
  KEY idx_ai_request_app (app_id, create_time),
  KEY idx_ai_request_expiry (status, deadline),
  CONSTRAINT fk_ai_request_app FOREIGN KEY (app_id) REFERENCES sys_ai_application(app_id),
  CONSTRAINT fk_ai_request_version FOREIGN KEY (app_version_id)
    REFERENCES sys_ai_application_version(app_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_request_attempt (
  attempt_id bigint NOT NULL AUTO_INCREMENT,
  request_id bigint NOT NULL,
  attempt_number int NOT NULL,
  config_version varchar(64) COLLATE utf8mb4_bin NOT NULL,
  model_ref varchar(128) COLLATE utf8mb4_bin NOT NULL,
  key_ref varchar(128) COLLATE utf8mb4_bin NOT NULL,
  masked_key_label varchar(128) NOT NULL,
  switch_reason varchar(64) COLLATE utf8mb4_bin NOT NULL,
  status varchar(24) COLLATE utf8mb4_bin NOT NULL,
  error_code varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
  input_tokens bigint DEFAULT NULL,
  output_tokens bigint DEFAULT NULL,
  create_time datetime(3) NOT NULL,
  finish_time datetime(3) DEFAULT NULL,
  PRIMARY KEY (attempt_id),
  UNIQUE KEY uk_ai_request_attempt (request_id, attempt_number),
  KEY idx_ai_attempt_model (model_ref, create_time),
  CONSTRAINT fk_ai_attempt_request FOREIGN KEY (request_id) REFERENCES sys_ai_request(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_request_stage (
  stage_id bigint NOT NULL AUTO_INCREMENT,
  request_id bigint NOT NULL,
  stage varchar(24) COLLATE utf8mb4_bin NOT NULL,
  outcome varchar(24) COLLATE utf8mb4_bin NOT NULL,
  error_code varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
  reference_kind varchar(24) COLLATE utf8mb4_bin DEFAULT NULL,
  reference_id varchar(128) COLLATE utf8mb4_bin DEFAULT NULL,
  reference_version varchar(64) COLLATE utf8mb4_bin DEFAULT NULL,
  create_time datetime(3) NOT NULL,
  PRIMARY KEY (stage_id),
  KEY idx_ai_stage_request (request_id, stage_id),
  CONSTRAINT fk_ai_stage_request FOREIGN KEY (request_id) REFERENCES sys_ai_request(request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
