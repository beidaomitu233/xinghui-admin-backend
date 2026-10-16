-- UI-05: reuse directory IDs; retain every surviving page/button grant and old URL.
UPDATE sys_menu SET menu_name='系统管理',order_num=1 WHERE menu_id=1 AND menu_type='M';
UPDATE sys_menu SET menu_name='通用能力',order_num=2,icon='carbon:apps' WHERE menu_id=3 AND menu_type='M';
UPDATE sys_menu SET menu_name='运行维护',order_num=3 WHERE menu_id=2 AND menu_type='M';

-- Relative URLs must become absolute before their original parent is retired.
UPDATE sys_menu SET path=CONCAT('/ai/',path)
WHERE parent_id=4 AND menu_type='C' AND path NOT LIKE '/%' AND is_frame=1;
UPDATE sys_menu SET path=CONCAT('/infra/',path)
WHERE parent_id=3 AND menu_type='C' AND path NOT LIKE '/%' AND is_frame=1;
UPDATE sys_menu SET path=CONCAT('/',path),parent_id=2,
    order_num=CASE component WHEN 'dashboard/workspace/index' THEN 4 ELSE 5 END
WHERE parent_id=0 AND menu_type='C' AND component IN ('dashboard/workspace/index','dashboard/analytics/index');

CREATE TEMPORARY TABLE ui05_retired_directories (menu_id BIGINT PRIMARY KEY);
INSERT INTO ui05_retired_directories(menu_id)
SELECT menu_id FROM sys_menu WHERE menu_type='M' AND
    (menu_id IN (4,202610120140000001,202610120140000002)
     OR (parent_id=0 AND path IN ('lowcode','/lowcode')));
UPDATE sys_menu m JOIN ui05_retired_directories old ON old.menu_id=m.parent_id
SET m.parent_id=3 WHERE m.menu_type='C';

UPDATE sys_menu SET order_num=CASE component
    WHEN 'lowcode/forms/index' THEN 1
    WHEN 'system/file/index' THEN 2
    WHEN 'system/ai-model/index' THEN 3
    WHEN 'system/ai-prompt/index' THEN 4
    WHEN 'system/notice/index' THEN 5
    WHEN 'system/notification-template/index' THEN 6
    WHEN 'system/news/index' THEN 8
    ELSE order_num END
WHERE parent_id=3 AND menu_type='C';
-- Order 7 is reserved for the existing protected personal inbox frontend entry.

-- Directories alone confer no page permission; only existing child owners receive them.
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT DISTINCT rm.role_id,m.parent_id FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE m.parent_id IN (1,3,2) AND m.menu_type='C' AND m.is_deleted=0
AND NOT EXISTS (SELECT 1 FROM sys_role_menu current WHERE current.role_id=rm.role_id AND current.menu_id=m.parent_id);

-- Retire only empty obsolete directories, preserving any custom descendants.
CREATE TEMPORARY TABLE ui05_empty_directories AS
SELECT old.menu_id FROM ui05_retired_directories old
WHERE NOT EXISTS (SELECT 1 FROM sys_menu child WHERE child.parent_id=old.menu_id);
DELETE rm FROM sys_role_menu rm JOIN ui05_empty_directories old ON old.menu_id=rm.menu_id;
DELETE m FROM sys_menu m JOIN ui05_empty_directories old ON old.menu_id=m.menu_id;
DROP TEMPORARY TABLE ui05_empty_directories;
DROP TEMPORARY TABLE ui05_retired_directories;
