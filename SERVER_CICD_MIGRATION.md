# AI-PI-DEPLOY：服务器部署迁移方案（待评审，尚未启用）

负责人 B；2026-10-20。本交付只增加 `deploy/server/` 和本文件。没有执行服务器命令、安装软件、构建/拉取镜像、修改现有站点或启用 GitHub 工作流。Pi 服务尚未实现，本方案不启动 Pi。

## 1. 已有证据与待确认项

PM 转交的服务器输出：OpenCloudOS 9.6、Linux 6.6、x86_64；Docker 28.0.1、Compose 2.32.1、Nginx 1.30.3 已有；bash/curl/python3/flock/openssl 可用，未发现 jq。内存总 3655 MiB、当时 available 1213 MiB，swap 3072 MiB；磁盘余 16 GiB。这些是一次采样，不代表持续容量。

现有 Nginx 占用 80/443/7799，旧 Java 只监听 127.0.0.1:7798，宿主 MySQL 3306，宿主 Redis 只监听 127.0.0.1:6379，已有 MinIO 容器映射 9000/9001。旧 Java、数据库及站点用途尚未确认；不得替换、重启或删除它们。

待 PM 收齐后才能定稿和激活：

- 域名/公网入口、现有 TLS 站点归属与配置文件位置、SSH 地址及可信公钥指纹。
- 部署前复核 `17799` 和 `18180` 空闲；端口修订及历史快照边界见下节。Compose 使用 host network，不设置 ports 映射。
- python3 实际版本（要求 3.9+）、Docker/Compose 命令可用性、SELinux 对现有 Nginx 反代的限制。遇拒绝时定位日志，不能直接关闭 SELinux。
- MySQL 版本、目标 schema、旧 7798 是否使用同一 schema；现有数据备份及恢复演练、Flyway 历史与权限。未经确认不能让新后端迁移旧库。
- Redis 实际认证/逻辑库及与旧服务的键空间兼容性，MinIO bucket/权限/endpoint（开启 OSS 时），配置加密主密钥来源。敏感值留在服务器，不回传聊天。
- 持续内存、磁盘和上传容量；新服务能否承受 896 MiB 容器上限及镜像拉取空间。所有构建只在 GitHub-hosted Actions。Pi 未来资源预算另评审。
- JDK/JRE/Nginx 基础镜像的真实 digest、安全扫描及平台/用户兼容性；本交付没有编造 digest。
- 两个仓库 protected branch、production Environment 分支限制与 Secrets、镜像仓库写权限和部署专用账号归属。

## 2. 实际应用与路由

```text
浏览器 HTTPS → 现有宿主 Nginx（确认的站点）
                      → 127.0.0.1:18180 新 web 容器
                         /         真实 web-ele 生产应用
                         /docs/    同一前端提交的 VitePress 文档
                         /api/* → 127.0.0.1:17799 新后端（去除 /api 前缀）
                                               → 现有 MySQL/Redis/MinIO
```

采用 **Linux host network**，原因是宿主 Redis 只绑回环；`host-gateway` 无法使容器访问宿主 loopback。Java 强制 `SERVER_ADDRESS=127.0.0.1`、`SERVER_PORT=17799`；容器 Nginx 强制 `listen 127.0.0.1:18180`，不使用宿主 80/443/7798/7799/18080。host network 共享宿主网络命名空间，容器能访问宿主其他回环服务，因此仅运行审查后的可信镜像，不能把此模式视为网络隔离。保留 Redis bind 和认证。部署前核对监听地址；既有 MinIO 的公网暴露属于现状，不在本任务中改动。

### AI-SERVER-PORT：已有安装的端口修订

PM 恢复前实查：其他项目新增 FlyMind Java 服务占用 `127.0.0.1:18080`，MemAvailable 当时为744MiB；本项目失败后端已停止，专用库仍0表，旧BLOCKED/IN_PROGRESS未归档或删除。本次仅从`origin/dev=b439d1330f05043a7e3f52fcefcda18316653e40`准备web端口`18180`源码修订，backend仍`17799`。PM已询问用户资源选择；本变更不改内存/CPU上限，不停止或操作FlyMind，不执行服务器动作。

| 关联 | 本次处理 |
| --- | --- |
| `nginx.conf` | 容器/host network监听127.0.0.1:18180；API上游17799不变 |
| `Dockerfile.web` | EXPOSE与wget健康检查成套改18180，健康条件/权限不变 |
| `deploy.py` + policy example | 固定端口约束与HTTP探测改18180；旧18080 policy在Docker/状态写入前被拒绝 |
| host Nginx location example | 仅本项目域名上游改127.0.0.1:18180 |
| `check-nginx.sh` + `test_gates.py` | 定向检查访问18180；保留原模拟状态/安全门禁并验证旧policy拒绝 |
| `compose.yaml` | host network无端口映射，保持原样；后端/资源/只读与cap_drop约束不变 |
| publisher/package/SSH/backend probe | 无固定web端口项，不改工作流、规则或后端探针 |
| 283旧工具、handoff与既有哈希收据 | 保留历史，不更新为新端口，不把旧收据当成新版验证 |

**升级依赖**：需要包含本修订的新reviewed tooling完整SHA，并用它构建/发布新web镜像取得新digest；旧`60649fb9...`web镜像内仍监听/检查18080，不能改policy后直接复用。新SHA/digest由后续PM推进Gitflow/Actions后记录，本次不虚构或触发发布。原583项应用测试不因端口修订重跑。

已有服务器不能重跑旧handoff初始化器来覆盖目录。PM按用户资源答复落实容量后，审查旧/新Git blob及bytes/hash，先在新私有备份位置保存旧工具/policy/本项目站点配置与状态证据，再制定仅本项目工具的原地升级：新`deploy.py`、policy端口18180、已审核的新web digest、本项目域名反代18180；不覆盖backend.env、数据库/密钥/SSH身份，不改其他站点。镜像内配置随新web镜像更新，不能只改宿主文件冒充镜像升级。两仓DEPLOY_TOOLING_SHA的更新由PM统一处理，SSH开关仍false。

BLOCKED/IN_PROGRESS的归档、空库事实确认和受控恢复仍由PM独立审查执行，本补丁不自动清理。恢复后核对18180的应用/docs/API与release JSON及真实镜像digest；18080归其他项目，不作为本项目健康地址。实际容器/Nginx定向检查待后续允许Actions时执行，本次仅语法、离线gates和范围审查。

本地核查：4个离线gate测试方法通过（含5种原有模拟发布状态和旧端口policy拒绝）；Python/JSON解析与check-nginx.sh的bash-n通过；6个运行/配置文件与基线逐字节比较仅18080→18180。原compose资源、SSH、backend探针/Dockerfile、package/workflows和历史交付未改。初次沙箱TEMP权限错误后，仅提升权限重跑mock gates，不改ACL。未运行Docker/完整583项应用测试/CI或服务器操作。

后端 Java 17、Spring Boot 3.3.6，实际可执行包为 `system-admin/target/system-admin.jar`，没有 Actuator。原应用端口 7799 在此部署中显式改为 17799，无 context-path。原始 Authorization 头照常透传，不擅自改成 Bearer。

前端为 `apps/web-ele/dist/`，生产 API 必须 `/api`；`scripts/build-quality.mjs` 对运行时 `_app.config.js` 做现有校验。文档 `apps/docs/.vitepress/dist/`，`DOCS_BASE=/docs/`。Pages 的 `site/`、demo、dist-demo、外部 mock API 不作为生产制品。

`nginx.conf` 与 `templates/host-nginx.conf.example` 保留流式代理所需的关闭 buffering、长连接超时，上传上限匹配现有请求上限 1000 MiB。后端 `/tmp` 是专用 Docker volume，上传临时数据需要磁盘监测；不把它当数据库备份。日志轮转 10 MiB × 5/容器。SPA fallback 与 `/docs/` 404 分开。

## 3. 文件与资源

| 文件 | 用途 |
| --- | --- |
| `probe.sh`, `PROBE.md` | 只读探测，已有结果由 PM 汇总 |
| `Dockerfile.backend`, `HealthProbe.java` | 复制已测试 JAR；独立 Java 小探针 |
| `Dockerfile.web`, `nginx.conf` | 真实应用和文档镜像，非 root UID 101 |
| `compose.yaml` | 仅 backend/web，host network、只读根目录、drop ALL、资源上限 |
| `verify-ci.py` | 校验源仓库、CI 路径、push、主分支、精确 SHA、全部必需 job 和当前分支头 |
| `package.sh` | Actions 打包和发布，返回本次 buildx digest |
| `notify-server.sh`, `ssh-entry.sh` | 固定 host key 的 SSH 与受限命令 |
| `deploy.py` | 标准库 Python，串行清单更新、镜像收据和 HTTP 检查 |
| `templates/*workflow.yml.example` | 未激活的两个仓库工作流模板 |
| `test_gates.py` | 离线门禁拒绝用例，无网络或 Docker |

后端上限 768 MiB / 1 CPU，web 128 MiB / 0.5 CPU。JVM 建议 `MaxRAMPercentage=65`，约 499 MiB heap，余量供 metaspace/线程/direct buffer；真实负载仍需压测。现有 available 1213 MiB 扣除上限只余约 317 MiB，容量**尚未实测**；执行前检查资源，不把 swap 当作满足上线容量。Java `ExitOnOutOfMemoryError` 配合容器重启，不能代替容量监测。暂不运行 Pi，也不在此服务器执行 Maven/pnpm/Docker build。

基础镜像要求：受审查的 Java 17 JDK/JRE linux/amd64（如 Temurin 的适当变体）；web 需要 `nginxinc/nginx-unprivileged` 类 UID 101、含 wget、支持此配置的 linux/amd64 镜像。三个 Docker ARG 无默认值；工作流只接受 `repository@sha256:64hex`。实际 digest 确认后填 Actions vars，不能填 latest。

## 4. 测试到制品的绑定与两个仓库协调

每个仓库独立监听其自己的 `CI` workflow_run。GitHub 的 workflow_run 不是跨仓库自动触发机制。后端 master 与前端 main 分别执行模板；不能把 Pages 完成、PR、fork、workflow_dispatch、失败/跳过 job 或旧 SHA 当作上线依据。

`verify-ci.py` 从 GitHub API 重新读取指定 run 与最新 job，要求 `.github/workflows/ci.yml`、push、正确源仓库、精确 head_sha、所有现有必需 job 成功，并验证该 SHA 仍是受保护分支头。在构建前、镜像发布后且发送 SSH 前各检查一次。分支若在最后检查后发生变化仍存在很短的时间窗口；这是按已通过 CI 的精确提交发布，不承诺与远端分支移动原子同步。

后端必需 job 包括主打包测试、MySQL 5.7/8.0 服务矩阵和 Backend quality gate。现有 CI 未提供 JAR artifact，所以模板会**对同一 SHA 再运行现有 required-tests + inventory/report gates**，随后复制本次测试生成的 JAR，记录其 SHA256。没有 skipTests；不谎称镜像字节与第一次 CI 的 JAR 相同。服务矩阵仍由原 CI 门禁证明，不能删减它。

前端要求 Root and application tests、typecheck、lint、真实生产应用构建、文档构建与 Frontend quality gate 全成功，再以 Node 22 / pnpm 10.28.1 frozen lockfile 对同一 SHA 重建应用和文档。SHA 与 CI run ID 同时写入 OCI labels；web 还提供 `/__release.json`。镜像以 buildx 返回的 digest 部署，不查询 latest 或可变标签。重建可能产生不同 digest，每次用本次真实结果。

两个仓库的 workflow concurrency 各自排队；真正的跨仓库互斥在服务器 `flock`。`current.json` 固定保存一对 backend/web 的 image@digest、source SHA、CI run。单组件更新读取当前另一组件，不推断“对方最新版本”，成功后原子替换整对清单。初次发布必须人工审查一对兼容收据，服务器只允许本机 root bootstrap。自动单组件发布要求 API 保持向后兼容；破坏兼容的变更由 PM 暂停自动发布并安排成对窗口，不能单靠这把锁解决接口兼容。

OCI labels 是一致性检查，不是密码学签名。安全性依赖受保护分支、受限 registry writer、固定 tooling SHA、Environment 和 SSH 权限；如需制品签名/attestation 应另行设计，不宣称已具备。

## 5. 激活前的配置清单（现在不执行）

### 5.1 服务器路径与权限

用户在地址、端口、容量、库和域名审查通过后，按 PM 最终命令单操作。这里是安装约定，不是当前授权运行的步骤：

- root 管理 `/opt/xinghui/`（0755）及其中 `compose.yaml`、`deploy.py`、`ssh-entry.sh`；文件 root:root，普通账号不可写。
- Compose project 固定为 `xinghui-server`；安装前核对该 project/同名 volume 没有归属现有服务，避免名称碰撞。
- `/etc/xinghui/` 0700；实际 `backend.env` 和 `deploy-policy.json` root:root 0600，从 example 私下填写。`backend.env` 是 Compose raw env file，值不加 shell 引号，字面 `$` 保留；不能执行 `source backend.env`。
- `/var/lib/xinghui/`、`releases/` 0700；`current.json` 0600；单锁 `/var/lock/xinghui-deploy.lock`。目录需在同一可靠本地文件系统，原子 rename 不能替代外部备份。
- 专用 SSH 账号仅用于此部署，不加入 docker 组，不授予通用 sudo。禁密码登录，authorized_keys 使用 `restrict,command="/opt/xinghui/ssh-entry.sh"` 加此部署公钥；其余 key 不得绕过 forced command。
- 用 `visudo` 单独校验最小 sudoers 项：`xinghui-deploy ALL=(root) NOPASSWD: /usr/bin/python3 -I /opt/xinghui/deploy.py --remote *`（账号名与最终选择一致）。保留 sudo 默认 env_reset/secure_path，不允许 SETENV。Python 使用 `-I`，脚本再次校验所有 argv，远端路径不能 bootstrap，绝不 eval 请求。
- 如 GHCR 镜像私有，仅在服务器 root Docker credential store 配置只读 packages token；保留文件 0600，不把 token 放镜像、脚本、清单、聊天或命令历史。没有自动 registry 登录安装步骤。

实际后端变量：`DB_URL/DB_USERNAME/DB_PASSWORD`；容器里显式 `SPRING_DATA_REDIS_HOST=127.0.0.1`，另有 `REDIS_PORT/DATABASE/PASSWORD`；`JWT_SECRET` 至少 32 UTF-8 字节；已有加密数据必须沿用 `CONFIG_MASTER_KEY`（32 字节 base64）。启用对象存储使用 `OSS_*`，不能直接套用旧 QA 的 `MINIO_*`。`MAIL_ENABLED/DRUID_ENABLED` 默认 false。租户、邮件等业务配置必须按原环境核对，不把迁移当作启用功能的理由。

独立演示使用**经用户预留的独立 MySQL schema、独立 Redis 逻辑库、独立 MinIO bucket（或已验证不会相撞的受控 prefix）**。Redis example 故意不给 DB0 默认值；由现有服务负责人核查占用、Redis database 数量及应用缓存行为，再在服务器私下填写，不能仅凭“通常用 DB15”占用它。若现有 Redis 不支持所需隔离，先由 PM 决定方案，不能改旧实例 bind/认证或清空它。独立新库使用独立 JWT 签名密钥与新生成主密钥；迁移已有加密数据则保留其原主密钥，签名密钥变动会使旧会话失效，需要切换计划。不得混用新主密钥与旧密文。本交付不创建任何这些线上资源。

DB JDBC TLS 参数需与实际 MySQL/证书匹配，example 的 useSSL 不是连通性证明。AIP-032 模型出口 TLS 问题未解决；不得在部署中关闭证书校验，也不额外发真实模型请求。

### 5.2 GitHub

两个模板保存在 deploy/server，当前不位于 `.github/workflows`，因此不生效。按 Gitflow 合入后分别复制为两个仓库的 `.github/workflows/server-deploy.yml`，先保持 repository var `SERVER_DEPLOY_ENABLED=false`（未配置也等价 false）。此阶段只启用 CI 后的 publisher，生成已验证镜像及 Actions summary 中的真实 digest/SHA/CI 收据，不发送 SSH。首次用户本机 bootstrap 验收后再将该变量置为字符串 `true`，之后自动部署；无需手工本地打包。后端模板保留 master，前端模板保留 main。启用前重新核对现有 CI job 显示名称及 action 固定 SHA。

两个仓库 vars：`DEPLOY_TOOLING_SHA` = 本次部署工具经过审查并进入后端可读取仓库的完整 40 位提交；`SERVER_JDK_IMAGE`、`SERVER_JRE_IMAGE`、`SERVER_NGINX_IMAGE` 按组件填写真实 pinned digest。tooling checkout 固定提交，不能指向未审查的后端最新分支。本模板假定 GitHub token 可读取后端 tooling；若后端私有，前端跨仓库 checkout 需要另行配置限定该仓库的只读令牌并审查，不能直接启用失败的模板或扩大通用 PAT 权限。

上述非敏感 pin 与 tooling SHA 必须配置为**repository vars**：publish job 没有 environment，不能从 production Environment vars 获取这些值。生产 SSH 参数使用下述 Environment secrets，不放 repository vars。

分别配置 `production` Environment：默认限定受保护主分支部署并保存 Secrets，不要求逐次人工 reviewer，保持自动 CI/CD；未来团队可自行选择 reviewer 政策。放置 `DEPLOY_HOST`、`DEPLOY_PORT`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`、`DEPLOY_KNOWN_HOSTS`。公钥指纹通过服务器可信控制台核对；known_hosts 要覆盖真实 host/port（非 22 时为 `[host]:port`），禁止在工作流内用未经核验的 ssh-keyscan 生成信任，也不使用 accept-new。发布 job 仅 packages:write，部署 job 不得有 packages:write；生产 SSH secrets 只在 Environment 的部署 job 提供。

### 5.3 初次发布与入口切换

1. 确认目标库和旧服务关系；完成备份、恢复演练与 Flyway SQL 审查。保持 baseline-on-migrate false、validate true、clean-disabled true，不运行 clean/repair/自动 baseline。
2. 按 5.2 激活工作流的 publisher，两个仓库均保持 `SERVER_DEPLOY_ENABLED=false`。由 Actions 发布两个已通过 CI 的不可变镜像，从 summary 记录 digest/SHA/CI URL，填写服务器私有的 `initial-release.json`。example 的 REPLACE 值应被拒绝。
3. 执行前检查独立 schema、备份和实时资源。用户本机 root 执行 `/usr/bin/python3 -I /opt/xinghui/deploy.py bootstrap /root/initial-release.json`，先拉取校验镜像，再启动后端（此时可能执行 Flyway）、web，检查全部健康与应用/文档/API/served revision。用户已授权部署，本步骤不另加审批流；目标库与旧服务关系仍必须确认。
4. 本机检查 `ss -ltnp` 新端口只在 127.0.0.1；核对容器健康、日志、内存、数据库迁移历史与业务配置。健康检查是现有匿名 capability 接口的有限 HTTP/配置读取检查，不证明全部 MySQL/Redis/MinIO 操作正常；WeChat DISABLED 是合法配置，CONFIG_UNAVAILABLE 不可通过。
5. 按现有站点配置备份，将 `host-nginx.conf.example` 的 location 合入确认的 TLS server，用户先执行现有 Nginx 的配置检查，成功后按现有运维方式 reload。不要覆盖全量配置、抢占已有 listener 或关闭 TLS。确认 HTTPS 证书、真实前端、docs、登录鉴权和已授权的非模型业务流程；外部探测不包含真实模型调用。
6. 首次验收后将两个仓库 repository var `SERVER_DEPLOY_ENABLED` 置为字符串 `true`，后续主分支 CI 成功即自动发布并 SSH 部署。保留 Pages 直到迁移验收并由 PM 协调停用。旧 Java/站点不在本任务里停机或删除。

后续 SSH 只接受 `deploy backend|web sha256:<64hex> <40hex-source-sha> <numeric-ci-run>`。服务器不接收 caller 自选镜像仓库或 Compose 命令，不构建、不拉“另一仓库最新版本”。升级有短暂重建停机，不承诺零停机或蓝绿部署。

## 6. 故障、恢复和留存

- 所有尝试保存 `requested.json`、上一对清单、成功/失败记录；成功才写 `current.json`。执行日志避免输出 backend.env，诊断 docker inspect 也可能泄露环境，回传时只提供选定状态字段。
- 启动容器前持久化 `IN_PROGRESS.json`，成功或静态前端成功恢复后才清除。SSH/进程中断遗留该文件时，即使没有异常处理生成的 BLOCKED，也拒绝下一次发布，由本机运维核查实际容器、清单和迁移状态后留档解锁。
- web 更新失败：恢复前一个 web digest、保持 backend，重新健康和 HTTP 检查；恢复也失败则生成 `BLOCKED.json`，禁止后续自动更新。
- backend/bootstrap 失败：可能已经发生 Flyway 变更，写 `BLOCKED.json` 并终止；不自动降级后端/回滚数据库，不伪报旧清单就是实际运行状态。失败后容器可能仍运行，需要本机运维根据日志决定停止/修复，不能继续自动切换。记录的 current 是最后验收版本，失败现场以容器/迁移历史为准。
- 解锁由 root 在审查本次 SQL、数据库备份恢复策略和旧 JAR 兼容性后执行。恢复可选择修复新版本或协调 DB 恢复；未证明兼容前不能用旧 JAR 顶回。将 BLOCKED 记录留档，再按批准计划调整清单和服务并重新验证。forced SSH 不具备解锁/任意回滚入口。
- 若仅入口配置出错，恢复旧 Nginx 配置并检查/reload；这不等价于数据库回滚。旧 7798 服务能否继续使用数据库须先审查迁移兼容性。
- 不自动 `down -v`、删除数据库/Redis/MinIO、全局 docker prune 或删除旧 release；16 GiB 磁盘需制定镜像和日志留存政策后人工按清单清理。

## 7. 依据

- [GitHub workflow_run 事件与权限](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)：成功条件和源代码信任必须显式检查。
- [GitHub 发布 Docker 镜像](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images)、[GHCR 权限](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)：区分构建发布与服务器只读拉取权限。
- [Compose services](https://docs.docker.com/reference/compose-file/services/)：raw env_file 需要 Compose 2.30+；现有 2.32.1 满足。host network 仅适用本次 Linux 服务器。

本地校验及提交收据见 `deploy/server/DELIVERY.md`。静态校验不能替代实际镜像构建、服务器健康和外部 HTTPS 验收。
