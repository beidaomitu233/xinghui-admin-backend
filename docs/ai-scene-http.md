# 场景配置与调用 HTTP 合同

本合同定义 AI-UX 新接口，配套类型为 `com.xinghuiTec.ai.scene.SceneDtos`。接口实现与集成验收仍需完成，不能把类型定义当作服务已上线。原应用调用和请求账本继续兼容；新调用使用真实 `sceneKey` / `sceneVersionId`，不把它们冒充 `appId`。

## 公共约定

所有 ID 为字符串。分页固定 `records,total,pageNum,pageSize`，`total` 为十进制字符串。时间为 ISO-8601 UTC，界面按本地时区显示。成功 HTTP 200、受理 202 均使用 `{code:200,message:...,data:...}`。

`sceneKey` 由服务端生成：摘要为 `lowcode.record.summary.<formId>`，通用场景为 `scene.<服务端UUID无连字符>`。名称可改，key、种类和绑定表单不可由保存接口改换。摘要 formId 由有权候选列表选择；重复摘要场景返回 409，不猜最新 ID。

权限动作：管理列表 `ai:scene:list`；详情/模型选项 `ai:scene:query`；创建 `ai:scene:add`；保存 `ai:scene:edit`；启停 `ai:scene:enable`；预览/主动试运行 `ai:scene:test`。工作区列表必须 list，含 selected 正文还必须 query；无 query 不返回正文，显式请求 selected 则403。所有管理动作还要求既有平台身份，不向租户开放配置权限。新原生场景只允许真实当前登录用户且具有 `ai:scene:invoke`，不从保存者身份派生后台服务主体；有数据对象时仍需 C 的真实记录查询权限与数据范围。旧迁移场景同时保留原主体/动作限制，新场景接口不能扩大旧授权。管理权不等于取数权。

## 管理入口

| 方法与路径 | 输入 | 成功 data |
| --- | --- | --- |
| GET `/system/ai/scenes` | `pageNum=1&pageSize=20&search=` | `Page` |
| GET `/system/ai/scenes/workspace` | 同分页及可选 `selectedSceneKey` | `Workspace {scenes,selected}`；未指定时 selected=null |
| GET `/system/ai/scenes/{sceneKey}` | 无正文 | `Detail {scene,definition}` |
| POST `/system/ai/scenes` | `Create` | `Detail`；只创建稳定身份，初始 revision=0/currentVersionId=null |
| PUT `/system/ai/scenes/{sceneKey}` | `Save` | `SaveResult {detail,clientMutationId,effectiveFrom}` |
| POST `/system/ai/scenes/{sceneKey}/enabled` | `Enabled` | `SaveResult` |
| GET `/system/ai/scenes/options/models` | `outputFormat=TEXT` | `ModelOption[]`；无供应商调用 |
| GET `/system/ai/scenes/options/keys` | `modelRef&configVersion` | `KeyOption[]`；无明文/密文/envName |
| POST `/system/ai/scenes/{sceneKey}/preview` | `PreviewInput` | `Preview`；真实授权、脱敏、不调用模型 |
| POST `/system/ai/scenes/{sceneKey}/test-requests` | `TestInput` | HTTP 202 `Request`；仅明确 confirmedCosts=true 后执行 |

工作区 selected 与列表使用一致读取边界，不要求前端拼接 application/template/resource 表。模型选项和 C 负责的 `/system/ai/scene-data` 目录独立按需加载；只返回真实有权候选。

创建样例（数据只是占位示例，不创建真实配置）：

```json
{"name":"记录摘要","purpose":"概括当前记录","kind":"RECORD_SUMMARY","formId":"42","clientMutationId":"6f021aaf-006d-4164-acd2-3ffcdaf54c4f"}
```

新身份默认 enabled=true 表示预期启用，但 currentVersionId=null、availability=UNCONFIGURED，不能运行。第一份合法配置保存即生效；保存始终保留当前启停意图，停用后保存不会意外开启。新建后无需另走发布/激活步骤。

Detail 增加 `legacy`（原生或完成显式转换后为 null）：`{compatibility:CONVERTIBLE|READ_ONLY,reasons,requiresConfirmation,conversionNotes,editor:{promptText,outputRequirement,dataObjects}|null,originalMessages:[{section,text}],originalDefinition:{...旧配置...}}`。此信息只给有 scene:query 的管理详情，配置正文仍不进入业务 Entry/日志。读取及首次加载绝不写入。READ_ONLY 无法无损转换时显示原内容与固定原因，不补空配置覆盖；CONVERTIBLE 可用 editor 作为显式转换编辑起点。

Save 增加 `confirmedLegacyConversion:boolean`（可省略，默认 false）。仅旧版本第一次转换时，若 legacy.requiresConfirmation 为 true，须用户明确确认 conversionNotes 后传 true，否则409 LEGACY_CONVERSION_CONFIRMATION_REQUIRED；保存无效或事务回滚不算已转换。之后 native snapshot 的编辑不再重复确认，但固定 legacy base 和批准资源边界永久保留。

完整保存样例：

```json
{
  "expectedRevision": 0,
  "clientMutationId": "1696d91a-a75e-4cc8-8d11-e5e3306b4646",
  "definition": {
    "name": "记录摘要",
    "purpose": "概括当前授权记录",
    "promptText": "概括当前记录，遵守用户要求：{{user_request}}",
    "outputRequirement": "输出简短中文摘要",
    "dataObjects": [{"objectKey":"lowcode.record","formId":"42","fields":["subject"]}],
    "modelPolicy": {
      "mode":"FIXED",
      "models":[{"model":{"modelRef":"17","configVersion":"8"},"keyMode":"AUTO","allowedKeys":[],"fixedKey":null,"fallbackKeys":[]}],
      "fallback":false,
      "outputFormat":"TEXT",
      "timeoutSeconds":30,
      "maxAttempts":2,
      "maxConcurrent":2,
      "maxTokens":1024
    }
  }
}
```

变量/内置对象由 C 的唯一目录及编译契约校验；`dataObjects` 不能增加 SQL、URL、类名、身份或任意记录正文。每个选择含 objectKey/formId/fields，表单来自安全候选；摘要 formId 可省略由服务端补绑定表单，提供时必须一致。GENERAL 带对象必须明确安全表单选择。摘要只允许绑定表单内的授权 subject/summary 字段，排除 internal_note。promptText 是单编辑器完整指令，业务数据独立消息注入。

自动保存约 600ms debounce，同场景单写在途。expectedRevision 必须匹配；同 clientMutationId 同内容重放原保存结果，不同内容 409。合法完整保存在事务内 CAS、生成不可变内部快照和真实 sceneVersion、切 current 指针；响应确认后才显示已保存。无效中间输入 HTTP400，客户端保留输入，服务端不保存另一个待发布 buffer，也不改变旧有效配置。模型暂不可用不阻止合法保存，availability 返回 UNAVAILABLE 及安全原因；配置已生效不等于当前供应商可用。保存不读真实业务记录、不测试 Key、不调用供应商，不依赖成功 BUSINESS/TEST。

`Availability.state` 为 READY/UNCONFIGURED/DISABLED/UNAVAILABLE；reasons 为固定安全分类。currentVersionId 为 null 时没有可执行配置。Source=NATIVE/LEGACY_V04 说明映射来源，旧 AI02 场景仍走独立兼容路径。

## 实际业务

| 方法与路径 | 输入 | 成功 data |
| --- | --- | --- |
| GET `/ai/scenes` | 可选 `kind&formId`，受控候选 | `Entry[]`，最多100条 |
| GET `/ai/scenes/record-summary` | `formId` | `Entry` 或 null；不返回 prompt/内部 appId |
| POST `/ai/scenes/{sceneKey}/requests` | `Submission` | HTTP202 `Request` |
| GET `/ai/scenes/requests/{requestId}` | 无正文 | `Request`，严格 owner+tenant+当前访问 |
| POST `/ai/scenes/requests/{requestId}/cancel` | 无正文 | `Request`；不撤回已输出内容 |
| GET `/ai/requests/{requestId}/events` | 既有 SSE 连接和 Last-Event-ID | 保留一期有界 SSE 事件合同 |

```json
{"businessRef":{"type":"lowcode.record","id":"42:305"},"inputs":{"user_request":"请概括重点"},"idempotencyKey":"4a1f8cc3-b769-4ba2-a902-e80e3e79a738"}
```

只传受控记录引用和用户输入，不传 actor/tenant/appId/模板版本/业务正文。服务端校验摘要场景与 formId 对应，C 核对真实记录、schema、字段与当前主体。受理固定当前 sceneVersion，同幂等键同输入返回原请求，即使随后保存新版；不同输入409。在途不随场景编辑换内容/重启输出。

GENERAL 无数据对象时，预览/试运行/业务提交省略 businessRef（或 null），服务端生成语义引用 `{type:"ai.scene",id:"<该真实sceneKey>"}`；这是场景级调用关联而非伪造业务记录 ID，不读业务记录。客户端若提供不同引用则400。GENERAL 有数据对象或 RECORD_SUMMARY 必须由安全记录选择生成 lowcode.record 引用；不可手输或伪造别的表单。预览/试运行还要求 scene:test 与 scene:invoke 及实际数据权限，不借管理身份绕过。

`Request` 返回 requestId、sceneKey、sceneVersionId、kind（BUSINESS/TEST）、businessRef、status/stage/errorCode、outputStarted/attemptCount、nullable token 用量与时间。不包含 appId。原 `/ai/applications` 和 `/ai/requests` 兼容入口保留原响应，不改变旧 ID 含义；scene 管理接管的旧写接口返回 SCENE_MANAGED，不能绕过新 CAS。

主动试运行的 expectedRevision 必须对应已保存版本，modelIndex 从0计；固定该版本直到结束，不因后续自动保存失效，不是保存/启用前置。preview 同样固定当前已保存版本，只返回有权脱敏预览及安全摘要。

## 真实监控

GET `/system/ai/monitor/summary?scope=OWN&requestKind=BUSINESS&from=<UTC>&to=<UTC>&sceneKey=&modelRef=&result=` 返回 `Monitor`。默认最近24小时半开窗[from,to)，最长31天；scope 默认OWN，AUDIT必须平台+ai:request:audit，同时具 ai:request:list；不因统计接口扩大详情/导出权。

`keyCards.enabledKeyVersions` 仅当前ACTIVE配置版本有效admitted pairs去重(configId,keyRef,keyVersion)，还需配置查询权限；`knownAdmittedPairs` 是搭配数。`recentlyFailedKeyVersions` 是这些当前Key版本与窗口内真实失败attempt交集；排除取消和供应商调用前的输入/业务取数错误，不能据此宣称实时健康、冷却或失效。keyVersion为空的历史attempt计unattributedAttempts，不归到当前Key。

无配置查询权限时相应计数为null；不把OWN日志的未观察到异常冒充全局零异常，返回unavailableReason。calls严格使用同一OWN/AUDIT范围和scene/model/result/window过滤；requests与attempts分别计算，测试与业务分开。known token之和可为null，unknownAttempts单列；不估算费用/额度/负载/队列。监控读取不调用供应商。

## 错误

沿用 HTTP/Result：400/413/415/422→202，401→311，403→309，404→204，409→201，429/503/504→203。message 为固定安全分类；结构/字段错误允许白名单 `ErrorDetails {fields:[{field,code}],currentRevision,currentVersionId,requestId}`，无供应商正文、实际字段值、SQL、密钥或输入指纹。纯安全失败 data=null。

主要分类：INVALID_INPUT、UNKNOWN_FIELD、MISSING_VARIABLE、DATA_OBJECT_FORBIDDEN、FIELD_FORBIDDEN、VERSION_CONFLICT、IDEMPOTENCY_CONFLICT、SCENE_MANAGED、SCENE_DISABLED、SCENE_UNCONFIGURED、MODEL_UNAVAILABLE、RESOURCE_FORBIDDEN、RESOURCE_TIMEOUT、DEPENDENCY_UNAVAILABLE。400不会保存无效配置；409保留客户端内容供冲突处理，不静默覆盖。
