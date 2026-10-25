CREATE TABLE sys_agent_image (
  image_id VARCHAR(36) NOT NULL,
  session_id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (image_id),
  KEY idx_agent_image_owner (tenant_id,user_id,session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE sys_agent_run ADD COLUMN image_ids_json TEXT NULL;
