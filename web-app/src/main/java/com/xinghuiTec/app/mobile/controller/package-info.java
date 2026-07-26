/**
 * 应用端（移动端/用户端）业务控制器包。
 *
 * <p>约定：
 * <ul>
 *   <li>控制器以 {@code Mobile} 前缀命名（如 MobileProfileController），路由统一挂在 {@code /app/**}；</li>
 *   <li>业务实体对应的数据库表使用 {@code app_} 前缀（区别于系统表 {@code sys_}），
 *       表结构变更通过 Flyway 迁移脚本演进（见 sql/README.md）；</li>
 *   <li>请求/响应对象放在 {@code com.xinghuiTec.app.mobile.domain.dto / vo}；</li>
 *   <li>可复用的业务 Service/Mapper 建议下沉到 system-admin 或独立业务模块，本模块聚焦接口层。</li>
 * </ul>
 */
package com.xinghuiTec.app.mobile.controller;
