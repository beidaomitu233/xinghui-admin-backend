-- QF-04: optional website content administration; tenant-less platform tables only.
CREATE TABLE IF NOT EXISTS sys_site_config (
 id BIGINT NOT NULL PRIMARY KEY,
 site_name VARCHAR(200) NOT NULL, site_description VARCHAR(1000) NOT NULL DEFAULT '',
 logo_url VARCHAR(2048) NOT NULL DEFAULT '', contact_email VARCHAR(254) NOT NULL DEFAULT '',
 copyright VARCHAR(500) NOT NULL DEFAULT '', icp_number VARCHAR(100) NOT NULL DEFAULT '',
 revision INT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_site_navigation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(200) NOT NULL, url VARCHAR(2048) NOT NULL,
 sort_order INT NOT NULL DEFAULT 0, status CHAR(1) NOT NULL DEFAULT '0',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 revision INT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_site_navigation_status_order(status,sort_order,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_site_banner (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 title VARCHAR(200) NOT NULL, image_url VARCHAR(2048) NOT NULL, link_url VARCHAR(2048) NOT NULL DEFAULT '',
 sort_order INT NOT NULL DEFAULT 0, status CHAR(1) NOT NULL DEFAULT '0',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 revision INT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_site_banner_status_order(status,sort_order,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_site_product (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(200) NOT NULL, summary VARCHAR(2000) NOT NULL DEFAULT '', image_url VARCHAR(2048) NOT NULL DEFAULT '', link_url VARCHAR(2048) NOT NULL DEFAULT '',
 sort_order INT NOT NULL DEFAULT 0, status CHAR(1) NOT NULL DEFAULT '0',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 revision INT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_site_product_status_order(status,sort_order,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS sys_site_api_doc (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 title VARCHAR(200) NOT NULL, method VARCHAR(10) NOT NULL, api_path VARCHAR(2048) NOT NULL, content MEDIUMTEXT NOT NULL,
 sort_order INT NOT NULL DEFAULT 0, status CHAR(1) NOT NULL DEFAULT '0',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 revision INT NOT NULL DEFAULT 0,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_site_api_doc_status_order(status,sort_order,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '网站设置查询',m.menu_id,1,'','',1,'F','0','0','system:siteConfig:query','#',0
FROM sys_menu m WHERE m.perms='system:siteConfig:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:siteConfig:query' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '网站设置编辑',m.menu_id,2,'','',1,'F','0','0','system:siteConfig:edit','#',0
FROM sys_menu m WHERE m.perms='system:siteConfig:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:siteConfig:edit' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '导航查询',m.menu_id,1,'','',1,'F','0','0','system:navigation:query','#',0
FROM sys_menu m WHERE m.perms='system:navigation:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:navigation:query' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '导航新增',m.menu_id,2,'','',1,'F','0','0','system:navigation:add','#',0
FROM sys_menu m WHERE m.perms='system:navigation:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:navigation:add' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '导航编辑',m.menu_id,3,'','',1,'F','0','0','system:navigation:edit','#',0
FROM sys_menu m WHERE m.perms='system:navigation:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:navigation:edit' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '导航删除',m.menu_id,4,'','',1,'F','0','0','system:navigation:remove','#',0
FROM sys_menu m WHERE m.perms='system:navigation:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:navigation:remove' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 'Banner查询',m.menu_id,1,'','',1,'F','0','0','system:banner:query','#',0
FROM sys_menu m WHERE m.perms='system:banner:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:banner:query' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 'Banner新增',m.menu_id,2,'','',1,'F','0','0','system:banner:add','#',0
FROM sys_menu m WHERE m.perms='system:banner:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:banner:add' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 'Banner编辑',m.menu_id,3,'','',1,'F','0','0','system:banner:edit','#',0
FROM sys_menu m WHERE m.perms='system:banner:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:banner:edit' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 'Banner删除',m.menu_id,4,'','',1,'F','0','0','system:banner:remove','#',0
FROM sys_menu m WHERE m.perms='system:banner:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:banner:remove' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '产品查询',m.menu_id,1,'','',1,'F','0','0','system:ecosystem:query','#',0
FROM sys_menu m WHERE m.perms='system:ecosystem:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:ecosystem:query' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '产品新增',m.menu_id,2,'','',1,'F','0','0','system:ecosystem:add','#',0
FROM sys_menu m WHERE m.perms='system:ecosystem:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:ecosystem:add' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '产品编辑',m.menu_id,3,'','',1,'F','0','0','system:ecosystem:edit','#',0
FROM sys_menu m WHERE m.perms='system:ecosystem:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:ecosystem:edit' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT '产品删除',m.menu_id,4,'','',1,'F','0','0','system:ecosystem:remove','#',0
FROM sys_menu m WHERE m.perms='system:ecosystem:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:ecosystem:remove' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 'API文档查询',m.menu_id,1,'','',1,'F','0','0','system:apiDoc:query','#',0
FROM sys_menu m WHERE m.perms='system:apiDoc:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:apiDoc:query' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 'API文档新增',m.menu_id,2,'','',1,'F','0','0','system:apiDoc:add','#',0
FROM sys_menu m WHERE m.perms='system:apiDoc:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:apiDoc:add' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 'API文档编辑',m.menu_id,3,'','',1,'F','0','0','system:apiDoc:edit','#',0
FROM sys_menu m WHERE m.perms='system:apiDoc:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:apiDoc:edit' AND a.is_deleted=0);
INSERT INTO sys_menu(menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
SELECT 'API文档删除',m.menu_id,4,'','',1,'F','0','0','system:apiDoc:remove','#',0
FROM sys_menu m WHERE m.perms='system:apiDoc:list' AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_menu a WHERE a.perms='system:apiDoc:remove' AND a.is_deleted=0);
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
 ON (m.perms LIKE 'system:siteConfig:%' OR m.perms LIKE 'system:navigation:%'
 OR m.perms LIKE 'system:banner:%' OR m.perms LIKE 'system:ecosystem:%' OR m.perms LIKE 'system:apiDoc:%')
WHERE r.tenant_id='000000' AND r.role_key IN ('admin','super_admin') AND r.status=1 AND r.is_deleted=0 AND m.is_deleted=0
AND NOT EXISTS(SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
