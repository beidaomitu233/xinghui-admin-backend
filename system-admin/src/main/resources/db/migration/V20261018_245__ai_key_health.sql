-- Bad material is isolated by immutable key version; no credential or upstream body is stored.
CREATE TABLE sys_ai_key_health (
 key_ref BIGINT NOT NULL,
 key_version BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL,
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(key_ref,key_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Idempotency stores a keyed fingerprint only, never the submitted credential/body.
CREATE TABLE sys_ai_settings_mutation (
 owner_scope CHAR(64) NOT NULL,
 mutation_id VARCHAR(36) NOT NULL,
 config_id BIGINT NOT NULL,
 fingerprint CHAR(64) NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(owner_scope,mutation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
