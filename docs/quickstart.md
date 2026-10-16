# 快速开始已迁移

当前正文：前端 `apps/docs/content/guide/quickstart.md`，站点路径 `/docs/guide/quickstart`；环境与配置分别在 `/docs/guide/environment`、`/docs/guide/configuration`。统一入口及构建命令见[README](../README.md)。

历史说明：旧教程使用DB_HOST/DB_PORT/DB_NAME拼接和自动baseline、旧MinIO连接方式；这些不是当前默认。当前配置改为完整DB_URL，baseline false，MAIL/OSS/Druid关闭，JWT_SECRET必须显式提供，CONFIG_MASTER_KEY稳定保存。生产API默认为同源/api，不再按旧mock默认手改dist。

[实际应用配置](../system-admin/src/main/resources/application.yml)和[主数据源](../system-admin/src/main/resources/application-datasource.yml)是核对依据。旧空库/初始化账号安全处理原则仍适用，具体可运行PowerShell/Bash只维护新正文，原命令在Git历史中保留供历史版本追溯。
