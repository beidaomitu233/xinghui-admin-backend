-- AI-UX: stable public scene identity. Legacy application/request IDs remain unchanged.
ALTER TABLE sys_ai_application_version MODIFY COLUMN tested_request_id BIGINT NULL;
ALTER TABLE sys_ai_application_version ADD COLUMN snapshot_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY_TEST_PUBLISH';

CREATE TABLE sys_ai_scene (
 scene_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 name VARCHAR(128) NOT NULL,
 purpose VARCHAR(512) NOT NULL,
 scene_kind VARCHAR(24) NOT NULL,
 form_id VARCHAR(19) COLLATE utf8mb4_bin NULL,
 source VARCHAR(24) NOT NULL,
 compatibility_status VARCHAR(24) NOT NULL DEFAULT 'MAPPED',
 app_id BIGINT NULL,
 legacy_base_app_version_id BIGINT NULL,
 current_version_id BIGINT NULL,
 enabled TINYINT(1) NOT NULL DEFAULT 1,
 revision INT NOT NULL DEFAULT 0,
 created_by BIGINT NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_scene_application(app_id),
 KEY idx_scene_form(scene_kind,form_id),
 FOREIGN KEY(app_id) REFERENCES sys_ai_application(app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_scene_version (
 scene_version_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 scene_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 version_number INT NOT NULL,
 saved_revision INT NOT NULL,
 app_version_id BIGINT NOT NULL,
 definition_json LONGTEXT NULL,
 compiled_json LONGTEXT NULL,
 source VARCHAR(24) NOT NULL,
 legacy_template_id BIGINT NULL,
 legacy_template_version_id BIGINT NULL,
 created_by BIGINT NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_scene_version_number(scene_key,version_number),
 UNIQUE KEY uk_scene_execution_version(app_version_id),
 KEY idx_scene_legacy_template(legacy_template_id),
 FOREIGN KEY(scene_key) REFERENCES sys_ai_scene(scene_key),
 FOREIGN KEY(app_version_id) REFERENCES sys_ai_application_version(app_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_ai_scene_mutation (
 scene_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 client_mutation_id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL,
 input_fingerprint VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 result_json LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(scene_key,client_mutation_id),
 FOREIGN KEY(scene_key) REFERENCES sys_ai_scene(scene_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Duplicate legacy entries stay distinct and visibly ambiguous; no max-ID/last-write winner.
INSERT INTO sys_ai_scene(scene_key,name,purpose,scene_kind,form_id,source,compatibility_status,app_id,legacy_base_app_version_id,enabled,created_by,create_time,update_time)
SELECT CASE WHEN a.entry_key REGEXP '^lowcode[.]record[.]summary/[1-9][0-9]{0,18}$'
                 AND (SELECT COUNT(*) FROM sys_ai_application other_app WHERE other_app.entry_key=a.entry_key)=1
            THEN REPLACE(a.entry_key,'/','.') ELSE CONCAT('scene.legacy.',a.app_id) END,
       a.name,a.purpose,
       CASE WHEN a.entry_key REGEXP '^lowcode[.]record[.]summary/[1-9][0-9]{0,18}$' THEN 'RECORD_SUMMARY' ELSE 'GENERAL' END,
       CASE WHEN a.entry_key REGEXP '^lowcode[.]record[.]summary/[1-9][0-9]{0,18}$'
            THEN SUBSTRING(a.entry_key,CHAR_LENGTH('lowcode.record.summary/')+1) ELSE NULL END,
       'LEGACY_V04',
       CASE WHEN (SELECT COUNT(*) FROM sys_ai_application other_app WHERE other_app.entry_key=a.entry_key)>1
            THEN 'AMBIGUOUS' ELSE 'MAPPED' END,
       a.app_id,a.published_version_id,a.enabled,a.owner_id,a.create_time,a.update_time
FROM sys_ai_application a;

INSERT INTO sys_ai_scene_version(scene_key,version_number,saved_revision,app_version_id,source,legacy_template_id,legacy_template_version_id,created_by,create_time)
SELECT s.scene_key,v.version_number,v.draft_revision,v.app_version_id,'LEGACY_V04',
       CAST(JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.policy.template.templateId')) AS UNSIGNED),
       CAST(JSON_UNQUOTE(JSON_EXTRACT(v.snapshot_json,'$.policy.template.templateVersionId')) AS UNSIGNED),v.created_by,v.create_time
FROM sys_ai_application_version v JOIN sys_ai_scene s ON s.app_id=v.app_id;

UPDATE sys_ai_scene s JOIN sys_ai_application a ON a.app_id=s.app_id
 JOIN sys_ai_scene_version v ON v.app_version_id=a.published_version_id
 SET s.current_version_id=v.scene_version_id;

ALTER TABLE sys_ai_request ADD COLUMN scene_key VARCHAR(128) COLLATE utf8mb4_bin NULL;
ALTER TABLE sys_ai_request ADD COLUMN scene_version_id BIGINT NULL;
ALTER TABLE sys_ai_request ADD KEY idx_request_scene(scene_key,create_time);
ALTER TABLE sys_ai_request ADD CONSTRAINT fk_request_scene FOREIGN KEY(scene_key) REFERENCES sys_ai_scene(scene_key);
ALTER TABLE sys_ai_request ADD CONSTRAINT fk_request_scene_version FOREIGN KEY(scene_version_id) REFERENCES sys_ai_scene_version(scene_version_id);
UPDATE sys_ai_request r JOIN sys_ai_scene s ON s.app_id=r.app_id
 LEFT JOIN sys_ai_scene_version v ON v.app_version_id=r.app_version_id
 SET r.scene_key=s.scene_key,r.scene_version_id=v.scene_version_id;

-- Two daily entries; application/configuration records and action permissions are preserved.
UPDATE sys_menu SET menu_name='调用监控',order_num=1,path='/ai/requests',component='system/ai-request/index',perms='ai:monitor:enter'
 WHERE menu_id=202610160180000003;
UPDATE sys_menu SET menu_name='提示词配置',order_num=2,path='/ai/templates',component='system/ai-template/index',perms='ai:workspace:enter',visible='0'
 WHERE menu_id=107;
UPDATE sys_menu SET visible='1' WHERE menu_id IN (106,202610160180000002);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted) VALUES
 (202610160225000001,'场景列表',202610160180000002,1,'','',1,'F','0','0','ai:scene:list','#',0),
 (202610160225000002,'场景详情',202610160180000002,2,'','',1,'F','0','0','ai:scene:query','#',0),
 (202610160225000003,'新增场景',202610160180000002,3,'','',1,'F','0','0','ai:scene:add','#',0),
 (202610160225000004,'保存场景',202610160180000002,4,'','',1,'F','0','0','ai:scene:edit','#',0),
 (202610160225000005,'场景启停',202610160180000002,5,'','',1,'F','0','0','ai:scene:enable','#',0),
 (202610160225000006,'场景试运行',202610160180000002,6,'','',1,'F','0','0','ai:scene:test','#',0),
 (202610160225000007,'调用场景',107,7,'','',1,'F','0','0','ai:scene:invoke','#',0),
 (202610160225000008,'监控入口',202610160180000003,8,'','',1,'F','0','0','ai:monitor:enter','#',0);
UPDATE sys_menu SET parent_id=107 WHERE menu_id BETWEEN 202610160225000001 AND 202610160225000006;

INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
 SELECT DISTINCT r.role_id,m.menu_id FROM sys_role r
 JOIN sys_menu m ON m.menu_id BETWEEN 202610160225000001 AND 202610160225000007
 WHERE r.tenant_id='000000' AND r.role_key IN ('admin','super_admin') AND r.status=1 AND r.is_deleted=0;
-- ai:monitor:enter is derived at read time from current source permissions by the menu mapper.
-- Do not persist grants that would outlive revocation of those source permissions.
