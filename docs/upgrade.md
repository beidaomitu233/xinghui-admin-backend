# 升级与恢复已迁移

当前正文：前端 `apps/docs/content/releases/upgrade.md`、`operations/backup-restore.md`、`reference/database-migrations.md`，站点路径 `/docs/releases/upgrade`、`/docs/operations/backup-restore`、`/docs/reference/database-migrations`。统一入口见[README](../README.md)。

历史说明：旧教程专指NTF-02后预览到NTF-03后预览（后端a3da37f→6e5c523、前端00e9093dd→b6535d7e4），只验证当时V90组合，不代表含V170当前候选或完整灾备。它关于严格future校验、恢复到受控空schema、配套产物及旧对象恢复未验证的边界继续保留在Git历史。

当前应用默认ignore-migration-patterns=['']、自动baseline关闭、同源/api；不再推荐通过手改旧mock产物得到可用地址。升级仍需真实history/结构审查、备份及主密钥/对象/代码成套恢复；不能删history或随意repair。依据是[配置](../system-admin/src/main/resources/application.yml)和[迁移目录](../system-admin/src/main/resources/db/migration)，完整可执行步骤只维护新正文。
