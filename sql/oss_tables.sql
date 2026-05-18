-- ============================================================
-- OSS 文件管理模块数据库迁移（幂等）
-- ============================================================

CREATE TABLE IF NOT EXISTS `sys_oss_config` (
    `oss_config_id` BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `config_key`    VARCHAR(50)  NOT NULL                COMMENT '配置标识',
    `access_key`    VARCHAR(255) DEFAULT ''              COMMENT '访问密钥',
    `secret_key`    VARCHAR(255) DEFAULT ''              COMMENT '密钥',
    `bucket_name`   VARCHAR(255) DEFAULT ''              COMMENT '存储桶名称',
    `endpoint`      VARCHAR(255) DEFAULT ''              COMMENT '访问端点',
    `domain`        VARCHAR(255) DEFAULT ''              COMMENT '自定义域名',
    `is_https`      CHAR(1)      DEFAULT 'Y'              COMMENT '是否HTTPS',
    `access_policy` CHAR(1)      DEFAULT '1'              COMMENT '桶权限(0=私有 1=公开)',
    `remark`        VARCHAR(500) DEFAULT ''              COMMENT '备注',
    `status`        CHAR(1)      DEFAULT '0'              COMMENT '状态(0正常 1停用)',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`     VARCHAR(64)  DEFAULT NULL            COMMENT '创建者',
    `update_by`     VARCHAR(64)  DEFAULT NULL            COMMENT '更新者',
    `tenant_id`     VARCHAR(20)  DEFAULT '000000'        COMMENT '租户编号',
    PRIMARY KEY (`oss_config_id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OSS配置表';

CREATE TABLE IF NOT EXISTS `sys_oss` (
    `oss_id`          BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `original_name`   VARCHAR(255) NOT NULL                COMMENT '原始文件名',
    `file_name`       VARCHAR(255) NOT NULL                COMMENT '存储文件名',
    `file_suffix`     VARCHAR(20)  DEFAULT ''              COMMENT '文件后缀',
    `url`             VARCHAR(500) NOT NULL                COMMENT '访问URL',
    `size`            BIGINT(20)   DEFAULT 0               COMMENT '文件大小(字节)',
    `platform`        VARCHAR(50)  DEFAULT 'minio-1'      COMMENT '存储平台',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`       VARCHAR(64)  DEFAULT NULL            COMMENT '创建者',
    `update_by`       VARCHAR(64)  DEFAULT NULL            COMMENT '更新者',
    `tenant_id`       VARCHAR(20)  DEFAULT '000000'        COMMENT '租户编号',
    PRIMARY KEY (`oss_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OSS文件记录表';
