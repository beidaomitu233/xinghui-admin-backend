-- Preserve invoke_target for audit only. No legacy row receives a handler_key.
-- Pausing all preexisting rows makes an old active row inert before JOB-02 exists.
ALTER TABLE `sys_job`
  MODIFY COLUMN `invoke_target` varchar(500) NULL COMMENT 'Legacy target, audit only; never executable';
ALTER TABLE `sys_job`
  ADD COLUMN `handler_key` varchar(100) NULL COMMENT 'Explicitly registered safe handler key';
ALTER TABLE `sys_job`
  ADD COLUMN `handler_params` varchar(4096) NOT NULL DEFAULT '{}' COMMENT 'Validated JSON object';
ALTER TABLE `sys_job`
  ADD COLUMN `time_zone` varchar(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'IANA time zone';

UPDATE `sys_job` SET `status` = '1', `handler_key` = NULL;
CREATE INDEX `idx_sys_job_handler_status` ON `sys_job` (`handler_key`, `status`);
