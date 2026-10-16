-- All DATETIME values below are UTC instants serialized as UTC wall time.
ALTER TABLE `sys_job`
  ADD COLUMN `next_fire_time` datetime(3) NULL COMMENT 'Next scheduled UTC instant';
ALTER TABLE `sys_job`
  ADD COLUMN `revision` bigint(20) NOT NULL DEFAULT 0 COMMENT 'Configuration revision';
CREATE INDEX `idx_sys_job_due` ON `sys_job` (`status`, `next_fire_time`);

-- This ledger is the cluster claim and recovery source, separate from JOB-03 display logs.
CREATE TABLE `sys_job_fire` (
  `fire_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `job_id` bigint(20) NOT NULL,
  `job_revision` bigint(20) NOT NULL,
  `fire_key` varchar(120) NOT NULL,
  `trigger_type` varchar(16) NOT NULL,
  `scheduled_fire_time` datetime(3) NULL,
  `status` varchar(16) NOT NULL,
  `attempt_count` int(11) NOT NULL DEFAULT 1,
  `claim_token` varchar(36) NULL,
  `claimed_by` varchar(100) NULL,
  `lease_until` datetime(3) NULL,
  `started_at` datetime(3) NULL,
  `completed_at` datetime(3) NULL,
  `result_summary` varchar(200) NULL,
  PRIMARY KEY (`fire_id`),
  UNIQUE KEY `uk_sys_job_fire_key` (`fire_key`),
  KEY `idx_sys_job_fire_job` (`job_id`, `fire_id`),
  KEY `idx_sys_job_fire_lease` (`status`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable job claim and result ledger';
