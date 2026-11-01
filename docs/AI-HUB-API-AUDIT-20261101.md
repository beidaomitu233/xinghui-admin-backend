# AI 中台接口边界审计

范围：`feature/AI-HUB-20261025` 的 `system-admin/src/main/java`。按源码盘点共 52 个带 `@RestController` 的控制器、284 个方法级 HTTP Mapping 注解；此数包含旧 AI 接口、协议、回调和传输，不能作为业务能力覆盖率。实际可执行能力以 `AgentResourceProvider.describe`、原业务权限和 [工具清单](AI-HUB-API-COVERAGE.md) 为准。

| 原控制器 | Agent 接入或保留方式 |
| --- | --- |
| LowcodeRecordController | forms：实际业务记录查询、增删改及受控批量；按表单与租户范围执行 |
| LowcodeFormController | form-definitions：完整草稿、字段、原生发布版本和兼容性校验；独立确认发布 |
| SysUserController | users：查询、用户基本信息创建/修改/删除；导入、导出和单独重置密码仍用原页面 |
| SysRoleController | roles：查询、基本 CRUD；单独授权分配保留原界面 |
| SysMenuController | menus：查询、CRUD 和局部排序；使用原服务校验，不开放任意路由执行 |
| DictionaryAdminController / DictionaryReadController | dictionary：类型和条目读取、原生新增/编辑；无额外脚本或 SQL |
| SysNoticeController | notices：公告查询与 CRUD |
| NotificationInboxController | inbox：只读当前接收人的通知，经确认标记具体通知已读 |
| SysConfigController | system-parameters：原参数白名单内查询和 CRUD |
| BusinessConfigController / WechatConfigController | business-config：保存版本及密钥保护沿用原服务；已有密钥不给模型 |
| BusinessConfigProbeController | 右侧测试界面调用；实际发短信/邮件需要明确接收者，不作为自动探测副作用 |
| SysJobController | jobs：查询、状态与手动运行；调度器定义/删除保留原页面 |
| SysJobLogController / SysOperLogController | job-logs / operation-logs：检索；日志删除、清空保留原管理界面 |
| SysOssController | files：原账号文件元数据；二进制上传下载仍走原受权接口，外部对象删除未包装成可回滚事务 |
| SysTenantController / SysTenantPackageController | tenants / tenant-packages：平台身份查询；CRUD 保留原页面 |
| SysProfileController | 原个人资料/密码页面；不能以 Agent 会话替代身份凭据验证 |
| LowcodeSharingController / LowcodeFillController | 发布链接、外部填报和授权检查沿用原页面与分享令牌，不把持有令牌当成后台管理权限 |
| LoginController / SmsLoginController / WechatAuthorizationController / WechatLoginFlowController | 原认证、验证码、身份回调和账号关联，不作为模型工具 |
| SysUserRoleController / SysRoleMenuController | 当前源码没有方法级 HTTP Mapping；不虚构可调用接口 |
| ModelHubController / AiSettingsController / AiConfigurationController / AiModelCatalogController / AiModelConfigAdminController / AiModelTestController | AI 配置与真实模型测试界面；不向 Agent 暴露已有密钥或任意供应商 HTTP 调用；管理工具尚未开放 |
| SceneAdminController / SceneRoutingController / AiPromptSceneAdminController / AiSceneReadController / TemplateAdminController / AiApplicationAdminController | 当前分支只有一个 Agent 设置入口，提示词和模型顺序并入 AI 配置；保留旧业务协议/数据兼容，无独立提示词菜单；不让 Agent 自行批准配置变更 |
| SceneQueryController / SceneDataController / SqlAssistanceController / DataResourceAdminController | 普通产品原数据引用协议保留；中台通过业务 Skill 发现字段，不开放任意 SQL 或数据源权限 |
| AgentWorkbenchController / AgentChangeController | 前端受身份绑定的会话、图片、取消及原用户确认协议；模型不能代替用户调用确认接口 |
| ConversationController / AiRuntimeController / SceneRuntimeController | 原调用、流式传输和历史协议；通过官方 Harness 与现有模型桥接复用，不反射为循环调用工具 |
| AiCallLogController / AiRequestLogController / SceneMonitorController | 受权限约束的调用监控页面；上游响应存档不是可被模型任意导出的业务资料 |

无 Shell、任意 HTTP、任意 SQL、Java 反射或模型自选身份入口。用户新增业务表单后，forms Skill 自动使用真实 schema 与当前权限，不需要按商品名称或用户意图编写固定工作流。

本轮实际模型验收使用独立 QA 数据：商品新增、图片新增、价格修改计划及页面确认；其余适配器使用原服务合同、权限撤销和目标锁测试。没有把这些局部验收描述成每个未开放接口都已实现或外部服务全部联调完成。
