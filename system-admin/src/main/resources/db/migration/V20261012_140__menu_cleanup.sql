-- UI-04: retire only the user-confirmed legacy modules. Historical V1/V2/V120/V130 stay immutable.
CREATE TEMPORARY TABLE ui04_removed_menu (menu_id BIGINT PRIMARY KEY);
INSERT INTO ui04_removed_menu(menu_id)
SELECT menu_id FROM sys_menu
WHERE perms REGEXP '^(example:material|system:(download|page|siteConfig|navigation|banner|ecosystem|apiDoc)):'
   OR component IN ('example/material/index','system/download/index','system/page/index',
       'system/site/config/index','system/site/navigation/index','system/site/banner/index',
       'system/site/ecosystem/index','system/site/apiDoc/index')
   OR (menu_type='M' AND path IN ('site','/site'));
-- The legacy tree has the website directory, its five pages and their buttons.
CREATE TEMPORARY TABLE ui04_removed_children AS
SELECT m.menu_id FROM sys_menu m JOIN ui04_removed_menu p ON p.menu_id=m.parent_id;
INSERT IGNORE INTO ui04_removed_menu(menu_id) SELECT menu_id FROM ui04_removed_children;
DROP TEMPORARY TABLE ui04_removed_children;
CREATE TEMPORARY TABLE ui04_removed_children AS
SELECT m.menu_id FROM sys_menu m JOIN ui04_removed_menu p ON p.menu_id=m.parent_id;
INSERT IGNORE INTO ui04_removed_menu(menu_id) SELECT menu_id FROM ui04_removed_children;
DROP TEMPORARY TABLE ui04_removed_children;
DELETE rm FROM sys_role_menu rm JOIN ui04_removed_menu old ON old.menu_id=rm.menu_id;
DELETE m FROM sys_menu m JOIN ui04_removed_menu old ON old.menu_id=m.menu_id;
DROP TEMPORARY TABLE ui04_removed_menu;

DROP TABLE IF EXISTS sys_site_config;
DROP TABLE IF EXISTS sys_site_navigation;
DROP TABLE IF EXISTS sys_site_banner;
DROP TABLE IF EXISTS sys_site_product;
DROP TABLE IF EXISTS sys_site_api_doc;
DROP TABLE IF EXISTS sys_download;
DROP TABLE IF EXISTS sys_page;
DROP TABLE IF EXISTS example_material;

-- Reuse existing directory IDs; keep all surviving deep links stable.
UPDATE sys_menu SET menu_name='系统管理',order_num=1,icon='carbon:settings' WHERE menu_id=1;
UPDATE sys_menu SET menu_name='任务与监控',order_num=3,icon='carbon:activity' WHERE menu_id=2;
UPDATE sys_menu SET menu_name='资源管理',order_num=4,icon='carbon:folder' WHERE menu_id=3;
UPDATE sys_menu SET menu_name='AI中心',order_num=5,icon='carbon:bot' WHERE menu_id=4;
UPDATE sys_menu SET order_num=1 WHERE menu_id=100;
UPDATE sys_menu SET order_num=2 WHERE menu_id=101;
UPDATE sys_menu SET order_num=3 WHERE menu_id=102;
UPDATE sys_menu SET order_num=4 WHERE menu_id=104;
UPDATE sys_menu SET order_num=5 WHERE menu_id=103;
UPDATE sys_menu SET order_num=1 WHERE menu_id=300;
UPDATE sys_menu SET order_num=1 WHERE menu_id=106;
UPDATE sys_menu SET order_num=2 WHERE menu_id=107;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,menu_type,visible,status,perms,icon,is_deleted)
VALUES (202610120140000001,'消息中心',0,2,'messages',NULL,1,'M','0','0','','carbon:notification',0),
       (202610120140000002,'内容管理',0,7,'content',NULL,1,'M','0','0','','carbon:document',0);
UPDATE sys_menu SET parent_id=202610120140000001,order_num=1,path='/infra/notice' WHERE menu_id=301;
UPDATE sys_menu SET parent_id=202610120140000001,order_num=2,path='/system/notification-template' WHERE menu_id=105;
UPDATE sys_menu SET parent_id=202610120140000002,order_num=1,path='/system/news'
WHERE component='system/news/index';
-- Grant new directories only to roles already owning their surviving children. No page/button grants added.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT rm.role_id,m.parent_id FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE m.parent_id IN (202610120140000001,202610120140000002)
AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id=rm.role_id AND existing.menu_id=m.parent_id);
-- Personal inbox stays a protected core entry, independent of business menu grants.
-- CFG-02 inserts its implemented business configuration under directory 1, order 6.
-- LC-01 inserts its implemented low-code directory at root order 6 and its form entry. Do not create an empty shell here.
