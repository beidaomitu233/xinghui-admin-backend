-- Display and selection labels for existing business codes. Business state rules stay in their owning modules.
INSERT INTO `sys_dict_type` (`type_code`, `type_name`, `enabled`, `remark`) VALUES
  ('notice_type', '公告类型', 1, '1 通知卡片，2 强弹窗；对应 sys_notice.notice_type'),
  ('job_status', '任务状态', 1, '0 启用配置，1 暂停；对应 sys_job.status');

INSERT INTO `sys_dict_item` (`type_id`, `item_value`, `item_label`, `sort_order`, `enabled`, `remark`)
SELECT `type_id`, '1', '通知卡片', 10, 1, '右下角小卡片提示'
  FROM `sys_dict_type` WHERE `type_code` = 'notice_type'
UNION ALL
SELECT `type_id`, '2', '强弹窗', 20, 1, '页面居中弹窗'
  FROM `sys_dict_type` WHERE `type_code` = 'notice_type'
UNION ALL
SELECT `type_id`, '0', '启用配置', 10, 1, '任务配置为可调度状态'
  FROM `sys_dict_type` WHERE `type_code` = 'job_status'
UNION ALL
SELECT `type_id`, '1', '暂停', 20, 1, '任务暂停调度'
  FROM `sys_dict_type` WHERE `type_code` = 'job_status';
