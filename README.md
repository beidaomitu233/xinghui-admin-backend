# 星辉后台管理系统

基于 Java 17、Spring Boot 3 的分模块后台开发候选，包含用户与权限、字典、配置、文件存储、任务、通知、站点、低代码表单和 AI 配置及普通应用运行能力。Agent、工具执行和 MCP 业务接入仍属后续建设范围。源码导入不代表正式发行或远端 CI 已通过。

配套前端：[xinghui-admin-vue3](https://github.com/beidaomitu233/xinghui-admin-vue3)。公开使用文档集中维护在前端仓库 `apps/docs/content/`，默认部署路径为 `/docs/`；部署者配置自己的访问域名。

## 本地启动

准备 Java 17、Maven 3.9、MySQL 和 Redis。在当前终端通过私密配置注入 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_PORT`、`REDIS_DATABASE`、`REDIS_PASSWORD`、`JWT_SECRET` 和 `CONFIG_MASTER_KEY`。默认 Redis 主机为回环地址，可通过 Spring 环境变量覆盖；准确映射见 `system-admin/src/main/resources/application.yml`。签名密钥与配置加密主密钥须独立生成，主密钥须稳定保存；不要使用测试夹具或提交真实凭据。

```bash
mvn -B -ntp -DskipTests package
java -jar system-admin/target/system-admin.jar
```

以上本地打包命令跳过了测试。初次启动使用专属空 schema，Flyway 执行版本迁移；升级前备份并校验历史。不要修改已执行迁移，也不要通过自动 baseline 或 clean 绕过失败。`sql/` 中历史 SQL 用于参考，不应重复导入已由 Flyway 管理的库。初始化账户须在首次登录后修改密码并审查权限。

邮件、OSS、短信、微信和真实模型供应商等能力须按需配置。Redis 承载部分运行状态和事件，重启或淘汰可能丢失临时事件；不要将其当作持久业务结果。租户开关默认关闭，扩展模块的行级隔离须单独落实。

## 模块和验证

| 目录 | 用途 |
| --- | --- |
| `common/` | 公共能力与可选服务适配 |
| `system-framework/` | 安全、运行配置和基础框架 |
| `web-admin/`、`web-app/` | Web 层模块 |
| `system-admin/` | 主应用、业务功能、测试和 Flyway 迁移 |
| `scripts/quality/` | 必跑测试清单、原生测试分类和报告门禁 |

GitHub Actions 的构建、隔离服务及最终质量门禁见 `.github/workflows/ci.yml`。本地人工或原生测试证据不能替代远端 CI；真实模型调用、既有本地资源和双 JVM 测试按 [测试范围](scripts/quality/AI-TEST-SCOPES.md) 单独执行。私有环境运维脚本见 [QA 脚本说明](docs/qa-scripts.md)。

## 许可证

本项目贡献者的原创代码使用 [MIT](LICENSE)。既有版权头、上游来源及第三方许可证继续有效，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。第三方依赖不因本项目选择 MIT 而重新授权。
