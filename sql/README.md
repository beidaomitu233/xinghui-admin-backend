# 数据库说明与文档迁移入口

完整迁移/升级/恢复指南统一到前端 `apps/docs/content/reference/database-migrations.md`、`releases/upgrade.md`、`operations/backup-restore.md`，站点路径分别为 `/docs/reference/database-migrations`、`/docs/releases/upgrade`、`/docs/operations/backup-restore`。本目录只说明历史SQL与真实源码位置，不维护另一份操作教程。

## 当前初始化来源

- [应用配置](../system-admin/src/main/resources/application.yml)：DB_URL/DB_USERNAME/DB_PASSWORD同时供Flyway和dynamic-datasource主库。应用不会创建schema。
- [默认版本迁移](../system-admin/src/main/resources/db/migration)：22份versioned脚本，最高V20261013_170菜单归并。
- [历史optional清理](../system-admin/src/main/resources/db/optional-migration)：曾使用该location的库必须保留校验路径；R不再创建资料示例。

当前baseline-on-migrate=false、baseline-version=2仅供经审查的历史基线、validate-on-migrate=true、out-of-order=false、ignore-migration-patterns=['']、clean-disabled=true。非空且无history的库不能默认接受V2基线；需要单独结构审查、备份和明确迁移方案。MySQLDDL可能部分提交，不能认为失败自动全部回滚。

## 历史说明

`sql/xinghui_admin.sql`是旧导出，不作为当前初始化方式。旧说明中的DB_HOST/DB_PORT/DB_NAME拼接、默认自动baseline=true及“V100未执行/当前最高V90”是历史状态，已过期；原文通过Git历史保留。V130建立网站表与后续退役链路也保留历史脚本，不能删除成功迁移使history失配。

当前质量候选在MySQL8.0.12验证默认22份加optional R共23条history、严格重启校验；这不是所有环境应固定23行的规则。5.7/8.0容器矩阵与存量灾备演练未在本轮文档任务执行。禁止通过随意repair/clean/删history/删库绕过失败，恢复方案见统一文档中心。
