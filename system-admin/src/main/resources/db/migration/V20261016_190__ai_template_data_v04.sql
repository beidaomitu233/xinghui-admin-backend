-- Independent immutable v0.4 templates/resources. Legacy sys_ai_prompt_scene is retained as-is.
-- Menu/component registration belongs to A (system/ai-template/index, /ai/templates).
CREATE TABLE sys_ai_prompt_template (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name varchar(100) NOT NULL,
 draft_json longtext NOT NULL,
 revision int NOT NULL DEFAULT 1,
 enabled tinyint(1) NOT NULL DEFAULT 0,
 tested_hash char(64) DEFAULT NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_prompt_template_version (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 template_id bigint NOT NULL,
 version_no int NOT NULL,
 config_json longtext NOT NULL,
 config_hash char(64) NOT NULL,
 publisher varchar(64) NOT NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_ai_template_version(template_id,version_no),
 CONSTRAINT fk_ai_template_version FOREIGN KEY(template_id) REFERENCES sys_ai_prompt_template(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_data_resource (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name varchar(100) NOT NULL,
 draft_json longtext NOT NULL,
 revision int NOT NULL DEFAULT 1,
 enabled tinyint(1) NOT NULL DEFAULT 0,
 state varchar(20) NOT NULL DEFAULT 'DRAFT',
 tested_hash char(64) DEFAULT NULL,
  tested_check_id bigint DEFAULT NULL,
 submitted_hash char(64) DEFAULT NULL,
 approved_hash char(64) DEFAULT NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_data_resource_version (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 resource_id bigint NOT NULL,
 version_no int NOT NULL,
 config_json longtext NOT NULL,
 config_hash char(64) NOT NULL,
 approver varchar(64) NOT NULL,
  test_check_id bigint NOT NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_ai_resource_version(resource_id,version_no),
 CONSTRAINT fk_ai_resource_version FOREIGN KEY(resource_id) REFERENCES sys_ai_data_resource(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_data_resource_check (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 resource_id bigint NOT NULL,
 config_hash char(64) NOT NULL,
  scope_hash char(64) NOT NULL,
 action varchar(20) NOT NULL,
 outcome varchar(32) NOT NULL,
 subject_id varchar(64) NOT NULL,
 tenant_id varchar(64) NOT NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_ai_resource_evidence(resource_id,id),
 CONSTRAINT fk_ai_resource_check FOREIGN KEY(resource_id) REFERENCES sys_ai_data_resource(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
