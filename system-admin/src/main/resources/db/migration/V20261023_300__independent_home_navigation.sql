-- Reuse the existing page IDs/URLs and grants; do not grant any page to new roles.
UPDATE sys_menu SET parent_id=0, path='/workspace', order_num=-20,
    icon='carbon:home'
WHERE menu_type='C' AND component='dashboard/workspace/index' AND is_deleted=0;
UPDATE sys_menu SET parent_id=0, path='/analytics', order_num=-10,
    icon='carbon:dashboard'
WHERE menu_type='C' AND component='dashboard/analytics/index' AND is_deleted=0;
-- Correct historical icon names absent from the bundled Carbon collection.
UPDATE sys_menu SET icon='carbon:data-table' WHERE icon='carbon:data-viewer' AND is_deleted=0;
UPDATE sys_menu SET icon='carbon:notebook' WHERE icon='carbon:dictionary' AND is_deleted=0;
UPDATE sys_menu SET icon='carbon:document' WHERE icon='carbon:form' AND is_deleted=0;
