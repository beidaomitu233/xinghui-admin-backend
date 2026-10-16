-- V60 is reserved for NTF-01 and must be applied before this migration.
ALTER TABLE `sys_job_fire`
  ADD COLUMN `tenant_id` varchar(20) NOT NULL DEFAULT '000000' COMMENT 'Platform task ownership';
ALTER TABLE `sys_job_fire`
  ADD COLUMN `trace_id` varchar(36) NULL COMMENT 'Safe per-fire correlation ID';
ALTER TABLE `sys_job_fire`
  ADD COLUMN `job_name_snapshot` varchar(64) NULL COMMENT 'Task name at claim time';
ALTER TABLE `sys_job_fire`
  ADD COLUMN `job_group_snapshot` varchar(64) NULL COMMENT 'Task group at claim time';

CREATE INDEX `idx_sys_job_fire_status_time` ON `sys_job_fire` (`status`, `completed_at`, `fire_id`);
CREATE INDEX `idx_sys_job_fire_trace` ON `sys_job_fire` (`trace_id`);
