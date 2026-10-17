# 数据库绑定合同（一期实用改造）

本合同与已提交Java接缝一起供A/B接线，当前仅类型可编译，以下新增HTTP与取数实现仍在开发，不能据此宣称可运行。共享类型统一为AiSceneDataRuntime嵌套records；B独占SceneDtos等外层，不由C复制。

## B必须增加的精确接线

1. Definition增加可选 `dataBindings: List<AiSceneDataRuntime.DataBinding>`，缺失按空列表；原dataObjects继续兼容读取。把promptText作为EditorInput.prompt，把历史outputRequirement非空内容先合并进可编辑正文；新UI不提供独立输出规则。`new EditorInput(prompt,"",legacySelections,dataBindings)` 静态编译，完整CompiledScene（包括bindings和legacyScopes）持久化。不能只保存别名或忽略bindings。
2. CompiledScene增加 `bindings: List<BindingSnapshot>`，EditorInput增加bindings，均保留旧构造器；旧JSON缺失字段归空。旧v1快照可读，新绑定使用scene-data-v2。新增默认方法在旧实现中明确CAPABILITY_UNSUPPORTED，下一提交接真实Bean，不返回假数据。
3. Submission/PreviewInput沿用BusinessRef；C行候选返回完整可直接提交的businessRef。低代码仍 `{"type":"lowcode.record","id":"42:305"}`；通用数据库使用 `{"type":"database.record","id":"db1.<base64url-UTF8-JSON>"}`，解码内容严格为 `{"sourceKey":"db.orders","key":{"id":"42"}}`。C生成/解析、验证真实表/键，token不授予读取权。
4. B的normalize及AiApplicationInputRules须允许上述database.record token（db1.后仅base64url字符，总长最多2048），作为类型化业务引用；不接收actor/tenant/SQL。绑定没有row.currentRecord=true时，GENERAL可省略businessRef并沿用ai.scene真实sceneKey关联；有CURRENT行绑定时必须传C候选引用并保持原请求快照/业务引用。同一引用须与绑定sourceKey一致。
5. 实际运行仍调用render/authorize，SceneContext签名不变。render会提供授权业务值；普通业务值可以在管理预览中实际显示，不能再统一替换已脱敏。预览请直接调用下面C的previewBindings或组合其结果；不得回显秘密，也不让管理权限替代实际数据权限。B保存只静态编译，不调用previewBindings。
6. 旧LEGACY_V04固定原基线与批准范围继续保留；不能经新增dataBindings绕过compileEdited扩展原资源审批。C将对旧基线的新bindings明确拒绝，普通原生场景使用新绑定能力。

## HTTP（均为 /system/ai/scene-data 下）

管理入口沿用平台身份与ai:scene:query/add/edit/test任一动作。通用数据库元数据、行候选、编译及实际读取另要求平台身份+新独立 `ai:data:database:read`；请PM唯一登记该动作并按明确角色授予，不能由导航/scene:invoke派生。运行时同样复核该权与实际账户/tenant，普通租户无此权不能通过别人保存的场景读全库。已知业务表叠加现有业务动作/tenant/软删；秘密字段和秘密表不进入值上下文。低代码虚拟来源沿用记录query/list与tenant/原schema权限。

| 方法路径 | 输入 | data |
| --- | --- | --- |
| GET `/database/tables` | keyword?,pageNum=1,pageSize=20 | Page<DatabaseTable>；sourceKey、tableName、label、comment、primaryKey |
| GET `/database/fields` | sourceKey=db.orders 或 lowcode.42 | DatabaseFields；fields含field/label/comment/type/nullable/primaryKey |
| GET `/database/relations` | sourceKey=db.orders | DatabaseRelation[]；真实FK双向目录；手动选择关系仍须实际字段存在/兼容 |
| POST `/database/records` | DatabaseRecordQuery | DatabaseRecords；records含key/label/values/businessRef，另含truncated |
| POST `/bindings/preview` | BindingPreviewRequest | BindingPreview；values为别名→实际JSON值，reads为别名/来源/条数/截断，notices安全提示 |

统一Result包装，ID字符串、数字/null/数组类型保留。GET tables分页1–100000、size1–100；每绑定最多200行、最多16绑定、64字段及16个AND过滤；查询limit+1判断截断，不扫完整总数。整体有时限与结果字节上限，越界明确拒绝或提示，不静默当完整。标识符先匹配当前schema元数据再引用，过滤值参数化，拒绝SQL/URL/Bean/函数表达式。

sourceKey的db.前缀后为实际数据库表名，表备注作为label优先显示；lowcode.后为有权表单ID，所有实际可读schema字段可选，不再限subject/summary。选择器不要求用户输入sourceKey或SQL。

## JSON示例

读取有权记录候选：
```json
{"sourceKey":"db.orders","filters":[{"field":"status","operator":"EQ","value":"OPEN"}],"limit":20}
```

保存或预览同一组绑定（表和字段仅为结构示例，实际来自元数据）：
```json
{
  "bindings":[
    {"variable":"客户.名称","label":"客户名称","sourceKey":"db.customers","mode":"FIELD","fields":["name"],"filters":[],"limit":1,"row":{"currentRecord":false,"key":{"id":"7"}},"relation":null},
    {"variable":"当前订单","label":"当前订单","sourceKey":"db.orders","mode":"ROW","fields":[],"filters":[],"limit":1,"row":{"currentRecord":true,"key":{}},"relation":null},
    {"variable":"订单明细","label":"当前订单明细","sourceKey":"db.order_lines","mode":"CHILDREN","fields":["sku","quantity"],"filters":[],"limit":50,"row":null,"relation":{"parentVariable":"当前订单","parentField":"id","childField":"order_id"}},
    {"variable":"未结订单","label":"未结订单","sourceKey":"db.orders","mode":"TABLE","fields":["id","amount"],"filters":[{"field":"status","operator":"EQ","value":"OPEN"}],"limit":100,"row":null,"relation":null}
  ],
  "businessRef":{"type":"database.record","id":"db1.<使用行候选返回的真实token>"}
}
```

Definition.dataBindings使用上面bindings数组。正文通过选择器插入 `{{客户.名称}}`、`{{当前订单}}`、`{{订单明细}}`、`{{未结订单}}`，允许中文、点号、字母数字下划线的稳定别名；值只单次替换。FIELD输出单个实际值，ROW输出对象，TABLE/CHILDREN输出数组；空fields表示该次编译固定所有可读字段，不因表后续加列自动扩大。

CHILDREN的parentVariable必须是前面同组ROW绑定，查询从该主记录真实parentField取值，按childField参数化等值约束，加上子表tenant/软删/过滤。关系不仅用于显示元数据，不允许与主记录无关地读取整个子表。指定关联字段必须真实存在且可读，可用真实FK或明确选定的兼容字段，不能是任意表达式。
