# 故障排查已迁移

当前正文：前端 `apps/docs/content/operations/troubleshooting.md`和`reference/error-codes.md`，站点路径 `/docs/operations/troubleshooting`、`/docs/reference/error-codes`。统一入口见[README](../README.md)。

历史说明：旧页关于共享服务不可清空、迁移需核对结构/history的原则仍适用，但“默认自动baseline到2”和旧外部mock默认已过期。现在非空无history不会默认接受baseline，登录态Redis故障HTTP503与认证HTTP401区分；Controller局部HTTP码和全局业务码也不能混称。

源码依据：[LoginFilter](../system-framework/src/main/java/com/xinghuiTec/filter/LoginFilter.java)、[全局错误处理](../common/common-core/src/main/java/com/xinghuiTec/exception/GlobalExceptionHandler.java)、[应用配置](../system-admin/src/main/resources/application.yml)。原排查阶段记录在Git历史，新文档按当前API/配置统一维护，不公开私有QA证据。
