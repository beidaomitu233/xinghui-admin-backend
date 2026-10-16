-- Fixed metadata, immutable published schemas and tenant-scoped records. No dynamic DDL.
CREATE TABLE IF NOT EXISTS sys_lowcode_form (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    form_key VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    draft_schema MEDIUMTEXT NOT NULL,
    published_version INT NOT NULL DEFAULT 0,
    creator VARCHAR(20) NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lowcode_form_key (form_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_lowcode_form_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    form_id BIGINT NOT NULL,
    schema_version INT NOT NULL,
    schema_json MEDIUMTEXT NOT NULL,
    creator VARCHAR(20) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_lowcode_form_version (form_id, schema_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_lowcode_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    form_id BIGINT NOT NULL,
    schema_version INT NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    creator VARCHAR(20) NOT NULL,
    data_json MEDIUMTEXT NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_lowcode_record_scope (form_id, tenant_id, is_deleted, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Prefer reserved IDs when free, then safely use AUTO_INCREMENT if another module owns them.
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 7160,'低代码',0,6,'lowcode',NULL,1,'M','0','0',NULL,'carbon:data-class',0
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=7160)
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE path IN ('lowcode','/lowcode') AND menu_type='M' AND is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '低代码',0,6,'lowcode',NULL,1,'M','0','0',NULL,'carbon:data-class',0
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE path IN ('lowcode','/lowcode') AND menu_type='M' AND is_deleted=0);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 7161,'表单管理',m.menu_id,10,'/lowcode/forms','lowcode/forms/index',1,'C','0','0','lowcode:form:list','carbon:form',0
FROM sys_menu m WHERE m.path IN ('lowcode','/lowcode') AND m.menu_type='M' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=7161)
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE path='/lowcode/forms' AND is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '表单管理',m.menu_id,10,'/lowcode/forms','lowcode/forms/index',1,'C','0','0','lowcode:form:list','carbon:form',0
FROM sys_menu m WHERE m.path IN ('lowcode','/lowcode') AND m.menu_type='M' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE path='/lowcode/forms' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '表单详情',m.menu_id,1,'','',1,'F','0','0','lowcode:form:query','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:form:query' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '表单新增',m.menu_id,2,'','',1,'F','0','0','lowcode:form:add','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:form:add' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '表单编辑',m.menu_id,3,'','',1,'F','0','0','lowcode:form:edit','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:form:edit' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '表单发布',m.menu_id,4,'','',1,'F','0','0','lowcode:form:publish','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:form:publish' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '表单删除',m.menu_id,5,'','',1,'F','0','0','lowcode:form:remove','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:form:remove' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '数据列表',m.menu_id,1,'','',1,'F','0','0','lowcode:record:list','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:record:list' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '数据详情',m.menu_id,2,'','',1,'F','0','0','lowcode:record:query','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:record:query' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '数据新增',m.menu_id,3,'','',1,'F','0','0','lowcode:record:add','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:record:add' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '数据编辑',m.menu_id,4,'','',1,'F','0','0','lowcode:record:edit','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:record:edit' AND is_deleted=0);

INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '数据删除',m.menu_id,5,'','',1,'F','0','0','lowcode:record:remove','#',0
FROM sys_menu m WHERE m.path='/lowcode/forms' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu WHERE perms='lowcode:record:remove' AND is_deleted=0);

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
ON (m.path IN ('lowcode','/lowcode','/lowcode/forms') OR m.perms LIKE 'lowcode:form:%' OR m.perms LIKE 'lowcode:record:%')
WHERE r.tenant_id='000000' AND r.role_key IN ('admin','super_admin') AND r.status=1 AND r.is_deleted=0 AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
