# 旧文档入口已迁移

公开正文单一来源是前端仓库 `apps/docs/content/`，部署路径默认同源 `/docs/`。本页及同目录七份旧文件仅保留迁移说明和历史边界；它们不是第二套持续更新的教程。

| 原入口 | 新正文（前端仓库） | 站点路径 |
| --- | --- | --- |
| quickstart.md | guide/quickstart.md | /docs/guide/quickstart |
| versions.md | releases/versions.md | /docs/releases/versions |
| upgrade.md | releases/upgrade.md | /docs/releases/upgrade |
| troubleshooting.md | operations/troubleshooting.md | /docs/operations/troubleshooting |
| extensions.md | development/module-extension.md | /docs/development/module-extension |
| collaboration.md | contributing/gitflow.md | /docs/contributing/gitflow |

获取完整前端文档组合后，按前端仓库 README 运行`pnpm docs:check`，再分别使用`pnpm --filter @xinghui/docs dev`、`pnpm --filter @xinghui/docs build`和`pnpm --filter @xinghui/docs preview`。输出`apps/docs/.vitepress/dist`，由部署者配置实际域名，不把上游站点冒充本项目地址。

当前产品能力和运行边界见后端 [README](../README.md) 与 [已知边界](../KNOWN_ISSUES.md)。内部任务板、QA 日志和临时账户不属于公开源码。
