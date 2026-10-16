# AI-04 场景读取接入说明

## 业务代码接入

1. 按 AI-03 指南登记 `AiBusinessVariableResolver<C>`：固定 appKey、具体不可变 context 类型、变量名和业务操作权限；先取资源元数据并校验租户/资源权限，再读取值。生产默认不安装中性测试应用。
2. 为同一 app 登记 `AiSceneContextConverter<C>` Spring Bean。`appKey/contextType` 必须与 resolver 对应；明确 `allowedFields/requiredFields`，最多 16 个。转换器只校验引用格式并创建 context，不能读取业务值、记录输入或通过输入查找类/Bean。
3. 客户端使用已有 Authorization 登录 token 调用 `POST /ai/scenes/{appKey}/{sceneKey}/render`，正文为 `{"context":{"resourceId":"..."}}` 或该应用登记的字符串字段。所有额外字段、嵌套结构、重复字段和客户端身份均拒绝。
4. 接收 Result.data 的五个字段。modelId 为十进制字符串；prompt 可能含敏感业务值，调用方应按当前主体处理。错误仅固定分类，无部分结果。

完整字段、状态码、16KiB 限制、禁止缓存和最小审计规则见 [HTTP 契约](AI-04_HTTP_CONTRACT.md)。

## 运行与安全边界

- 服务器身份沿用 SecurityConfig/LoginFilter/JWT + Redis 登录缓存；平台配置权限不能替代登记业务权限。
- 只读已发布、启用的场景快照及可用模型；更新草稿不改变运行结果。字符串单次替换、变量类型和输出长度仍由 AI-03 执行。
- boundary filter 在安全链前执行，对容器已解码 servletPath/pathInfo 和常规原始路径匹配。未知长度正文也只读取 16385 字节即可判定拒绝；`/%61i/scenes/...` 的真实容器测试已验证。
- 专用日志只包含服务端 trace、主体引用、已登记 app、成功 scene/发布修订、固定分类和状态。Controller 禁止添加通用 `@Log`；当前通用切面仅匹配该注解，不进入读取链路。
- 本接口不读取模型 endpoint/凭据环境变量，也不发起供应商调用。未来服务端 Gateway 使用 modelId 在受控服务中查配置，并重新校验调用时可用性、凭据和出站边界。
- 无数据库迁移、调用计费、配额或管理页面。
