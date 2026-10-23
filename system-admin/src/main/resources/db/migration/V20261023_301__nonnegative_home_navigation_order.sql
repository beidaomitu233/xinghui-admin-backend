-- V300 is already applied: keep its checksum and repair the persisted metadata.
-- Reserve 0/1 for existing home pages. Rank other active roots by their former
-- order (NULL first, menu_id as deterministic tie breaker), without changing
-- identity, permissions, parentage or the relative order of other roots.
CREATE TEMPORARY TABLE use01_root_order_v301 (
    menu_id BIGINT NOT NULL PRIMARY KEY,
    order_num INT NOT NULL
);

INSERT INTO use01_root_order_v301 (menu_id, order_num)
SELECT m.menu_id, 2 + (
    SELECT COUNT(*) FROM sys_menu p
    WHERE p.parent_id=0 AND p.is_deleted=0
      AND NOT (COALESCE(p.menu_type,'')='C' AND COALESCE(p.component,'') IN
          ('dashboard/workspace/index','dashboard/analytics/index'))
      AND (
          (p.order_num IS NULL AND m.order_num IS NOT NULL)
          OR p.order_num < m.order_num
          OR ((p.order_num=m.order_num OR (p.order_num IS NULL AND m.order_num IS NULL))
              AND p.menu_id < m.menu_id)
      )
)
FROM sys_menu m
WHERE m.parent_id=0 AND m.is_deleted=0
  AND NOT (COALESCE(m.menu_type,'')='C' AND COALESCE(m.component,'') IN
      ('dashboard/workspace/index','dashboard/analytics/index'));

UPDATE sys_menu SET order_num=(
    SELECT r.order_num FROM use01_root_order_v301 r WHERE r.menu_id=sys_menu.menu_id
)
WHERE parent_id=0 AND is_deleted=0
  AND NOT (COALESCE(menu_type,'')='C' AND COALESCE(component,'') IN
      ('dashboard/workspace/index','dashboard/analytics/index'));

UPDATE sys_menu SET order_num=0
WHERE parent_id=0 AND menu_type='C' AND component='dashboard/workspace/index' AND is_deleted=0;
UPDATE sys_menu SET order_num=1
WHERE parent_id=0 AND menu_type='C' AND component='dashboard/analytics/index' AND is_deleted=0;

DROP TABLE use01_root_order_v301;
