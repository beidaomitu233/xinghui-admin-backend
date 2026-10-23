-- USE-05: retire management-only templates/news without deleting delivery history.
-- Freeze rejected legacy work before detaching template identity. A retired request is never
-- eligible for the DELIVERY_ERROR compensation path, including an expired PROCESSING claim.
UPDATE sys_notification_event_request r
LEFT JOIN sys_notification_template t ON t.template_id=r.template_id
SET r.status='FAILED',r.failure_code='TEMPLATE_RETIRED',r.claim_token=NULL,
    r.lease_until=NULL,r.next_attempt_at=NULL
WHERE (r.status IN ('PENDING','PROCESSING') OR (r.status='FAILED' AND r.failure_code='DELIVERY_ERROR'))
  AND (t.template_id IS NULL OR t.enabled<>1);

ALTER TABLE sys_notification_event_request DROP FOREIGN KEY fk_notification_template;
ALTER TABLE sys_notification_event_request DROP COLUMN template_id;
DROP TABLE sys_notification_template;

-- Remove grants before menus; preserve SysNotice and authenticated inbox endpoints.
DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE m.component IN ('system/news/index','system/notification-template/index')
   OR m.perms LIKE 'system:news:%' OR m.perms LIKE 'system:notificationTemplate:%';
DELETE FROM sys_menu
WHERE component IN ('system/news/index','system/notification-template/index')
   OR perms LIKE 'system:news:%' OR perms LIKE 'system:notificationTemplate:%';
DROP TABLE sys_news;
