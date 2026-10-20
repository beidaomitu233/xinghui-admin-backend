# AI-PI-DEPLOY / B 静态交付收据

2026-10-20；分支 `feature/AI-PI-server-deploy`，基线 `origin/master` = `307074f3ca5fe9f081c7391cdd7f5ff1f86cb497`。仅修改 `deploy/server/` 与根 `SERVER_CICD_MIGRATION.md`。旧任务分支保留，未修改其他成员文件、业务源码、Pages 或活动 CI。

## 已交付

- 服务器只读探测脚本与说明。
- 真实后端/前端/文档的镜像构建定义、host-network Compose、双层 Nginx 反代示例。
- 两个未激活的工作流模板：精确 CI SHA/全部必需 job 门禁、固定 tooling 与镜像 digest、发布/部署权限分离。
- 本机 root 部署执行器：跨仓库 flock、一对不可变组件收据、仅单组件替换、健康/路由/served revision 校验、静态前端恢复和后端失败阻断。
- 执行中持久化标记：进程中断遗留标记时拒绝下次发布，等待人工核查。
- 固定 host key 的 SSH 客户端与 forced command；服务器配置占位示例；完整迁移、首次验收与恢复说明。

## 本地验证

全部使用已有工具，无下载依赖、镜像拉取、Docker 启动或服务器连接：

| 检查 | 结果与边界 |
| --- | --- |
| Git Bash `bash -n` | 4 个 shell 脚本通过，仅语法 |
| Python `-B deploy/server/test_gates.py` | 3 个测试方法通过：两个真实 CI profile 的门禁负例、收据拒绝负例、5 种模拟发布状态；无外部执行 |
| Java17 `javac --release 17` | HealthProbe 编译成功；未连接实际接口 |
| 已有 yaml 2.8.2 `parseDocument` | Compose、两个 workflow 模板语法通过；现有依赖因 ACL 用只读提升权限访问，未改 ACL/依赖 |
| 首发开关静态检查 | 两模板只有 deploy job 受 `vars.SERVER_DEPLOY_ENABLED == 'true'` 控制；publisher 无 production Environment，digest 写 Actions summary；变量缺失/false 不匹配 true |
| `docker compose ... config --no-env-resolution --no-interpolate -q` | 通过；无需 daemon，不读真实 backend.env，不拉镜像。本机 CLI Compose 为 5.5.1，服务器报告 2.32.1，使用的 raw 特性最低 2.30 |
| `git diff --cached --check` | 提交前执行，排查空白错误 |

门禁测试覆盖 PR/dispatch/fork/错误分支/错误 SHA/错误 workflow/旧分支头/失败或缺失或重复或跳过的必需 job，以及 mutable tag/其他镜像仓库/参数注入。状态模拟覆盖成功更新保留另一组件、web 失败恢复、恢复也失败阻断、backend 失败不自动降级、bootstrap 失败不发布 current。flock 在测试中模拟，未宣称验证了真实 Linux 多进程互斥。没有运行 actionlint、Nginx 二进制检查、真实 Docker build、Maven/pnpm 构建或重跑全项目 CI；这些属于后续受控验证阶段。

## 尚未验收

本交付不是已上线结果。域名/SSH/可信指纹、候选端口空闲、python3 版本、稳态容量、旧站点用途与目标独立数据库/Redis/bucket 预留、基础镜像真实 digest、Secrets/vars、初始兼容清单和备份恢复演练仍待确认。服务器已有服务保持原样，Pi 未启动，AIP-032 仍未解决，未新增模型请求。

PM 协调 Gitflow 合入后可先激活 publisher，保持 repository var `SERVER_DEPLOY_ENABLED=false`（默认），仅 Actions 产出镜像和 summary 收据；首次服务器 bootstrap 验收后置 true 才启用自动 SSH。production Environment 默认只限制保护分支并提供 Secrets，不强制逐次 reviewer。服务器操作由用户按 PM 最终命令执行。
