# GitFlow 文档迁移入口

完整协作指南统一到前端 `apps/docs/content/contributing/gitflow.md`，文档站路径 `/docs/contributing/gitflow`；源码贡献流程在 `/docs/contributing/contribution`。旧指南的详细任务板/窗口材料不进入公开站点。

当前约定仍为后端 `dev`、前端 `develop`，两仓的feature/fix/release/hotfix独立提交、准确SHA配对后串行集成。提交前检查remote归属与自有工作树，不覆盖既有用户分支。合入需要实际必需CI成功；本地通过、skipped或有仓库写权限不代表正式推送/发布授权。

源码依据：[后端CI](.github/workflows/ci.yml)、[汇总门槛](scripts/quality/check-needs.mjs)。前端CI和`check-needs.mjs`在独立前端仓库。文档构建命令以配套前端仓库 README 为准。

历史说明：旧文件中的阶段任务安排和候选练习记录只反映当时状态，不能用作当前主线/Tag承诺。新教程只维护单一正文。
