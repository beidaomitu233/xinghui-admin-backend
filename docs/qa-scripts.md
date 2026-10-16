# 私有环境 QA 脚本

脚本保留开发与验收用途，执行者须提供专属资源。报告目录放在仓库外或被忽略的私有目录。不要把环境变量、日志、DPAPI 文件或真实账户提交到 Git。

| 脚本 | 必需输入及作用 |
| --- | --- |
| `Start-Cfg02Qa.ps1` | `-QaDirectory` 或 `CFG02_QA_DIRECTORY`；`DB_URL/DB_USERNAME/DB_PASSWORD`、`SPRING_DATA_REDIS_HOST/PORT/DATABASE/PASSWORD`、`CONFIG_MASTER_KEY`、`MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET`。启动仅监听回环的 Java，生成临时 JWT，正常执行严格 Flyway，输出 PID 和日志。需预先打包。 |
| `Test-Cfg02HttpQa.ps1` | 私有报告目录、`-BaseUri` 或 `CFG02_QA_BASE_URI`、`CFG02_QA_PHONE/CFG02_QA_PASSWORD`、`MINIO_ACCESS_KEY/MINIO_SECRET_KEY`；执行配置和 OSS 写入、重启阶段检查及精确清理。 |
| `Read-ConfigMasterKey.ps1` | 显式 `-Path` 指向执行者的 DPAPI 文件。由同一 Windows 用户读取；返回明文给调用方，不应直接打印到共享终端。 |
| `Import-OpenRouterModelsViaApi.ps1` | 显式 `-KeyFile` 和脚本参数。导入模型配置，可修改配置数据库。 |
| `Import-Cfg02OpenRouterQa.ps1` | `CONFIG_MASTER_KEY`、`CFG02_DB_PASSWORD`、`CFG02_PROVIDER_KEY_FILE`；运行原生配置导入测试，默认 Maven 来自 PATH，可传 `-Maven`。 |
| `Test-Cfg02OpenRouterQa.ps1` | `CONFIG_MASTER_KEY`、`CFG02_DB_PASSWORD`、`CFG02_REDIS_PASSWORD`、`CFG02_QA_DIRECTORY`；显式启用真实供应商测试。可产生费用，失败须人工审查，脚本不自动重试；`-RepairRetry` 是人工选择的单次后续尝试。 |

环境变量未提供时，辅助函数只报告缺少的变量名。原生 Java QA 测试可能绑定特定本地数据库、端口和 QA 记录；运行前阅读对应测试的目标、写入及清理边界，按执行环境配置。脚本不设置全局代理或 Java/Maven 安装路径，也不隐式读取开发者私有辅助脚本。

CI 中固定中性密码和全零测试主密钥只用于隔离服务夹具。迁移中的初始化密码散列属于历史初始化材料，首次启动后更换初始化账户密码。不要修改已执行迁移以清除这些历史散列。
