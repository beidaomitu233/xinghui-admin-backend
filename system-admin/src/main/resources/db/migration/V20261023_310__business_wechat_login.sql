-- USE-03: mail and service-account settings use the existing encrypted business config store.
-- OpenID is reduced to a per-channel, per-AppID digest. Never persist OAuth tokens or codes.
CREATE TABLE sys_wechat_identity_binding (
 identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 channel VARCHAR(16) NOT NULL,
 client_id VARCHAR(64) NOT NULL,
 user_id BIGINT NOT NULL,
 tenant_id VARCHAR(20) NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_wechat_account (channel,client_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_wechat_login_flow (
 flow_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 state_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 browser_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 channel VARCHAR(16) NOT NULL,
 client_id VARCHAR(64) NOT NULL,
 config_revision INT NOT NULL,
 return_path VARCHAR(2048) NOT NULL,
 status VARCHAR(24) NOT NULL,
 identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 expires_at DATETIME NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_wechat_flow_state (state_hash),
 KEY idx_wechat_flow_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
