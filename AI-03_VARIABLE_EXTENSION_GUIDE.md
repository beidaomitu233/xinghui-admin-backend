# AI-03 内部变量扩展契约

## 服务入口与身份

`AiBusinessPromptRenderer.render(appKey, sceneKey, BusinessContext)` 仅为内部 Java 接口。任务中的 appId 使用 AI-02 的 `app_key`，没有另建应用编号或变量表。入口没有调用方 userId/tenantId 参数，也没有新增 HTTP 路由、供应商调用或产品页面。

身份从已认证的 SecurityContext/LoginUser 捕获为不可变 Actor。账号用户 ID 必须有效、账号启用、会话与账号租户一致。SQL 动态租户上下文不作为身份来源。平台提示词/模型管理权限不能代替应用的业务读取权限；平台身份同样没有跨租户豁免。

## 注册第二个应用

1. 在该应用代码中定义实现 `BusinessContext` 的小型具体类型，通常只含资源引用，不接收任意 JSON、SQL、URL、Bean 名或客户端身份。
2. 实现 `AiBusinessVariableResolver<C>` 并通过应用代码注册为 Spring Bean。固定声明 appKey、contextType、允许变量名集合和业务 requiredAuthority。注册器启动时捕获不可变元数据；重复 appKey、无效名称及 AI 配置管理权限注册均拒绝。
3. `findResource` 先校验上下文，再只查资源标识/所属租户元数据。缺记录返回 null；上下文错误抛固定 `CONTEXT_INVALID`。不能在元数据阶段返回或记录业务变量值。
4. 框架在调用元数据方法之前验证业务操作权限；随后检查资源存在和真实租户归属。应用的 `canRead` 必须继续执行负责人、部门或其他资源级规则，不能仅返回 true 来绕过业务权限。通过全部检查后才调用 `resolve`。
5. `resolve` 仅按已授权 Resource 的标识、tenant 和访问谓词读值；应用须用一致快照/事务或取值时重检保证并发归属变更不会绕过授权。上下文建议为不可变 record，不能重新使用未约束的客户端标识读另一资源。只返回当前已发布声明，值为字符串；缺失、null、空白或多余变量失败，不取示例、不跨应用补值、不返回部分结果。
6. 在应用单测中分别验证资源不存在、跨租户、同租户但无资源权限、上下文缺项、未登录及权限误配。两个中性示例解析器仅在 AI-03 测试类中存在，没有产品 Bean。

变量改名先让代码同时支持旧名与新名并完成测试，再修改草稿声明和发布；确认旧名已无发布快照引用后才移除旧名。未发布的草稿改名不影响当前运行快照。允许集合与已发布声明不匹配会返回 `VARIABLE_NOT_REGISTERED`，不会跨应用或用示例补值。

## 渲染边界

- 只读取 AI-02 的已发布、已启用且关联模型可用快照；返回模型 ID 和 publishedRevision，草稿变化不改变它们。状态检查针对读取时的快照，不代表未来供应商调用的授权租约。
- 使用 AI-02 固定 `{{name}}` 语法、声明及 NUMBER/BOOLEAN 校验。NUMBER 为可解析十进制；BOOLEAN 为 true/false（大小写不敏感）；STRING 不执行表达式。
- 每个值最多 2048 个 UTF-16 字符，总结果最多 12000 个；拼接前检查长度。变量最多 64 个。全部值通过校验后才产生最终结果。
- 替换一次；返回值中的 `{{other}}`、美元符号和反斜杠都保留为普通文本。
- 敏感真实值仅进入受控内部 RenderedPrompt.prompt；管理端 AI-02 样例预览继续脱敏。RenderedPrompt.toString 隐藏正文。异常只携带固定错误码，不保留解析器 cause、变量值或上下文；业务实现和调用方也不得记录这些内容。
