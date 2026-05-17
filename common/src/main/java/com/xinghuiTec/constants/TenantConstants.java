package com.xinghuiTec.constants;

/**
 * 租户常量信息
 *
 * @author xinghuiTec
 */
public interface TenantConstants {

    /**
     * 超级管理员角色 roleKey
     */
    String SUPER_ADMIN_ROLE_KEY = "superadmin";

    /**
     * 租户管理员角色 roleKey
     */
    String TENANT_ADMIN_ROLE_KEY = "admin";

    /**
     * 租户管理员角色名称
     */
    String TENANT_ADMIN_ROLE_NAME = "管理员";

    /**
     * 默认租户ID
     */
    String DEFAULT_TENANT_ID = "000000";

    /**
     * 全局 Redis Key 前缀（不参与租户隔离）
     */
    String GLOBAL_REDIS_KEY = "global:";
}
