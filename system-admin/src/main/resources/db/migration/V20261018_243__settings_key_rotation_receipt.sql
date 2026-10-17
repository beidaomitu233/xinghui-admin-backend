-- Internal receipt only; credentials remain in the encrypted configuration material table.
ALTER TABLE sys_ai_settings_test_intent ADD COLUMN initiated_by VARCHAR(64) NULL;
ALTER TABLE sys_ai_settings_test_intent ADD COLUMN rotation_from_version BIGINT NULL;
ALTER TABLE sys_ai_settings_test_intent ADD COLUMN rotation_receipt_json LONGTEXT NULL;
