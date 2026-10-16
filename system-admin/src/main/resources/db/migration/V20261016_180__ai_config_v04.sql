-- AI-V04-A: immutable provider snapshots; no plaintext/ciphertext duplication from legacy models.
CREATE TABLE sys_ai_configuration (
 config_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 config_name VARCHAR(100) NOT NULL,
 environment_name VARCHAR(32) NOT NULL,
 provider_code VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 revision INT NOT NULL DEFAULT 0,
 draft_version_id BIGINT NULL,
 active_version_id BIGINT NULL,
 latest_test_id BIGINT NULL,
 running_test_id BIGINT NULL,
 legacy_model_id BIGINT NULL,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_ai_configuration_status(status,provider_code),
 UNIQUE KEY uk_ai_configuration_legacy(legacy_model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_configuration_model (
 model_ref BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 config_id BIGINT NOT NULL,
 model_code VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 UNIQUE KEY uk_ai_configuration_model(config_id,model_code),
 FOREIGN KEY(config_id) REFERENCES sys_ai_configuration(config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_configuration_key (
 key_ref BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 config_id BIGINT NOT NULL,
 FOREIGN KEY(config_id) REFERENCES sys_ai_configuration(config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_configuration_key_material (
 key_version BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 key_ref BIGINT NOT NULL,
 credential_mode VARCHAR(16) NOT NULL,
 credential_env_name VARCHAR(128) NULL,
 credential_ciphertext TEXT NULL,
 legacy_model_id BIGINT NULL,
 legacy_revision INT NULL,
 FOREIGN KEY(key_ref) REFERENCES sys_ai_configuration_key(key_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_configuration_version (
 version_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 config_id BIGINT NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 admitted_pairs_json LONGTEXT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(config_id) REFERENCES sys_ai_configuration(config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_configuration_test (
 test_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 config_id BIGINT NOT NULL,
 version_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL,
 cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(config_id) REFERENCES sys_ai_configuration(config_id),
 FOREIGN KEY(version_id) REFERENCES sys_ai_configuration_version(version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_ai_configuration_test_pair (
 pair_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 test_id BIGINT NOT NULL,
 model_ref BIGINT NOT NULL,
 key_ref BIGINT NOT NULL,
 key_version BIGINT NOT NULL,
 proof_hash VARCHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 error_code VARCHAR(64) NULL,
 elapsed_ms BIGINT NULL,
 checked_at DATETIME NULL,
 KEY idx_ai_pair_proof(proof_hash,pair_id),
 FOREIGN KEY(test_id) REFERENCES sys_ai_configuration_test(test_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing configurations appear as untested drafts on first explicit compatibility import.
-- Legacy tables and encrypted materials stay intact. No enabled bit becomes test evidence.

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
VALUES (202610160180000001,'AI管理',0,4,'/ai','BasicLayout',1,'M','0','0','','carbon:machine-learning',0);
UPDATE sys_menu SET menu_name='AI配置',parent_id=202610160180000001,order_num=1,
 path='/ai/configurations',component='system/ai-config/index',perms='ai:config:list' WHERE menu_id=106;
UPDATE sys_menu SET menu_name='AI提示词配置',parent_id=202610160180000001,order_num=3,
 path='/ai/templates',component='system/ai-template/index',perms='ai:template:list' WHERE menu_id=107;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
VALUES
 (202610160180000002,'AI应用中心',202610160180000001,2,'/ai/applications','system/ai-application/index',1,'C','0','0','ai:application:list','carbon:application',0),
 (202610160180000003,'AI日志记录',202610160180000001,4,'/ai/requests','system/ai-request/index',1,'C','0','0','ai:request:list','carbon:document',0),
 (202610160180000010,'AI配置列表',106,10,'','',1,'F','0','0','ai:config:list','#',0),
 (202610160180000011,'AI配置查询',106,11,'','',1,'F','0','0','ai:config:query','#',0),
 (202610160180000012,'AI配置新增',106,12,'','',1,'F','0','0','ai:config:add','#',0),
 (202610160180000013,'AI配置编辑',106,13,'','',1,'F','0','0','ai:config:edit','#',0),
 (202610160180000014,'AI配置测试',106,14,'','',1,'F','0','0','ai:config:test','#',0),
 (202610160180000015,'AI配置启用',106,15,'','',1,'F','0','0','ai:config:enable','#',0),
 (202610160180000016,'AI配置归档',106,16,'','',1,'F','0','0','ai:config:archive','#',0),
 (202610160180000020,'AI应用查询',202610160180000002,1,'','',1,'F','0','0','ai:application:query','#',0),
 (202610160180000021,'AI应用新增',202610160180000002,2,'','',1,'F','0','0','ai:application:add','#',0),
 (202610160180000022,'AI应用编辑',202610160180000002,3,'','',1,'F','0','0','ai:application:edit','#',0),
 (202610160180000023,'AI应用测试',202610160180000002,4,'','',1,'F','0','0','ai:application:test','#',0),
 (202610160180000024,'AI应用发布',202610160180000002,5,'','',1,'F','0','0','ai:application:publish','#',0),
 (202610160180000025,'AI应用启停',202610160180000002,6,'','',1,'F','0','0','ai:application:enable','#',0),
 (202610160180000030,'AI日志查询',202610160180000003,1,'','',1,'F','0','0','ai:request:query','#',0),
 (202610160180000031,'AI日志导出',202610160180000003,2,'','',1,'F','0','0','ai:request:export','#',0);

-- New actions only to existing platform administrators. Child owners retain the AI directory.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT rm.role_id,202610160180000001 FROM sys_role_menu rm WHERE rm.menu_id IN (106,107);
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id BETWEEN 202610160180000001 AND 202610160180000031
WHERE r.tenant_id='000000' AND r.role_key IN ('admin','super_admin') AND r.status=1 AND r.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM (SELECT role_id,menu_id FROM sys_role_menu) x WHERE x.role_id=r.role_id AND x.menu_id=m.menu_id);
