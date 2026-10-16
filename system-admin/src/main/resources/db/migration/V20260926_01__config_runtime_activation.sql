-- Existing captcha rows remain governed by the deployment setting until edited.
ALTER TABLE `sys_config`
  ADD COLUMN `runtime_active` tinyint(1) NOT NULL DEFAULT 0
    COMMENT '0: legacy deployment setting, 1: managed value is effective';
