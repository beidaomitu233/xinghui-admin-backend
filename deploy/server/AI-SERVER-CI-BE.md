# AI-SERVER-CI-BE / B 交付与源码审查

2026-10-20。后端仓库 `beidaomitu233/xinghui-admin-backend`；Gitflow 目标为 feature → dev PR，由 PM 串行集成到 dev/master。B 不合并主干，不执行 SSH 或服务器部署。

## 变更

- 从已审查提交 `949002324f01992df4774180214d9915b64bad27` 的 `deploy/server/templates/backend-workflow.yml.example` 复制唯一 `.github/workflows/server-deploy.yml`。除首行说明从“未激活模板”改为实际 publisher 说明外，工作流内容保持一致。
- 本阶段仅新增此工作流和本说明；949 的部署工具、门禁、Dockerfile、模板与配置示例保持不动。到 dev 的 PR 同时携带该已审查的前序快照，因为 dev 尚未包含它。
- 本仓 repository var `SERVER_DEPLOY_ENABLED` 设置为字符串 `false`；变量未设置时条件也不成立。生产 Secrets 不读取、不配置，其余 repo vars 由 PM 统一填写。

## 源码审查

1. 触发器仅为本仓 `CI` 的 master `workflow_run completed`，不是 PR 直接发布。默认分支为 master；工作流合入 dev 的 PR CI 不等于 publisher 已运行，必须等 PM 合入 master 且对应 push CI 成功。
2. publisher 再要求 CI conclusion success、push、正确 head_repository 与 master；固定 SHA tooling 中的 `verify-ci.py` 进一步核对 CI 路径、精确 head_sha、当前 master 和全部现有必需 job。tooling/base image pins 缺失时失败，不回退到可变分支/latest。
3. checkout actions/setup-java 使用既有模板固定 SHA；tooling checkout 使用 repository var `DEPLOY_TOOLING_SHA`，source checkout 使用已通过 CI 的精确 head_sha；均不持久化 checkout token。
4. publisher 在 GitHub-hosted runner 重跑同 SHA 必需打包测试和报告门禁，发布 linux/amd64 image@digest，将 digest/SHA/CI URL 写 Actions summary；具有 packages:write，但没有 production Environment 或服务器 SSH secrets。
5. deploy job 必须 `vars.SERVER_DEPLOY_ENABLED == 'true'` 且依赖 publisher 成功。缺失/false 会跳过整个 job；因此当前不会调用 SSH，也不需要配置服务器地址或密钥。未来启用后，部署 job 仅通过 production Environment Secrets 获取 SSH 参数，并再次校验源 CI。
6. production Environment 默认保护分支限制和 Secrets，模板不新增人工审批；固定工具、资源隔离、首次 bootstrap 和迁移恢复边界仍按 `SERVER_CICD_MIGRATION.md`。

## 配置责任与验收边界

PM 在实际 master publisher 前填写 repo vars `DEPLOY_TOOLING_SHA`、`SERVER_JDK_IMAGE`、`SERVER_JRE_IMAGE`（真实 reviewed digest），保留 `SERVER_DEPLOY_ENABLED=false`。需要的工具快照仍为 949 完整 SHA，不能把 workflow 自身 HEAD 或最新分支当作未经审查的工具替代。实际源应用 SHA 来自触发的 CI，不是 949 工具提交。

首次 Actions 成功发布后，从 summary 获取真实镜像 digest 和 CI 收据。用户完成本机 bootstrap 及验收后再由 PM 协调置开关 true；本阶段不切换它、不安装软件、不创建线上 schema/Redis/bucket、不发真实模型请求。

## 验证与交付记录

- 本地 YAML 解析、与模板逐行一致性（仅首行差异）、触发器/源门禁/开关/权限/checkout pin 静态检查、Git 空白检查。
- 前序 949 的离线门禁与状态验证已有 `DELIVERY.md` 收据，本阶段不为复制文件重跑 Java/镜像/服务器验证。
- 推送仅允许已经核对的 `origin=https://github.com/beidaomitu233/xinghui-admin-backend` 与自有 feature；PR base dev，不推上游、不直接更新 dev/master。
- PR URL、实际 CI run/结论和分支提交 SHA 在本任务最终收据及 PM 协调消息中报告，不把尚未运行的 publisher 记为通过。
