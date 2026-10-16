# AI-04 业务场景读取 HTTP 契约（2026-10-11）


## 请求

`POST /ai/scenes/{appKey}/{sceneKey}/render`，Content-Type: application/json，Authorization 沿用真实 LoginFilter JWT + 当前服务端登录态校验。

正文只允许 `{"context":{"resourceId":"…"}}` 的外层结构。具体 context 字段由该 app 的代码转换器登记，最多 16 个字段，每值为不超过 256 UTF-16 字符的字符串；禁止对象/数组/数字/布尔值和未登记字段。转换器明确声明必填字段并校验资源引用格式，输出已有 AI-03 的具体不可变 context。客户端不能指定或覆盖用户、租户、权限、Java 类型、Bean、SQL、URL或表达式。

传输正文最多 16384 字节，包括未知长度/chunked 请求；不接受压缩编码。JSON 深度上限 3，禁止重复字段和尾随内容。app/scene 使用 AI-02 标识规范。解码仅调用代码登记的转换器，不做反射、多态反序列化或按输入查找 Bean。

## 成功响应

沿用 Result：HTTP 200、code=200、message=成功，data 仅为 `appKey, sceneKey, publishedRevision, modelId, prompt`。modelId 使用十进制字符串保留 bigint 精度，不返回 provider 配置对象、endpoint、credentialEnvName、草稿或变量值字典。此正文可能含敏感业务值，只用于当前授权业务调用方。

认证身份完全来自服务器。原 AI-03 renderer 在取值前执行注册业务操作权限、资源存在、租户及应用资源权限检查；平台配置管理权限不能代替业务权限。只读已发布且启用的快照和可用模型；草稿变化不改变运行快照。返回值中的占位符仍只插入一次。

## 错误响应

所有失败 data=null，不回显输入、异常 cause 或部分提示词。业务 message 为固定 `AI_SCENE_READ:<分类>`：

| HTTP | Result code | 分类 |
|---|---|---|
| 400 | 202 | INVALID_REQUEST / CONTEXT_INVALID |
| 401 | 311 | UNAUTHENTICATED / INVALID_IDENTITY；LoginFilter 入口沿用原未登录消息 |
| 403 | 309 | BUSINESS_PERMISSION_REQUIRED / TENANT_MISMATCH / RESOURCE_FORBIDDEN |
| 404 | 204 | APP_NOT_REGISTERED / RESOURCE_NOT_FOUND |
| 409 | 201 | SCENE_UNAVAILABLE / VARIABLE_NOT_REGISTERED |
| 413 | 202 | REQUEST_TOO_LARGE |
| 415 | 202 | UNSUPPORTED_CONTENT |
| 422 | 202 | MISSING_VARIABLE / UNDECLARED_VALUE / INVALID_VARIABLE_VALUE / OUTPUT_TOO_LONG |
| 503 | 203 | RESOLVER_FAILED / INTERNAL_FAILURE |

正文传输限制优先执行，普通未认证请求由原安全链返回 401。HTTP 响应统一 `Cache-Control: no-store`、`Pragma: no-cache`、`X-Content-Type-Options: nosniff`；服务端生成 `X-Request-ID`，不相信客户端追踪标识。

## 最小审计与边界

专用日志仅含服务端追踪 ID、主体 ID/tenant 引用、已登记 app、成功快照的 scene/publishedRevision、固定结果分类及 HTTP 状态。失败时未知 scene 用占位符；不记录请求 URI、body、context、变量值、prompt、token、密钥或完整响应。Controller 不加通用 @Log；既有 LogAspect 仅匹配 @Log，不进入此链路。未认证/传输层拒绝也产生分类审计，无当前主体时为空。

不新增数据库模型/审计迁移，不使用 WX 的 V110，不调用 Provider/Gateway。未来服务端 Gateway 仅按返回 modelId 读取允许配置，另行检查调用时模型状态和出站边界。本接口不解析凭据，也不提供管理页面。
