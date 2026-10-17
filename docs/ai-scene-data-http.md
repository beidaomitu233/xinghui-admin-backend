# AI-UX-C 数据候选 HTTP 合同

实现入口 `/system/ai/scene-data`；仅GET，不读取/写入场景配置、不调用模型。当前实现待测试验收，不代表上线。C0共享签名见AiSceneDataRuntime。配合B合同：保存的无效静态配置统一HTTP400/Result.code202，InvalidConfiguration.diagnostics由B转换为白名单field错误；不另用422制造两套保存规则。

所有四接口需平台身份且有 `ai:scene:query/add/edit/test` 任一动作。字段/表单另需 `lowcode:record:query`；样本另需 `lowcode:record:list`。新动作由B的V225登记，C不另建迁移授权租户。身份从现有真实登录解析，query中的任何身份字段都不构成授权。

| GET路径 | query | data |
| --- | --- | --- |
| `/catalog` | 无 | `{objects:[{objectKey,name,description}],inputVariables:[{name,label,type,required,maxChars}]}` |
| `/forms` | `keyword?,pageNum=1,pageSize=20` | `{records:[{formId,name,schemaVersion}],total:"0",pageNum:1,pageSize:20}` |
| `/fields` | `objectKey=lowcode.record&formId=<候选ID>` | `{objectKey,formId,schemaVersion,fields:[{fieldKey,label,type,nullable,variableName}]}` |
| `/samples` | `objectKey=lowcode.record&formId=<候选ID>&keyword?&pageNum=1&pageSize=20` | `{records:[{recordId,label,schemaVersion,revision}],total:"0",pageNum:1,pageSize:20}` |

返回统一Result包装，所有ID与total为字符串、版本/revision/页码为整数。页码1–100000、pageSize1–100、keyword最多100字符，LIKE按字面转义。samples按当前有权记录的subject或记录ID做字面包含搜索；subject必须存在于记录原schema，只读取该字段的最多80字符作为标签，空值回退“记录 ID”。不读取internal_note或整行正文作标签。原schemaVersion不强制等于表单最新版本，标签含用户内容时前端使用文本渲染，不用v-html。

catalog无业务query权限时objects为空，但仍可编辑不取数的普通提示词。forms仅返回现有低代码runtime权限语义下已发布启用的表单，不要求取得表单设计权限；现有低代码表单本身为平台定义，无额外表单角色ACL。本接口不扩展其授权模型。samples严格当前tenant+form+未删除记录，不限creator本人，与现有记录查询一致。无query/list则明确403，不返回任何相关总数/名称。

字段目录仅subject/summary与实际发布schema交集。type保持低代码原类型，variableName固定为record_subject/record_summary，internal_note及新字段不返回。正文使用`{{user_request}}`表示受控用户输入；`{{record_subject}}`等在运行时以授权投影值进行单次插值，输出到USER而非SYSTEM，业务值中的占位符不二次展开。null插值文本为null，独立JSON消息仍保留真实null、数字等原类型。只有已勾选字段的变量才可用，保存时服务端再校验，不读取样本值。

数据/字段读取失败不携带实际输入、SQL、堆栈或记录正文。无缓存；错误映射：参数400、未登录401、权限403、资源不可用409、超时504、依赖503；Result业务码沿用一期AiHttpFailures。候选不等于运行授权，render仍检查真实当前用户、tenant、记录、原schema及字段类型。所有未知/额外字段均不能成为SQL、URL、Bean或权限输入。

## B接线

把Definition.promptText/outputRequirement映射到EditorInput.prompt/outputInstructions；dataObjects的每项附上scene头固定formId生成Selection。compile只访问授权schema元数据，结果CompiledScene必须随sceneVersion原样持久化。render使用服务端SceneContext(sceneKey,sceneVersion,actor,businessRef,requestId)，不接受客户端CompiledScene。

预览不得直接将RenderedScene敏感消息作为HTTP响应。B应按预览合同脱敏输入和业务数据，保持no-store/private；实际模型执行才使用未掩码消息。

## 旧V04模板内部接缝

`AiSceneLegacyTemplates.inspect(TemplateVersionRef, Invocation, control)` 由B传入明确映射的精确旧模板/真实旧app上下文，不接受客户端随意指定的版本。返回LegacyView：name/version、CONVERTIBLE或READ_ONLY、固定reasons、requiresConfirmation、conversionNotes、可转换EditorInput、原消息/原配置和旧批准范围。完整原配置仅供有权管理详情，不能整对象直接投递日志或通用HTTP。原消息保留原分区，转换提示明确说明合并为USER及新读取保留null的语义差异；查看时不保存或发布。

`compileEdited(version, legacyInvocation, EditorInput, control)` 只在显式合法保存时调用；生成独立CompiledScene，不修改旧共享模板。批准字段只能收窄，保留原精确资源版本/应用/主体范围。返回CompiledScene.legacyScopes必须和执行快照一同持久化；后续编辑旧映射场景仍走此方法，不能改调普通compile而丢掉旧约束。C render/authorize按当前启停、成功测试证据、配置哈希、批准人、字段/主体/applications重新校验。移除所有数据对象时不再附加无关资源范围。

旧执行仍通过原AiTemplateRuntime精确版本运行，直到用户明确保存新快照；旧AI02场景不合并。特殊默认值/额外变量/不支持资源映射等返回READ_ONLY及原配置，禁止静默丢弃后自动转换。变量truncate=true返回VARIABLE_TRUNCATION_UNSUPPORTED；user_request.required与原文slot引用不一致返回INPUT_REQUIRED_POLICY_CHANGED，输入长度非2048返回INPUT_LENGTH_POLICY_CHANGED。业务别名只允许已授权元数据确认的text/textarea；旧变量maxChars不足覆盖类型注册上限（2000/5000）返回RESOURCE_VARIABLE_LENGTH_POLICY_CHANGED，其他类型返回RESOURCE_VARIABLE_TYPE_POLICY_CHANGED，schema元数据不可用返回RESOURCE_SCHEMA_UNAVAILABLE。保守只读保留原配置，不以新schema较小长度推断所有历史记录，不丢弃原默认/截断/必填策略。

旧template的save、publish、enabled与持久test已接入AiSceneOwnership.ownsTemplate检查，和实际变更共用AiApplicationReferences.withReferenceGuard；已接管时由C生成固定ServiceException并映射HTTP409/业务码201/message=SCENE_MANAGED，避免依赖B异常类型。Spring生产构造器要求ownership Bean可用，缺失时依赖失败关闭写入；原显式非Spring构造器保留前scene兼容。B接口/Bean需一同集成，其查询必须是当前读，不能在旧RR快照中误判未接管。只读预览和旧不可变版本读取保留，不开放旁路覆盖scene配置。
