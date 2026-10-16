-- Retired by UI-04. Keep this repeatable migration name and location so existing
-- installations validate successfully. A changed checksum reruns this tombstone
-- after V140; new installations also must not recreate the retired teaching module.
DROP TABLE IF EXISTS example_material;
