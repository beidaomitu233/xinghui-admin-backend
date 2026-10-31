# AI 中台业务工具接入清单

本表按实际注册的 `AgentResourceProvider` 和原业务入口记录覆盖范围。不是自动把 Controller 反射成工具；模型不能选择任意 URL、SQL、类名或权限。

| Skill / 资源域 | 原业务入口 | 已开放能力 |
| --- | --- | --- |
| forms | `/lowcode/forms/{formId}/runtime`、`/records` | 已授权业务表单字段、记录查询、增删改及受控批量操作 |
| system-parameters | `/sysConfig` | 原参数白名单内查询、创建、修改、删除 |
| business-config | `/system/businessConfig` | 接入设置查询、按版本更新；现有密钥不返回模型 |
| users | `/system/user` | 用户查询、创建、修改、删除；联系方式脱敏 |
| roles | `/system/role` | 角色查询、创建、修改、删除 |
| dictionary | `/system/dict` | 类型、条目查询及原接口提供的新增/修改 |
| notices | `/system/notice` | 站内公告查询、创建、修改、删除 |
| jobs | `/monitor/job` | 已配置任务查询、状态切换及运行 |
| menus | `/system/menu` | 菜单列表与详情查询 |
| files | `/system/oss` | 原服务允许当前账号读取的文件元数据、分页检索；不返回对象 URL |
| operation-logs | `/monitor/operlog/list` | 平台操作日志摘要、查询和排序；不返回请求/响应正文或异常堆栈 |
| job-logs | `/monitor/jobLog` | 平台调度记录分页查询和详情 |
| form-definitions | `/lowcode/forms` | 平台表单定义和发布状态查询 |
| tenants | `/system/tenant` | 平台租户状态、套餐、容量、到期查询；不返回联系人明细 |
| tenant-packages | `/system/tenantPackage` | 平台租户套餐查询 |

写能力必须先产生具体变更预览，再由原登录用户确认，仍走原服务校验和业务审计。新增的七个查询组没有注册 `preview` 工具；不能通过指定其他 operation 获得写能力。

## 仍需接入

- 菜单与授权分配、表单设计/发布、租户与套餐的管理动作。
- 个人通知查询/已读、文件删除等业务能力；涉及对象存储的动作需要明确执行回执及部分失败处理，不能用数据库回滚冒充撤销外部操作。
- AI 配置、供应商/模型和提示词管理的业务操作；不得暴露已有密钥或让 Agent 自行批准自己的变更。
- 完整业务接口盘点、逐项权限/数据范围及真实页面验收。当前 15 个域仍不能作为“几乎全部接口接入”的完成证明。

## 有意不作为通用 Agent 工具

- 登录、验证码、微信身份回调、令牌签发/刷新、密码和身份凭据变更：保留原认证界面和身份验证过程。
- 下载/上传二进制传输、数据库引用/SQL 诊断、任意 HTTP、Shell、主机文件操作：不包装成任意执行通道；聊天图片仍使用原受控图片上传入口。
- 会话协议、内部审计写入、确认签署、供应商原始响应接收等内部接口：属于应用协议或用户操作，模型不能代替用户调用。

普通产品分支保持原 AI 辅助和数据引用流程。本清单只描述 `feature/AI-HUB-20261025`。
