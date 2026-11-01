# 星辉 2.0

以 AI 为核心的应用开发后台。基于 Java 17、Spring Boot、AgentScope Java，连接模型、Agent 与企业业务。

## 功能

- AgentScope Harness：自然语言与图片任务、计划、会话、业务工具。
- 原生 Skill、远程 MCP，Dify/FastGPT 外部知识库检索。
- 多供应商业务模型、提示词场景、模型顺序与兜底。
- AI 统计：费用、Tokens、缓存、首输出、耗时、时段、模型对比与质量评价。
- 多角色与页面/模块/动作权限、租户、表单、文件、通知、任务与服务商配置。

## 启动

准备 Java 17、Maven 3.9.x、MySQL、Redis。配置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、Redis 连接、`JWT_SECRET` 与 `CONFIG_MASTER_KEY` 后执行：

```bash
mvn -B -ntp clean package
java -jar system-admin/target/system-admin.jar
```

Flyway 自动执行数据库迁移。新环境首次登录后修改初始化账号。

## 业务扩展

实现 `AgentResourceProvider` 并注册为 Spring Bean，让 Agent 调用已有业务服务。`system-agent` 负责官方 Harness 接入，`system-admin/.../ai/agent` 提供当前业务和扩展适配。

[配套前端](https://github.com/beidaomitu233/xinghui-admin-vue3) · [开发文档](https://admin.hui7.fun/docs/) · [版本历程](docs/versions.md)

## 版本

v1.0：2025年11月，后台基础框架。v1.5：2026年7月左右，通用业务与 AI 接入。v2.0：2026年11月，Agent、工具扩展与 AI 统计。

通过 GitHub Actions 构建镜像并部署服务器，部署工具位于 `deploy/server`。
