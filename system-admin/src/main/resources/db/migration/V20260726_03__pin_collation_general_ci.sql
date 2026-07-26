-- =====================================================================
-- 排序规则归一（深度审查确认项）
-- 背景: V1 去除 8.0 专属的 utf8mb4_0900_ai_ci 后未钉住 COLLATE，
-- 同一脚本在 5.7 落 utf8mb4_general_ci、在 8.0 落 utf8mb4_0900_ai_ci，
-- 唯一索引判重与字符串比较语义随环境漂移（0900 不区分重音，general 区分）。
-- 本迁移统一转换为两个版本都存在的 utf8mb4_general_ci，使 schema 与环境无关。
-- 注: BLOB/二进制列不受 CONVERT 影响；已在 5.7 上建立的库本身即 general_ci，转换为无害等价操作。
-- =====================================================================

ALTER TABLE `sys_config`         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_download`       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_file`           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_file_part`      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_job`            CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_job_log`        CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_menu`           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_news`           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_notice`         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_oper_log`       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_oss`            CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_oss_config`     CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_page`           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_role`           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_role_menu`      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_tenant`         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_tenant_package` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_user`           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_user_role`      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
